package com.example.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.client.PhotoNestApiClient
import com.example.core.data.FirestoreCoordinatorRepository
import com.example.core.data.PhotoNestDatabase
import com.example.core.data.SettingsDataStore
import com.example.core.media.SafStorageManager
import com.example.core.model.PairingPayload
import com.example.core.security.TlsCertManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PhotoNestTlsLoopbackIntegrationTest : FirestoreEmulatorTestBase() {

    private lateinit var context: Context
    private lateinit var db: PhotoNestDatabase
    private lateinit var settingsStore: SettingsDataStore
    private lateinit var storageManager: SafStorageManager
    private lateinit var tlsCertManager: TlsCertManager
    private lateinit var firestoreRepo: FirestoreCoordinatorRepository
    private lateinit var apiClient: PhotoNestApiClient
    private var server: PhotoNestHttpServer? = null

    @Before
    fun setUpLoopback() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhotoNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsStore = SettingsDataStore(context)
        storageManager = SafStorageManager(context)
        tlsCertManager = TlsCertManager(context)
        firestoreRepo = FirestoreCoordinatorRepository(firestore, auth)
        apiClient = PhotoNestApiClient(context, tlsCertManager, storageManager)
    }

    @After
    fun tearDownLoopback() {
        server?.stop()
        db.close()
    }

    @Test
    fun `tls loopback server handles pairing chunked upload range streaming delete and revocation`(): Unit = runBlocking {
        System.err.println("[LOOPBACK] 0 start")
        val ownerUid = kotlinx.coroutines.withTimeout(5000L) { signInTestUser("loopback_owner@example.com") }
        System.err.println("[LOOPBACK] 1 signed in: $ownerUid")
        val serverId = "srv_loopback_01"
        val secret = kotlinx.coroutines.withTimeout(4000L) { settingsStore.getOrCreatePairingSecret() }
        System.err.println("[LOOPBACK] 2 got secret")

        val httpServer = PhotoNestHttpServer(
            context = context,
            db = db,
            settingsStore = settingsStore,
            storageManager = storageManager,
            tlsCertManager = tlsCertManager,
            firestoreRepo = firestoreRepo,
            onEventLog = {}
        )
        server = httpServer
        httpServer.start(preferredPort = 0, serverId = serverId, ownerUid = ownerUid)
        System.err.println("[LOOPBACK] 3 server started on ${httpServer.boundPort}")

        assertTrue(httpServer.isRunning)
        assertTrue(httpServer.boundPort > 0)
        assertEquals(64, httpServer.certFingerprint.length)

        val validPayload = PairingPayload(
            serverId = serverId,
            ownerUid = ownerUid,
            serverName = "Test Vault",
            hostAddress = "127.0.0.1",
            port = httpServer.boundPort,
            certFingerprint = httpServer.certFingerprint,
            pairingSecret = secret,
            expiresAtMillis = System.currentTimeMillis() + 60_000L
        )

        // 1. Wrong secret should be rejected (403)
        System.err.println("[LOOPBACK] 4 testing badSecret")
        val badSecretRes = kotlinx.coroutines.withTimeout(4000L) {
            apiClient.pairWithServer(
                payload = validPayload.copy(pairingSecret = "wrong_secret"),
                deviceId = "dev_bad"
            )
        }
        assertTrue("badSecretRes expected failure", badSecretRes.isFailure)
        System.err.println("[LOOPBACK] 5 badSecret passed")

        // 2. Wrong TLS certificate pin should fail handshake
        val wrongPinRes = kotlinx.coroutines.withTimeout(4000L) {
            apiClient.pairWithServer(
                payload = validPayload.copy(certFingerprint = "0".repeat(64)),
                deviceId = "dev_pin_fail"
            )
        }
        assertTrue("wrongPinRes expected failure", wrongPinRes.isFailure)
        System.err.println("[LOOPBACK] 6 wrongPin passed")

        // 3. Valid pairing succeeds and stores persisted token
        val paired = kotlinx.coroutines.withTimeout(4000L) {
            apiClient.pairWithServer(validPayload, deviceId = "dev_pixel").getOrThrow()
        }
        assertEquals(serverId, paired.serverId)
        assertTrue(paired.authToken.startsWith("pn_"))
        System.err.println("[LOOPBACK] 7 valid pair passed")

        // 4. Upload a multi-chunk file (> 256KB) with non-ASCII filename
        val sampleFile = File(context.cacheDir, "loopback_upload_sample.bin")
        val contentBytes = ByteArray(300 * 1024) { idx -> (idx % 251).toByte() }
        sampleFile.writeBytes(contentBytes)
        val sha256 = storageManager.computeSha256(sampleFile)

        var progressCalls = 0
        System.err.println("[LOOPBACK] 8 uploading")
        val uploadResult = kotlinx.coroutines.withTimeout(5000L) {
            apiClient.uploadFileResumable(
                server = paired,
                uploadId = "upload_loop_1",
                sourceFile = sampleFile,
                fileName = "Vacation ✨ café.bin",
                mimeType = "application/octet-stream",
                sha256 = sha256,
                sourceBucket = "Camera ✨",
                takenAtMillis = 1700000000000L,
                onProgress = { _, _ -> progressCalls++ }
            )
        }
        assertTrue("upload failed: ${uploadResult.exceptionOrNull()}", uploadResult.isSuccess)
        val uploadedMediaId = uploadResult.getOrThrow()
        assertTrue(uploadedMediaId.startsWith("med_"))
        assertTrue(progressCalls >= 2)
        System.err.println("[LOOPBACK] 9 upload passed")

        // 5. Catalog, health & deduplication checks
        val health = kotlinx.coroutines.withTimeout(4000L) { apiClient.checkHealth(paired).getOrThrow() }
        assertEquals(1, health.mediaCount)
        assertTrue(health.totalBytes > 0L)
        System.err.println("[LOOPBACK] 10 health passed")

        val catalog = kotlinx.coroutines.withTimeout(4000L) { apiClient.fetchServerCatalog(paired).getOrThrow() }
        assertEquals(1, catalog.size)
        assertEquals("Vacation ✨ café.bin", catalog.first().fileName)

        val dedupId = kotlinx.coroutines.withTimeout(4000L) {
            apiClient.uploadFileResumable(
                server = paired,
                uploadId = "upload_loop_dup",
                sourceFile = sampleFile,
                fileName = "Duplicate.bin",
                mimeType = "application/octet-stream",
                sha256 = sha256,
                sourceBucket = "Camera",
                takenAtMillis = 1700000000000L,
                onProgress = { _, _ -> }
            ).getOrThrow()
        }
        assertEquals(uploadedMediaId, dedupId)
        System.err.println("[LOOPBACK] 11 dedup passed")

        // 6. Verify HTTP 206 byte-range streaming returns exact slice
        val pinnedClient = tlsCertManager.buildPinnedClient(paired.certFingerprint)
        val rangeReq = Request.Builder()
            .url("https://127.0.0.1:${httpServer.boundPort}/api/v1/media/$uploadedMediaId/file")
            .header("Authorization", "Bearer ${paired.authToken}")
            .header("X-Owner-Uid", ownerUid)
            .header("Range", "bytes=100-115")
            .get()
            .build()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pinnedClient.newCall(rangeReq).execute().use { resp ->
                assertEquals(206, resp.code)
                val slice = resp.body?.bytes()
                assertNotNull(slice)
                assertEquals(16, slice!!.size)
                assertTrue(slice.contentEquals(contentBytes.copyOfRange(100, 116)))
            }
        }
        System.err.println("[LOOPBACK] 12 range passed")

        // 7. Favorite toggle & permanent server deletion
        assertTrue(kotlinx.coroutines.withTimeout(4000L) { apiClient.sendMediaAction(paired, uploadedMediaId, "favorite").isSuccess })
        assertTrue(db.mediaDao().getById(uploadedMediaId)?.isFavorite == true)

        assertTrue(kotlinx.coroutines.withTimeout(4000L) { apiClient.deleteServerMedia(paired, uploadedMediaId).isSuccess })
        assertEquals(0, kotlinx.coroutines.withTimeout(4000L) { apiClient.fetchServerCatalog(paired).getOrThrow().size })
        System.err.println("[LOOPBACK] 13 favorite+delete passed")

        // 8. Token revocation blocks subsequent access with 401
        db.authorizedTokenDao().revokeDeviceTokens("dev_pixel", ownerUid)
        val afterRevoke = kotlinx.coroutines.withTimeout(4000L) { apiClient.fetchServerCatalog(paired) }
        assertTrue(afterRevoke.isFailure)
        System.err.println("[LOOPBACK] 14 revoke passed")

        sampleFile.delete()
        Unit
    }
}
