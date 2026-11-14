package com.example.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.core.model.BackupStatus
import com.example.core.model.PersonCategory
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val ownerUid: String,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val takenAtMillis: Long,
    val createdAtMillis: Long,
    val sha256Hash: String,
    val relativePath: String,
    val sourceBucket: String,
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAtMillis: Long? = null,
    val isScreenshot: Boolean = false,
    val isDocument: Boolean = false,
    val locationName: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cameraModel: String = "",
    val exifSummary: String = "",
    val aiTags: String = "",
    val localFileUri: String = "",
    val thumbLocalPath: String = "",
    val isBackedUpOnServer: Boolean = false
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val ownerUid: String = "",
    val name: String,
    val coverMediaId: String? = null,
    val createdAtMillis: Long,
    val isSmartAlbum: Boolean = false
)

@Entity(tableName = "album_media", primaryKeys = ["albumId", "mediaId"])
data class AlbumMediaCrossRef(
    val albumId: String,
    val mediaId: String,
    val addedAtMillis: Long
)

@Entity(tableName = "person_clusters")
data class PersonClusterEntity(
    @PrimaryKey val id: String,
    val ownerUid: String = "",
    val name: String,
    val category: String = PersonCategory.PEOPLE.name,
    val avatarPath: String = "",
    val sampleMediaId: String = "",
    val faceCount: Int = 0,
    val isHidden: Boolean = false,
    val createdAtMillis: Long
)

@Entity(tableName = "media_face_tags", primaryKeys = ["mediaId", "personId"])
data class MediaFaceTagEntity(
    val mediaId: String,
    val personId: String,
    val confidence: Float,
    val boundingBoxJson: String = ""
)

@Entity(tableName = "backup_queue")
data class BackupJobEntity(
    @PrimaryKey val id: String,
    val ownerUid: String = "",
    val localUri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256Hash: String,
    val sourceBucket: String,
    val takenAtMillis: Long,
    val status: String = BackupStatus.PENDING.name,
    val uploadedBytes: Long = 0L,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "paired_servers")
data class PairedServerEntity(
    @PrimaryKey val serverId: String,
    val ownerUid: String,
    val serverName: String,
    val hostAddress: String,
    val port: Int,
    val certFingerprint: String,
    val authToken: String,
    val lastSeenMillis: Long,
    val storageLabel: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val isOnline: Boolean
)

@Entity(tableName = "authorized_tokens")
data class AuthorizedTokenEntity(
    @PrimaryKey val token: String,
    val deviceId: String,
    val ownerUid: String,
    val deviceName: String,
    val isRevoked: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isTrashed = 0 AND isArchived = 0 ORDER BY takenAtMillis DESC")
    fun observeActiveMedia(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeAllNonTrashed(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isFavorite = 1 AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeFavorites(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isVideo = 1 AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeVideos(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isScreenshot = 1 AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeScreenshots(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isDocument = 1 AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeDocuments(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isArchived = 1 AND isTrashed = 0 ORDER BY takenAtMillis DESC")
    fun observeArchived(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isTrashed = 1 ORDER BY trashedAtMillis DESC")
    fun observeTrashed(ownerUid: String): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND isTrashed = 0 AND isBackedUpOnServer = 1 ORDER BY takenAtMillis DESC")
    suspend fun getBackedUpMediaByOwnerSync(ownerUid: String): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE isTrashed = 0 AND isBackedUpOnServer = 1 ORDER BY takenAtMillis DESC")
    suspend fun getBackedUpMediaSync(): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE ownerUid = :ownerUid AND sha256Hash = :hash LIMIT 1")
    suspend fun getByHash(ownerUid: String, hash: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE sha256Hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("UPDATE media_items SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE media_items SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE media_items SET isTrashed = :trashed, trashedAtMillis = :trashedAt WHERE id = :id")
    suspend fun setTrashed(id: String, trashed: Boolean, trashedAt: Long?)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("UPDATE media_items SET aiTags = :tags WHERE id = :id")
    suspend fun updateAiTags(id: String, tags: String)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums WHERE ownerUid = :ownerUid OR ownerUid = '' ORDER BY createdAtMillis DESC")
    fun observeAlbums(ownerUid: String): Flow<List<AlbumEntity>>

    @Query("SELECT mediaId FROM album_media WHERE albumId = :albumId ORDER BY addedAtMillis DESC")
    fun observeAlbumMediaIds(albumId: String): Flow<List<String>>

    @Query("SELECT * FROM album_media")
    fun observeAllCrossRefs(): Flow<List<AlbumMediaCrossRef>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMediaToAlbum(crossRef: AlbumMediaCrossRef)

    @Query("DELETE FROM album_media WHERE albumId = :albumId AND mediaId = :mediaId")
    suspend fun removeMediaFromAlbum(albumId: String, mediaId: String)

    @Query("DELETE FROM album_media WHERE albumId = :albumId")
    suspend fun clearAlbumMedia(albumId: String)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbum(albumId: String)
}

@Dao
interface PersonDao {
    @Query("SELECT * FROM person_clusters WHERE (ownerUid = :ownerUid OR ownerUid = '') AND isHidden = 0 ORDER BY faceCount DESC")
    fun observePeople(ownerUid: String): Flow<List<PersonClusterEntity>>

    @Query("SELECT * FROM media_face_tags")
    fun observeAllFaceTags(): Flow<List<MediaFaceTagEntity>>

    @Query("SELECT mediaId FROM media_face_tags WHERE personId = :personId")
    fun observeMediaIdsForPerson(personId: String): Flow<List<String>>

    @Query("SELECT * FROM person_clusters WHERE id = :personId LIMIT 1")
    suspend fun getPersonById(personId: String): PersonClusterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(person: PersonClusterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaceTag(tag: MediaFaceTagEntity)

    @Query("UPDATE person_clusters SET name = :name WHERE id = :personId")
    suspend fun renamePerson(personId: String, name: String)

    @Query("UPDATE person_clusters SET category = :category WHERE id = :personId")
    suspend fun updateCategory(personId: String, category: String)
}

@Dao
interface BackupQueueDao {
    @Query("SELECT * FROM backup_queue WHERE ownerUid = :ownerUid OR ownerUid = '' ORDER BY updatedAtMillis DESC")
    fun observeQueue(ownerUid: String): Flow<List<BackupJobEntity>>

    @Query("SELECT * FROM backup_queue WHERE (ownerUid = :ownerUid OR ownerUid = '') AND status IN ('PENDING', 'FAILED', 'PAUSED') ORDER BY takenAtMillis DESC")
    suspend fun getPendingOrFailedSync(ownerUid: String): List<BackupJobEntity>

    @Query("SELECT * FROM backup_queue WHERE status IN ('PENDING', 'FAILED', 'PAUSED') ORDER BY takenAtMillis DESC")
    suspend fun getPendingOrFailedSync(): List<BackupJobEntity>

    @Query("SELECT * FROM backup_queue WHERE sha256Hash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BackupJobEntity?

    @Query("SELECT * FROM backup_queue WHERE localUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): BackupJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: BackupJobEntity)

    @Query("UPDATE backup_queue SET status = :status, errorMessage = :error, updatedAtMillis = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE backup_queue SET status = 'PENDING', errorMessage = null, updatedAtMillis = :now WHERE status = 'FAILED' AND (ownerUid = :ownerUid OR ownerUid = '')")
    suspend fun retryAllFailed(ownerUid: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM backup_queue WHERE status = 'COMPLETED' AND (ownerUid = :ownerUid OR ownerUid = '')")
    suspend fun clearCompleted(ownerUid: String)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM paired_servers WHERE ownerUid = :ownerUid ORDER BY lastSeenMillis DESC")
    fun observePairedServers(ownerUid: String): Flow<List<PairedServerEntity>>

    @Query("SELECT * FROM paired_servers WHERE ownerUid = :ownerUid LIMIT 1")
    suspend fun getPrimaryServerSync(ownerUid: String): PairedServerEntity?

    @Query("SELECT * FROM paired_servers LIMIT 1")
    suspend fun getPrimaryServerSync(): PairedServerEntity?

    @Query("SELECT * FROM paired_servers WHERE serverId = :serverId LIMIT 1")
    suspend fun getById(serverId: String): PairedServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: PairedServerEntity)

    @Query("DELETE FROM paired_servers WHERE serverId = :serverId")
    suspend fun deleteServer(serverId: String)
}

@Dao
interface AuthorizedTokenDao {
    @Query("SELECT * FROM authorized_tokens WHERE token = :token AND ownerUid = :ownerUid AND isRevoked = 0 LIMIT 1")
    suspend fun findValidToken(token: String, ownerUid: String): AuthorizedTokenEntity?

    @Query("SELECT COUNT(*) FROM authorized_tokens WHERE ownerUid = :ownerUid AND isRevoked = 0")
    suspend fun countActiveTokens(ownerUid: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AuthorizedTokenEntity)

    @Query("UPDATE authorized_tokens SET isRevoked = 1 WHERE deviceId = :deviceId AND ownerUid = :ownerUid")
    suspend fun revokeDeviceTokens(deviceId: String, ownerUid: String)

    @Query("UPDATE authorized_tokens SET isRevoked = 0 WHERE deviceId = :deviceId AND ownerUid = :ownerUid")
    suspend fun activateDeviceTokens(deviceId: String, ownerUid: String)
}

@Database(
    entities = [
        MediaItemEntity::class,
        AlbumEntity::class,
        AlbumMediaCrossRef::class,
        PersonClusterEntity::class,
        MediaFaceTagEntity::class,
        BackupJobEntity::class,
        PairedServerEntity::class,
        AuthorizedTokenEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PhotoNestDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun albumDao(): AlbumDao
    abstract fun personDao(): PersonDao
    abstract fun backupQueueDao(): BackupQueueDao
    abstract fun serverDao(): ServerDao
    abstract fun authorizedTokenDao(): AuthorizedTokenDao

    companion object {
        @Volatile
        private var INSTANCE: PhotoNestDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE albums ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE person_clusters ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE backup_queue ADD COLUMN ownerUid TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `authorized_tokens` (
                        `token` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `isRevoked` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`token`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): PhotoNestDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoNestDatabase::class.java,
                    "photonest_vault.db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
