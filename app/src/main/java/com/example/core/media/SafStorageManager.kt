package com.example.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.example.core.data.MediaItemEntity
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Manages real file creation, directory provisioning, streaming chunked writes,
 * downsampled thumbnail generation, EXIF extraction, and SAF microSD/Internal storage operations.
 */
class SafStorageManager(private val context: Context) {

    data class ExtractedMetadata(
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val takenAtMillis: Long,
        val cameraModel: String,
        val locationName: String,
        val latitude: Double?,
        val longitude: Double?,
        val exifSummary: String
    )

    private val internalVaultRoot: File
        get() = File(context.filesDir, "PhotoNestVault").apply { mkdirs() }

    private val thumbsCacheDir: File
        get() = File(context.filesDir, "PhotoNestThumbs").apply { mkdirs() }

    private val stagingDir: File
        get() = File(context.cacheDir, "photonest_staging").apply { mkdirs() }

    fun ensureServerDirectories(safTreeUri: String): Boolean {
        return try {
            if (safTreeUri.isBlank()) {
                listOf("Originals", "Thumbnails", "Edits", "Trash").forEach {
                    File(internalVaultRoot, it).mkdirs()
                }
                true
            } else {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(safTreeUri)) ?: return false
                val photoNestDir = root.findFile("PhotoNest") ?: root.createDirectory("PhotoNest") ?: return false
                listOf("Originals", "Thumbnails", "Edits", "Trash").forEach { sub ->
                    if (photoNestDir.findFile(sub) == null) {
                        photoNestDir.createDirectory(sub)
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun getStagingPartFile(uploadId: String): File {
        val safeId = uploadId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(stagingDir, "$safeId.part")
    }

    /**
     * Commits a staged upload file into the user's configured PhotoNest Originals (or Edits) directory
     * on either Internal Storage or an external microSD card via SAF, without holding the file in memory.
     */
    fun commitStagedFileToVault(
        stagedFile: File,
        safTreeUri: String,
        subFolder: String,
        fileName: String,
        mimeType: String,
        mediaId: String,
        isVideo: Boolean
    ): Pair<String, String> {
        val safeFileName = fileName.ifBlank { "IMG_${System.currentTimeMillis()}.jpg" }
        val finalUriOrPath: String = if (safTreeUri.isNotBlank()) {
            ensureServerDirectories(safTreeUri)
            val root = DocumentFile.fromTreeUri(context, Uri.parse(safTreeUri))
            val nestDir = root?.findFile("PhotoNest") ?: root?.createDirectory("PhotoNest")
            val targetDir = nestDir?.findFile(subFolder) ?: nestDir?.createDirectory(subFolder)
            val existing = targetDir?.findFile(safeFileName)
            val uniqueName = if (existing == null) safeFileName else "${System.currentTimeMillis()}_$safeFileName"
            val docFile = targetDir?.createFile(mimeType, uniqueName)
            if (docFile != null) {
                val copied = runCatching {
                    val out = context.contentResolver.openOutputStream(docFile.uri)
                        ?: error("Unable to open SAF output stream")
                    out.use { stream ->
                        stagedFile.inputStream().use { input -> input.copyTo(stream, 64 * 1024) }
                    }
                }.isSuccess
                if (copied) {
                    docFile.uri.toString()
                } else {
                    runCatching { docFile.delete() }
                    copyToInternalVault(stagedFile, subFolder, uniqueName)
                }
            } else {
                copyToInternalVault(stagedFile, subFolder, uniqueName)
            }
        } else {
            copyToInternalVault(stagedFile, subFolder, safeFileName)
        }

        val thumbFile = File(thumbsCacheDir, "$mediaId.jpg")
        generateThumbnail(stagedFile, thumbFile, isVideo)

        // Mirror thumbnail into SAF Thumbnails folder if SAF is active
        if (safTreeUri.isNotBlank() && thumbFile.exists()) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(safTreeUri))
                val thumbDir = root?.findFile("PhotoNest")?.findFile("Thumbnails")
                if (thumbDir != null && thumbDir.findFile("$mediaId.jpg") == null) {
                    thumbDir.createFile("image/jpeg", "$mediaId.jpg")?.let { dest ->
                        context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                            thumbFile.inputStream().use { input -> input.copyTo(out) }
                        }
                    }
                }
            }
        }
        return finalUriOrPath to thumbFile.absolutePath
    }

    private fun copyToInternalVault(source: File, subFolder: String, fileName: String): String {
        val dir = File(internalVaultRoot, subFolder).apply { mkdirs() }
        var target = File(dir, fileName)
        if (target.exists()) {
            target = File(dir, "${System.currentTimeMillis()}_$fileName")
        }
        source.inputStream().use { input ->
            FileOutputStream(target).use { out ->
                input.copyTo(out, 64 * 1024)
            }
        }
        return target.absolutePath
    }

    fun extractMetadataFromFile(file: File, isVideo: Boolean, fallbackTakenAt: Long): ExtractedMetadata {
        return if (isVideo) {
            extractVideoMetadata(file, fallbackTakenAt)
        } else {
            extractImageMetadata(file, fallbackTakenAt)
        }
    }

    fun extractMetadataFromUri(uri: Uri, isVideo: Boolean, fallbackTakenAt: Long): ExtractedMetadata {
        return try {
            if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val loc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: ""
                    ExtractedMetadata(
                        width = w,
                        height = h,
                        durationMs = dur,
                        takenAtMillis = fallbackTakenAt,
                        cameraModel = "",
                        locationName = loc,
                        latitude = null,
                        longitude = null,
                        exifSummary = "${w}×${h} • ${dur / 1000}s Video"
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    parseImageExif(input, fallbackTakenAt)
                } ?: ExtractedMetadata(0, 0, 0L, fallbackTakenAt, "", "", null, null, "Image")
            }
        } catch (_: Exception) {
            ExtractedMetadata(0, 0, 0L, fallbackTakenAt, "", "", null, null, if (isVideo) "Video" else "Image")
        }
    }

    private fun extractImageMetadata(file: File, fallbackTakenAt: Long): ExtractedMetadata {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val exif = ExifInterface(file.absolutePath)
            buildExifResult(exif, bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0), fallbackTakenAt)
        } catch (_: Exception) {
            ExtractedMetadata(0, 0, 0L, fallbackTakenAt, "", "", null, null, "Image")
        }
    }

    private fun parseImageExif(input: InputStream, fallbackTakenAt: Long): ExtractedMetadata {
        val exif = ExifInterface(input)
        val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        return buildExifResult(exif, w, h, fallbackTakenAt)
    }

    private fun buildExifResult(
        exif: ExifInterface,
        width: Int,
        height: Int,
        fallbackTakenAt: Long
    ): ExtractedMetadata {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = listOf(make, model).filter { it.isNotBlank() }.distinct().joinToString(" ")
        val fStop = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        val exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val parsedTime = dateStr?.let {
            runCatching {
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time
            }.getOrNull()
        } ?: fallbackTakenAt
        val latLong = exif.latLong
        val locName = if (latLong != null) {
            "%.4f, %.4f".format(Locale.US, latLong[0], latLong[1])
        } else {
            ""
        }
        val summaryParts = buildList {
            if (width > 0 && height > 0) add("${width}×${height}")
            if (camera.isNotBlank()) add(camera)
            if (!fStop.isNullOrBlank()) add("f/$fStop")
            if (!iso.isNullOrBlank()) add("ISO $iso")
            if (!exp.isNullOrBlank()) add("${exp}s")
        }
        return ExtractedMetadata(
            width = width,
            height = height,
            durationMs = 0L,
            takenAtMillis = parsedTime,
            cameraModel = camera,
            locationName = locName,
            latitude = latLong?.getOrNull(0),
            longitude = latLong?.getOrNull(1),
            exifSummary = summaryParts.joinToString(" • ").ifBlank { "JPEG Original" }
        )
    }

    private fun extractVideoMetadata(file: File, fallbackTakenAt: Long): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val loc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) ?: ""
            ExtractedMetadata(
                width = w,
                height = h,
                durationMs = dur,
                takenAtMillis = fallbackTakenAt,
                cameraModel = "",
                locationName = loc,
                latitude = null,
                longitude = null,
                exifSummary = "${w}×${h} • ${dur / 1000}s Video"
            )
        } catch (_: Exception) {
            ExtractedMetadata(0, 0, 0L, fallbackTakenAt, "", "", null, null, "Video")
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun generateThumbnail(sourceFile: File, destThumbFile: File, isVideo: Boolean) {
        try {
            val bitmap: Bitmap? = if (isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFile.absolutePath)
                    retriever.getScaledFrameAtTime(
                        1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        360,
                        360
                    ) ?: retriever.frameAtTime
                } finally {
                    runCatching { retriever.release() }
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
                var sample = 1
                while (maxDim / sample > 480) {
                    sample *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts)
            }
            if (bitmap != null) {
                FileOutputStream(destThumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                bitmap.recycle()
            }
        } catch (_: Exception) {
            // Thumbnail generation failure is non-fatal
        }
    }

    fun openVaultInputStream(localFileUri: String): InputStream? {
        if (localFileUri.isBlank()) return null
        return try {
            if (localFileUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(localFileUri))
            } else {
                val f = File(localFileUri)
                if (f.exists()) f.inputStream() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveStreamToDeviceGallery(
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        isVideo: Boolean
    ): Uri? {
        return try {
            val safeName = fileName.ifBlank {
                if (isVideo) "VID_${System.currentTimeMillis()}.mp4" else "IMG_${System.currentTimeMillis()}.jpg"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isVideo) "${Environment.DIRECTORY_MOVIES}/PhotoNest"
                        else "${Environment.DIRECTORY_PICTURES}/PhotoNest"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                try {
                    val out = resolver.openOutputStream(uri) ?: error("Unable to open MediaStore output stream")
                    out.use { inputStream.copyTo(it, 64 * 1024) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    uri
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                // Android 7-9 (API 24-28): zero-permission scoped app external media directory + MediaScanner
                val baseDir = context.getExternalFilesDir(
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                ) ?: context.filesDir
                val targetDir = File(baseDir, "PhotoNest").apply { mkdirs() }
                var dest = File(targetDir, safeName)
                if (dest.exists()) {
                    dest = File(targetDir, "${System.currentTimeMillis()}_$safeName")
                }
                FileOutputStream(dest).use { out ->
                    inputStream.copyTo(out, 64 * 1024)
                }
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mimeType), null)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteVaultFile(localFileUri: String, thumbLocalPath: String) {
        if (localFileUri.isNotBlank() && !localFileUri.startsWith("http")) {
            runCatching {
                if (localFileUri.startsWith("content://")) {
                    DocumentFile.fromSingleUri(context, Uri.parse(localFileUri))?.delete()
                } else {
                    val f = File(localFileUri)
                    if (f.exists() && f.canonicalPath.startsWith(context.filesDir.canonicalPath)) {
                        f.delete()
                    }
                }
            }
        }
        if (thumbLocalPath.isNotBlank()) {
            runCatching {
                val tf = File(thumbLocalPath)
                if (tf.exists()) tf.delete()
            }
        }
    }

    fun writeBitmapToInternalVault(
        bitmap: Bitmap,
        fileName: String,
        subFolder: String = "Edits",
        quality: Int = 94
    ): Pair<File, File> {
        val dir = File(internalVaultRoot, subFolder).apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        val mediaId = "med_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val thumb = File(thumbsCacheDir, "$mediaId.jpg")
        generateThumbnail(file, thumb, isVideo = false)
        return file to thumb
    }

    fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        file.inputStream().use { input ->
            var read = input.read(buffer)
            while (read > 0) {
                md.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha256(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var read = input.read(buffer)
            while (read > 0) {
                md.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun createLocalMediaEntity(
        serverId: String,
        ownerUid: String,
        file: File,
        thumbFile: File,
        fileName: String,
        mimeType: String,
        sourceBucket: String = "PhotoNest",
        isBackedUpOnServer: Boolean = true
    ): MediaItemEntity {
        val isVideo = mimeType.startsWith("video/")
        val hash = computeSha256(file)
        val meta = extractMetadataFromFile(file, isVideo, System.currentTimeMillis())
        return MediaItemEntity(
            id = "med_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            serverId = serverId,
            ownerUid = ownerUid,
            fileName = fileName,
            mimeType = mimeType,
            isVideo = isVideo,
            sizeBytes = file.length(),
            width = meta.width,
            height = meta.height,
            durationMs = meta.durationMs,
            takenAtMillis = meta.takenAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            sha256Hash = hash,
            relativePath = "Edits/$fileName",
            sourceBucket = sourceBucket,
            isScreenshot = fileName.contains("screenshot", ignoreCase = true) ||
                sourceBucket.contains("screenshot", ignoreCase = true),
            isDocument = mimeType.contains("pdf") || sourceBucket.contains("document", ignoreCase = true),
            locationName = meta.locationName,
            latitude = meta.latitude,
            longitude = meta.longitude,
            cameraModel = meta.cameraModel,
            exifSummary = meta.exifSummary,
            localFileUri = file.absolutePath,
            thumbLocalPath = thumbFile.absolutePath,
            isBackedUpOnServer = isBackedUpOnServer
        )
    }
}
