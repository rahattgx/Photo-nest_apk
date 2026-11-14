package com.example.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.example.core.data.MediaFaceTagEntity
import com.example.core.data.MediaItemEntity
import com.example.core.data.PersonClusterEntity
import com.example.core.data.PersonDao
import com.example.core.model.PersonCategory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Strictly on-device People & Pets face indexing (using ML Kit Face Detection)
 * and on-device semantic search matching. Facial data never leaves the device or local server.
 */
class OnDeviceFaceIndexer(
    private val context: Context,
    private val personDao: PersonDao
) {
    private val faceAvatarDir: File
        get() = File(context.filesDir, "PhotoNestFaces").apply { mkdirs() }

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(opts)
    }

    suspend fun indexMediaItem(item: MediaItemEntity): Int {
        if (item.isVideo) return 0
        val sourceBitmap = loadSampledBitmap(item) ?: return 0
        return try {
            val image = InputImage.fromBitmap(sourceBitmap, 0)
            val faces = detector.process(image).await()
            if (faces.isEmpty()) return 0

            var taggedCount = 0
            faces.forEachIndexed { index, face ->
                val box = clampRect(face.boundingBox, sourceBitmap.width, sourceBitmap.height)
                if (box.width() > 24 && box.height() > 24) {
                    val personId = "person_${item.id.take(8)}_$index"
                    val avatarFile = File(faceAvatarDir, "$personId.jpg")
                    if (!avatarFile.exists()) {
                        val crop = Bitmap.createBitmap(sourceBitmap, box.left, box.top, box.width(), box.height())
                        FileOutputStream(avatarFile).use { out ->
                            crop.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        crop.recycle()
                    }
                    val existing = personDao.getPersonById(personId)
                    if (existing == null) {
                        personDao.upsertPerson(
                            PersonClusterEntity(
                                id = personId,
                                ownerUid = item.ownerUid,
                                name = "Add name",
                                category = PersonCategory.PEOPLE.name,
                                avatarPath = avatarFile.absolutePath,
                                sampleMediaId = item.id,
                                faceCount = 1,
                                createdAtMillis = System.currentTimeMillis()
                            )
                        )
                    } else {
                        personDao.upsertPerson(existing.copy(faceCount = existing.faceCount + 1))
                    }
                    personDao.upsertFaceTag(
                        MediaFaceTagEntity(
                            mediaId = item.id,
                            personId = personId,
                            confidence = 0.92f,
                            boundingBoxJson = "${box.left},${box.top},${box.right},${box.bottom}"
                        )
                    )
                    taggedCount++
                }
            }
            taggedCount
        } catch (_: Exception) {
            0
        } finally {
            sourceBitmap.recycle()
        }
    }

    private fun loadSampledBitmap(item: MediaItemEntity): Bitmap? {
        val path = item.thumbLocalPath.ifBlank { item.localFileUri }
        if (path.isBlank()) return null
        return try {
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / sample > 800) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clampRect(rect: Rect, width: Int, height: Int): Rect {
        val padX = (rect.width() * 0.15f).toInt()
        val padY = (rect.height() * 0.15f).toInt()
        return Rect(
            (rect.left - padX).coerceIn(0, width - 1),
            (rect.top - padY).coerceIn(0, height - 1),
            (rect.right + padX).coerceIn(1, width),
            (rect.bottom + padY).coerceIn(1, height)
        )
    }

    suspend fun createManualPersonOrPetCluster(
        name: String,
        category: PersonCategory,
        sampleMedia: MediaItemEntity
    ): PersonClusterEntity {
        val personId = "person_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val cluster = PersonClusterEntity(
            id = personId,
            ownerUid = sampleMedia.ownerUid,
            name = name.trim().ifBlank { if (category == PersonCategory.PETS) "My Pet" else "Person" },
            category = category.name,
            avatarPath = sampleMedia.thumbLocalPath.ifBlank { sampleMedia.localFileUri },
            sampleMediaId = sampleMedia.id,
            faceCount = 1,
            createdAtMillis = System.currentTimeMillis()
        )
        personDao.upsertPerson(cluster)
        personDao.upsertFaceTag(
            MediaFaceTagEntity(
                mediaId = sampleMedia.id,
                personId = personId,
                confidence = 1.0f
            )
        )
        return cluster
    }
}

object OnDeviceSearchEngine {

    enum class SearchFilterType {
        ALL, PHOTOS, VIDEOS, SCREENSHOTS, DOCUMENTS, FAVORITES
    }

    fun filterMedia(
        items: List<MediaItemEntity>,
        query: String,
        filterType: SearchFilterType,
        peopleNamesByMediaId: Map<String, List<String>>,
        albumNamesByMediaId: Map<String, List<String>>,
        aiSemanticEnabled: Boolean
    ): List<MediaItemEntity> {
        val q = query.trim().lowercase(Locale.US)
        val monthFormatter = SimpleDateFormat("MMMM yyyy MMM", Locale.US)
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        return items.filter { item ->
            val typeMatch = when (filterType) {
                SearchFilterType.ALL -> true
                SearchFilterType.PHOTOS -> !item.isVideo && !item.isScreenshot && !item.isDocument
                SearchFilterType.VIDEOS -> item.isVideo
                SearchFilterType.SCREENSHOTS -> item.isScreenshot
                SearchFilterType.DOCUMENTS -> item.isDocument
                SearchFilterType.FAVORITES -> item.isFavorite
            }
            if (!typeMatch) return@filter false
            if (q.isBlank()) return@filter true

            val dateText = monthFormatter.format(Date(item.takenAtMillis)).lowercase(Locale.US) +
                " " + dayFormatter.format(Date(item.takenAtMillis))
            val people = peopleNamesByMediaId[item.id].orEmpty().joinToString(" ").lowercase(Locale.US)
            val albums = albumNamesByMediaId[item.id].orEmpty().joinToString(" ").lowercase(Locale.US)

            val directMatch = item.fileName.lowercase(Locale.US).contains(q) ||
                item.sourceBucket.lowercase(Locale.US).contains(q) ||
                item.locationName.lowercase(Locale.US).contains(q) ||
                item.cameraModel.lowercase(Locale.US).contains(q) ||
                item.exifSummary.lowercase(Locale.US).contains(q) ||
                item.aiTags.lowercase(Locale.US).contains(q) ||
                dateText.contains(q) ||
                people.contains(q) ||
                albums.contains(q)

            if (directMatch) return@filter true

            if (aiSemanticEnabled) {
                val semanticMatch = when {
                    (q.contains("video") || q.contains("movie") || q.contains("clip")) && item.isVideo -> true
                    (q.contains("screen") || q.contains("shot")) && item.isScreenshot -> true
                    (q.contains("doc") || q.contains("scan") || q.contains("receipt")) && item.isDocument -> true
                    (q.contains("fav") || q.contains("best") || q.contains("liked")) && item.isFavorite -> true
                    (q.contains("selfie") || q.contains("portrait") || q.contains("face")) && people.isNotBlank() -> true
                    (q.contains("camera") || q.contains("photo") || q.contains("dslr")) && item.cameraModel.isNotBlank() -> true
                    (q.contains("trip") || q.contains("travel") || q.contains("map")) && item.locationName.isNotBlank() -> true
                    else -> false
                }
                if (semanticMatch) return@filter true
            }
            false
        }
    }
}
