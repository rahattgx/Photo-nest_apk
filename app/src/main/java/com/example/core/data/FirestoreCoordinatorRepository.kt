package com.example.core.data

import android.content.Context
import android.os.Build
import com.example.R
import com.example.core.model.OperatingMode
import com.example.core.model.PairedDeviceDoc
import com.example.core.model.PhotoServerDoc
import com.example.core.model.ServerMediaMetaDoc
import com.example.core.model.UserProfileDoc
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreCoordinatorRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    suspend fun upsertUserProfile(
        displayName: String,
        email: String,
        photoUrl: String?,
        preferredMode: OperatingMode
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "displayName" to displayName.ifBlank { "PhotoNest User" }.take(120),
                    "preferredMode" to preferredMode.wireValue,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (email.isNotBlank()) updates["email"] = email.take(200)
                if (!photoUrl.isNullOrBlank()) updates["photoUrl"] = photoUrl.take(500)
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to displayName.ifBlank { "PhotoNest User" }.take(120),
                    "preferredMode" to preferredMode.wireValue,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (email.isNotBlank()) payload["email"] = email.take(200)
                if (!photoUrl.isNullOrBlank()) payload["photoUrl"] = photoUrl.take(500)
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun registerOrHeartbeatServer(
        serverId: String,
        serverName: String,
        hostAddress: String,
        port: Int,
        certFingerprint: String,
        pairingCodeHash: String,
        isOnline: Boolean,
        storageLabel: String,
        totalBytes: Long,
        usedBytes: Long,
        availableBytes: Long,
        relayMode: String
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("servers").document(serverId)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updates = mapOf(
                    "serverName" to serverName.ifBlank { "PhotoNest Server" }.take(100),
                    "hostAddress" to hostAddress.ifBlank { "127.0.0.1" }.take(255),
                    "port" to port.toLong(),
                    "certFingerprint" to certFingerprint.take(128),
                    "pairingCodeHash" to pairingCodeHash.take(128),
                    "isOnline" to isOnline,
                    "storageLabel" to storageLabel.ifBlank { "Internal" }.take(120),
                    "totalBytes" to totalBytes.coerceAtLeast(0L),
                    "usedBytes" to usedBytes.coerceAtLeast(0L),
                    "availableBytes" to availableBytes.coerceAtLeast(0L),
                    "relayMode" to relayMode,
                    "lastHeartbeat" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                val payload = mapOf(
                    "userId" to uid,
                    "serverName" to serverName.ifBlank { "PhotoNest Server" }.take(100),
                    "hostAddress" to hostAddress.ifBlank { "127.0.0.1" }.take(255),
                    "port" to port.toLong(),
                    "certFingerprint" to certFingerprint.take(128),
                    "pairingCodeHash" to pairingCodeHash.take(128),
                    "isOnline" to isOnline,
                    "storageLabel" to storageLabel.ifBlank { "Internal" }.take(120),
                    "totalBytes" to totalBytes.coerceAtLeast(0L),
                    "usedBytes" to usedBytes.coerceAtLeast(0L),
                    "availableBytes" to availableBytes.coerceAtLeast(0L),
                    "relayMode" to relayMode,
                    "lastHeartbeat" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserServers(userId: String): Flow<List<PhotoServerDoc>> = flow {
        val path = "users/$userId/servers"
        emitAll(
            db.collection("users").document(userId).collection("servers")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PhotoServerDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserServersOnce(userId: String): List<PhotoServerDoc> {
        val path = "users/$userId/servers"
        return try {
            db.collection("users").document(userId).collection("servers")
                .get()
                .await()
                .toObjects(
                    PhotoServerDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (auth.currentUser?.uid == userId) {
                handleFirestoreError(e, OperationType.LIST, path)
            }
            emptyList()
        }
    }

    suspend fun registerPairedDevice(
        deviceId: String,
        serverId: String,
        deviceName: String = Build.MODEL,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("devices").document(deviceId)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                docRef.update(
                    mapOf(
                        "deviceName" to deviceName.ifBlank { "Android Phone" }.take(100),
                        "status" to "active",
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "serverId" to serverId,
                        "deviceName" to deviceName.ifBlank { "Android Phone" }.take(100),
                        "deviceModel" to deviceModel.ifBlank { "Android" }.take(100),
                        "status" to "active",
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun revokeDevice(deviceId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("devices").document(deviceId)
        return try {
            docRef.update(
                mapOf(
                    "status" to "revoked",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observePairedDevices(userId: String): Flow<List<PairedDeviceDoc>> = flow {
        val path = "users/$userId/devices"
        emitAll(
            db.collection("users").document(userId).collection("devices")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        PairedDeviceDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun upsertServerMediaMeta(
        serverId: String,
        media: MediaItemEntity
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("servers").document(serverId)
            .collection("catalog").document(media.id)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "fileName" to media.fileName.take(255),
                    "isFavorite" to media.isFavorite,
                    "isArchived" to media.isArchived,
                    "isTrashed" to media.isTrashed,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (media.locationName.isNotBlank()) {
                    updates["locationName"] = media.locationName.take(160)
                }
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "serverId" to serverId,
                    "fileName" to media.fileName.ifBlank { "photo.jpg" }.take(255),
                    "mimeType" to media.mimeType.ifBlank { "image/jpeg" }.take(120),
                    "isVideo" to media.isVideo,
                    "sizeBytes" to media.sizeBytes.coerceAtLeast(0L),
                    "width" to media.width.toLong().coerceAtLeast(0L),
                    "height" to media.height.toLong().coerceAtLeast(0L),
                    "durationMs" to media.durationMs.coerceAtLeast(0L),
                    "takenAtMillis" to media.takenAtMillis.coerceAtLeast(0L),
                    "sha256Hash" to media.sha256Hash.ifBlank { "unhashed_item" }.take(128),
                    "sourceBucket" to media.sourceBucket.ifBlank { "Camera" }.take(120),
                    "isFavorite" to media.isFavorite,
                    "isArchived" to media.isArchived,
                    "isTrashed" to media.isTrashed,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (media.locationName.isNotBlank()) {
                    payload["locationName"] = media.locationName.take(160)
                }
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteServerMediaMeta(
        serverId: String,
        mediaId: String
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
            .collection("servers").document(serverId)
            .collection("catalog").document(mediaId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeServerCatalog(userId: String, serverId: String): Flow<List<ServerMediaMetaDoc>> = flow {
        val path = "users/$userId/servers/$serverId/catalog"
        emitAll(
            db.collection("users").document(userId)
                .collection("servers").document(serverId)
                .collection("catalog")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ServerMediaMetaDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserProfile(userId: String): UserProfileDoc? {
        val docRef = db.collection("users").document(userId)
        return try {
            docRef.get().await().toObject(
                UserProfileDoc::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            null
        }
    }
}
