package com.example.core.data

import com.example.base.FirestoreEmulatorTestBase
import com.example.core.model.OperatingMode
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FirestoreCoordinatorRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun upsertUserProfile_and_registerServer_succeedsForOwner() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = FirestoreCoordinatorRepository(firestore, auth)

        val profileRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertUserProfile(
                displayName = "Alice Tester",
                email = ALICE_EMAIL,
                photoUrl = null,
                preferredMode = OperatingMode.SERVER
            )
        }
        assertTrue(profileRes.isSuccess)

        val serverId = "srv_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val serverRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.registerOrHeartbeatServer(
                serverId = serverId,
                serverName = "Pixel 5 Vault",
                hostAddress = "192.168.1.55",
                port = 8443,
                certFingerprint = "A1B2C3D4E5F60718293A4B5C6D7E8F90",
                pairingCodeHash = "pairhash12345",
                isOnline = true,
                storageLabel = "SanDisk SD Card",
                totalBytes = 128_000_000_000L,
                usedBytes = 12_000_000_000L,
                availableBytes = 116_000_000_000L,
                relayMode = "lan"
            )
        }
        assertTrue(serverRes.isSuccess)

        val servers = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserServers(uid).first { list -> list.any { it.id == serverId } }
        }
        assertEquals("Pixel 5 Vault", servers.first { it.id == serverId }.serverName)
    }

    @Test
    fun registerDevice_and_upsertCatalog_succeedsForOwner() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = FirestoreCoordinatorRepository(firestore, auth)
        val serverId = "srv_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val deviceId = "dev_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val mediaId = "med_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val deviceRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.registerPairedDevice(
                deviceId = deviceId,
                serverId = serverId,
                deviceName = "Pixel 9",
                deviceModel = "Google Pixel 9"
            )
        }
        assertTrue(deviceRes.isSuccess)

        val mediaRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.upsertServerMediaMeta(
                serverId = serverId,
                media = MediaItemEntity(
                    id = mediaId,
                    serverId = serverId,
                    ownerUid = uid,
                    fileName = "IMG_20261114_120000.jpg",
                    mimeType = "image/jpeg",
                    isVideo = false,
                    sizeBytes = 2_450_000L,
                    width = 4032,
                    height = 3024,
                    durationMs = 0L,
                    takenAtMillis = 1763121600000L,
                    createdAtMillis = 1763121600000L,
                    sha256Hash = "abcd1234efgh5678",
                    relativePath = "Originals/IMG_20261114_120000.jpg",
                    sourceBucket = "Camera",
                    isBackedUpOnServer = true
                )
            )
        }
        assertTrue(mediaRes.isSuccess)

        val catalog = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeServerCatalog(uid, serverId).first { list -> list.any { it.id == mediaId } }
        }
        assertEquals("IMG_20261114_120000.jpg", catalog.first { it.id == mediaId }.fileName)
    }

    @Test
    fun observeUserServers_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = FirestoreCoordinatorRepository(firestore, auth)
        val serverId = "srv_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.registerOrHeartbeatServer(
                serverId = serverId,
                serverName = "Alice Vault",
                hostAddress = "192.168.1.99",
                port = 8443,
                certFingerprint = "A1B2C3D4E5F60718293A4B5C6D7E8F90",
                pairingCodeHash = "pairhash12345",
                isOnline = true,
                storageLabel = "Internal",
                totalBytes = 64_000_000_000L,
                usedBytes = 2_000_000_000L,
                availableBytes = 62_000_000_000L,
                relayMode = "lan"
            )
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = FirestoreCoordinatorRepository(firestore, auth)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeUserServers(aliceUid).first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val firestoreException = caught?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException, got $caught", firestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }

    @Test
    fun observeUserServers_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = FirestoreCoordinatorRepository(firestore, auth)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeUserServers("some_uid_123").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val firestoreException = caught?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException, got $caught", firestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_photonest@test.com"
        const val BOB_EMAIL = "bob_photonest@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3500L
    }
}
