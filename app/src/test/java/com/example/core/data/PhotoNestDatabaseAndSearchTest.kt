package com.example.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.media.OnDeviceSearchEngine
import com.example.core.media.StorageInspector
import com.example.core.model.BackupStatus
import com.example.core.model.PairingPayload
import com.example.core.model.PersonCategory
import com.example.core.security.PairingQrCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PhotoNestDatabaseAndSearchTest {

    private lateinit var db: PhotoNestDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhotoNestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `authorizedTokenDao persists and enforces revocation`() = runBlocking {
        val dao = db.authorizedTokenDao()
        dao.upsert(
            AuthorizedTokenEntity(
                token = "pn_token_123",
                deviceId = "dev_a",
                ownerUid = "uid_1",
                deviceName = "Pixel 9"
            )
        )
        assertNotNull(dao.findValidToken("pn_token_123", "uid_1"))
        assertNull(dao.findValidToken("pn_token_123", "uid_2"))
        assertEquals(1, dao.countActiveTokens("uid_1"))

        dao.revokeDeviceTokens("dev_a", "uid_1")
        assertNull(dao.findValidToken("pn_token_123", "uid_1"))
        assertEquals(0, dao.countActiveTokens("uid_1"))

        dao.activateDeviceTokens("dev_a", "uid_1")
        assertNotNull(dao.findValidToken("pn_token_123", "uid_1"))
        assertEquals(1, dao.countActiveTokens("uid_1"))
    }

    @Test
    fun `multi-account ownerUid isolation across media albums and queue`() = runBlocking {
        val m1 = MediaItemEntity(
            id = "m1", serverId = "srv1", ownerUid = "userA",
            fileName = "alpha.jpg", mimeType = "image/jpeg", isVideo = false,
            sizeBytes = 100L, width = 10, height = 10, durationMs = 0L,
            takenAtMillis = 1000L, createdAtMillis = 1000L, sha256Hash = "hashA",
            relativePath = "Originals/alpha.jpg", sourceBucket = "Camera",
            isBackedUpOnServer = true
        )
        val m2 = m1.copy(id = "m2", ownerUid = "userB", fileName = "beta.jpg", sha256Hash = "hashB")
        db.mediaDao().upsertAll(listOf(m1, m2))

        val userAMedia = db.mediaDao().observeActiveMedia("userA").first()
        val userBMedia = db.mediaDao().observeActiveMedia("userB").first()
        assertEquals(listOf("m1"), userAMedia.map { it.id })
        assertEquals(listOf("m2"), userBMedia.map { it.id })

        db.albumDao().upsertAlbum(AlbumEntity(id = "a1", ownerUid = "userA", name = "Trips", createdAtMillis = 10L))
        db.albumDao().upsertAlbum(AlbumEntity(id = "a2", ownerUid = "userB", name = "Work", createdAtMillis = 10L))
        assertEquals(listOf("a1"), db.albumDao().observeAlbums("userA").first().map { it.id })

        db.backupQueueDao().upsert(
            BackupJobEntity(
                id = "j1", ownerUid = "userA", localUri = "u1", fileName = "f1.jpg",
                mimeType = "image/jpeg", sizeBytes = 10L, sha256Hash = "h1",
                sourceBucket = "Camera", takenAtMillis = 10L, status = BackupStatus.FAILED.name
            )
        )
        db.backupQueueDao().retryAllFailed("userB")
        assertEquals(BackupStatus.FAILED.name, db.backupQueueDao().observeQueue("userA").first().single().status)
        db.backupQueueDao().retryAllFailed("userA")
        assertEquals(BackupStatus.PENDING.name, db.backupQueueDao().observeQueue("userA").first().single().status)
    }

    @Test
    fun `pairingQrCodec round-trips payload and rejects malformed input`() {
        val payload = PairingPayload(
            serverId = "srv_xyz",
            ownerUid = "uid_owner",
            serverName = "Home Vault",
            hostAddress = "192.168.1.50",
            port = 8443,
            certFingerprint = "a".repeat(64),
            pairingSecret = "sec_123456",
            expiresAtMillis = 9999999999999L
        )
        val encoded = PairingQrCodec.encodeToString(payload)
        val decoded = PairingQrCodec.decodeFromString(encoded)
        assertEquals(payload, decoded)
        assertNull(PairingQrCodec.decodeFromString("not-json"))
    }

    @Test
    fun `onDeviceSearchEngine filters by filename people albums and semantic queries`() {
        val photo = MediaItemEntity(
            id = "p1", serverId = "s", ownerUid = "u",
            fileName = "IMG_Yosemite_Sunset.jpg", mimeType = "image/jpeg", isVideo = false,
            sizeBytes = 100L, width = 100, height = 100, durationMs = 0L,
            takenAtMillis = 1700000000000L, createdAtMillis = 1700000000000L,
            sha256Hash = "h1", relativePath = "Originals/1", sourceBucket = "Camera",
            locationName = "Yosemite Valley", cameraModel = "Pixel 9"
        )
        val video = photo.copy(
            id = "p2", fileName = "Birthday_Clip.mp4", mimeType = "video/mp4",
            isVideo = true, locationName = "", cameraModel = "", isFavorite = true
        )
        val items = listOf(photo, video)

        assertEquals(
            listOf("p1"),
            OnDeviceSearchEngine.filterMedia(
                items = items,
                query = "Alice",
                filterType = OnDeviceSearchEngine.SearchFilterType.ALL,
                peopleNamesByMediaId = mapOf("p1" to listOf("Alice")),
                albumNamesByMediaId = emptyMap(),
                aiSemanticEnabled = false
            ).map { it.id }
        )

        assertEquals(
            listOf("p2"),
            OnDeviceSearchEngine.filterMedia(
                items = items,
                query = "movie",
                filterType = OnDeviceSearchEngine.SearchFilterType.ALL,
                peopleNamesByMediaId = emptyMap(),
                albumNamesByMediaId = emptyMap(),
                aiSemanticEnabled = true
            ).map { it.id }
        )

        assertEquals("1.5 KB", StorageInspector.formatBytes(1536L))
        assertEquals("People", PersonCategory.PEOPLE.label)
    }
}
