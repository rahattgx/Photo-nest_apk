package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.client.PhotoNestApiClient
import com.example.core.data.MediaItemEntity
import com.example.core.data.PairedServerEntity
import com.example.core.media.SafStorageManager
import com.example.core.media.StorageInspector
import com.example.core.security.TlsCertManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class EditorFilter(val label: String) {
    ORIGINAL("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    BW("B&W"),
    SEPIA("Film")
}

private enum class CropPreset(val label: String, val ratio: Float?) {
    FREE("Original", null),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    items: List<MediaItemEntity>,
    initialMediaId: String,
    activeServer: PairedServerEntity?,
    onBack: () -> Unit,
    onToggleFavorite: (MediaItemEntity) -> Unit,
    onToggleArchive: (MediaItemEntity) -> Unit,
    onMoveToTrash: (MediaItemEntity) -> Unit,
    onDownloadOriginal: (MediaItemEntity) -> Unit,
    onShare: (MediaItemEntity) -> Unit,
    onSaveEditedCopy: (MediaItemEntity, Bitmap) -> Unit
) {
    BackHandler(onBack = onBack)

    val initialIndex = remember(items, initialMediaId) {
        items.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })
    val currentItem = items.getOrNull(pagerState.currentPage)

    var showInfoSheet by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<MediaItemEntity?>(null) }

    if (editingItem != null) {
        PhotoEditorOverlay(
            item = editingItem!!,
            activeServer = activeServer,
            onCancel = { editingItem = null },
            onSave = { bmp ->
                onSaveEditedCopy(editingItem!!, bmp)
                editingItem = null
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (items.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val item = items[page]
                if (item.isVideo) {
                    VideoPlayerPage(
                        item = item,
                        activeServer = activeServer,
                        isCurrentPage = page == pagerState.currentPage
                    )
                } else {
                    ZoomablePhotoPage(item = item, activeServer = activeServer)
                }
            }
        }

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            if (currentItem != null) {
                Text(
                    text = currentItem.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showInfoSheet = true }) {
                    Icon(Icons.Rounded.Info, contentDescription = "Photo details", tint = Color.White)
                }
            }
        }

        // Bottom Action Bar
        if (currentItem != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = { onShare(currentItem) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White)
                }
                IconButton(onClick = { onToggleFavorite(currentItem) }) {
                    Icon(
                        imageVector = if (currentItem.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (currentItem.isFavorite) Color(0xFFFF6B81) else Color.White
                    )
                }
                if (!currentItem.isVideo) {
                    IconButton(onClick = { editingItem = currentItem }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit photo", tint = Color.White)
                    }
                }
                IconButton(onClick = { onDownloadOriginal(currentItem) }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Download original", tint = Color.White)
                }
                IconButton(onClick = { onToggleArchive(currentItem) }) {
                    Icon(Icons.Rounded.Archive, contentDescription = "Archive", tint = Color.White)
                }
                IconButton(onClick = {
                    onMoveToTrash(currentItem)
                    if (items.size <= 1) onBack()
                }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        }
    }

    if (showInfoSheet && currentItem != null) {
        val dateFmt = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()) }
        ModalBottomSheet(onDismissRequest = { showInfoSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Media Information", style = MaterialTheme.typography.titleLarge)
                InfoDetailRow("Filename", currentItem.fileName)
                InfoDetailRow("Taken", dateFmt.format(Date(currentItem.takenAtMillis)))
                InfoDetailRow("Resolution", if (currentItem.width > 0) "${currentItem.width} × ${currentItem.height}" else "Original")
                InfoDetailRow("Size", StorageInspector.formatBytes(currentItem.sizeBytes))
                InfoDetailRow("MIME Type", currentItem.mimeType)
                if (currentItem.cameraModel.isNotBlank()) InfoDetailRow("Camera", currentItem.cameraModel)
                if (currentItem.exifSummary.isNotBlank()) InfoDetailRow("Details", currentItem.exifSummary)
                if (currentItem.locationName.isNotBlank()) InfoDetailRow("Location", currentItem.locationName)
                InfoDetailRow("SHA-256", currentItem.sha256Hash.take(24) + "…")
                InfoDetailRow("Source Bucket", currentItem.sourceBucket)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun InfoDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ZoomablePhotoPage(
    item: MediaItemEntity,
    activeServer: PairedServerEntity?
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }

    val modelData: Any = when {
        item.localFileUri.isNotBlank() && !item.localFileUri.startsWith("http") -> {
            if (item.localFileUri.startsWith("content://")) item.localFileUri else File(item.localFileUri)
        }
        activeServer != null -> "https://${activeServer.hostAddress}:${activeServer.port}/api/v1/media/${item.id}/file"
        else -> item.thumbLocalPath
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount > 1 || zoomChange != 1f || scale > 1.01f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                            scale = newScale
                            if (newScale > 1.01f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = modelData,
            contentDescription = item.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class PinnedOkHttpDataSource(
    private val client: OkHttpClient,
    private val server: PairedServerEntity?
) : BaseDataSource(/* isNetwork = */ true) {

    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        transferInitializing(dataSpec)

        val reqBuilder = Request.Builder().url(dataSpec.uri.toString())
        if (server != null && server.authToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${server.authToken}")
            reqBuilder.header("X-Owner-Uid", server.ownerUid)
        }
        val start = dataSpec.position
        val length = dataSpec.length
        if (start > 0L || length != C.LENGTH_UNSET.toLong()) {
            val rangeHeader = if (length != C.LENGTH_UNSET.toLong()) {
                "bytes=$start-${start + length - 1}"
            } else {
                "bytes=$start-"
            }
            reqBuilder.header("Range", rangeHeader)
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        response = resp
        if (!resp.isSuccessful && resp.code != 206) {
            val code = resp.code
            close()
            throw IOException("HTTP $code while opening video stream")
        }
        val body = resp.body ?: run {
            close()
            throw IOException("Empty video response body")
        }
        responseStream = body.byteStream()
        opened = true
        transferStarted(dataSpec)

        val contentLen = body.contentLength()
        bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLen >= 0L -> contentLen
            else -> C.LENGTH_UNSET.toLong()
        }
        bytesRead = 0L
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        }
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        val maxRead = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesToRead - bytesRead).toInt()
        }
        val read = stream.read(buffer, offset, maxRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        runCatching { responseStream?.close() }
        runCatching { response?.close() }
        responseStream = null
        response = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayerPage(
    item: MediaItemEntity,
    activeServer: PairedServerEntity?,
    isCurrentPage: Boolean
) {
    val context = LocalContext.current
    val videoUri = remember(item.id, activeServer) {
        when {
            item.localFileUri.isNotBlank() && !item.localFileUri.startsWith("http") -> {
                if (item.localFileUri.startsWith("content://")) Uri.parse(item.localFileUri)
                else Uri.fromFile(File(item.localFileUri))
            }
            activeServer != null -> Uri.parse("https://${activeServer.hostAddress}:${activeServer.port}/api/v1/media/${item.id}/file")
            else -> Uri.parse(item.localFileUri)
        }
    }

    val exoPlayer = remember(item.id, activeServer?.serverId, activeServer?.hostAddress, activeServer?.port) {
        val tlsManager = TlsCertManager(context)
        val pinnedClient = tlsManager.buildPinnedClient(activeServer?.certFingerprint.orEmpty())
        val httpFactory = DataSource.Factory { PinnedOkHttpDataSource(pinnedClient, activeServer) }
        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(upstreamFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(videoUri))
                prepare()
            }
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) exoPlayer.pause()
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 56.dp)
        )
        if (item.width > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 56.dp, end = 12.dp)
            ) {
                Text(
                    text = "${item.width}×${item.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PhotoEditorOverlay(
    item: MediaItemEntity,
    activeServer: PairedServerEntity?,
    onCancel: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    var baseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var filter by remember { mutableStateOf(EditorFilter.ORIGINAL) }
    var cropPreset by remember { mutableStateOf(CropPreset.FREE) }

    LaunchedEffect(item.id, activeServer) {
        baseBitmap = withContext(Dispatchers.IO) {
            val localPath = item.localFileUri.takeIf { it.isNotBlank() && !it.startsWith("http") }
                ?: if (activeServer != null && item.localFileUri.startsWith("http")) {
                    val api = PhotoNestApiClient(context, TlsCertManager(context), SafStorageManager(context))
                    api.downloadToTempFile(activeServer, item.id, item.fileName)?.absolutePath
                } else {
                    null
                }
                ?: item.thumbLocalPath
            if (localPath.isBlank()) return@withContext null
            runCatching {
                if (localPath.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(localPath))?.use { BitmapFactory.decodeStream(it) }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(localPath, opts)
                    var sample = 1
                    while (maxOf(opts.outWidth, opts.outHeight) / sample > 1440) sample *= 2
                    BitmapFactory.decodeFile(localPath, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }

    val previewBitmap = remember(baseBitmap, rotationDegrees, brightness, contrast, filter, cropPreset) {
        val src = baseBitmap ?: return@remember null
        applyEditsToBitmap(src, rotationDegrees, brightness, contrast, filter, cropPreset)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Color.White)
            }
            Text("Edit Photo", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Button(
                onClick = { previewBitmap?.let(onSave) },
                enabled = previewBitmap != null
            ) {
                Text("Save Copy")
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Edited photo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF14171F))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f }) {
                    Icon(Icons.Rounded.RotateRight, contentDescription = "Rotate 90 degrees", tint = Color.White)
                }
                CropPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = cropPreset == preset,
                        onClick = { cropPreset = preset },
                        label = { Text(preset.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EditorFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) }
                    )
                }
            }

            Text("Brightness", style = MaterialTheme.typography.labelSmall, color = Color.White)
            Slider(value = brightness, onValueChange = { brightness = it }, valueRange = -80f..80f)

            Text("Contrast", style = MaterialTheme.typography.labelSmall, color = Color.White)
            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..1.8f)
        }
    }
}

private fun applyEditsToBitmap(
    source: Bitmap,
    rotation: Float,
    brightness: Float,
    contrast: Float,
    filter: EditorFilter,
    cropPreset: CropPreset
): Bitmap {
    val matrix = Matrix().apply { postRotate(rotation) }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

    val cropped = cropPreset.ratio?.let { targetRatio ->
        val srcW = rotated.width
        val srcH = rotated.height
        val currentRatio = srcW.toFloat() / srcH.toFloat()
        val (cropW, cropH) = if (currentRatio > targetRatio) {
            (srcH * targetRatio).toInt() to srcH
        } else {
            srcW to (srcW / targetRatio).toInt()
        }
        val x = ((srcW - cropW) / 2).coerceAtLeast(0)
        val y = ((srcH - cropH) / 2).coerceAtLeast(0)
        Bitmap.createBitmap(rotated, x, y, cropW.coerceAtLeast(1), cropH.coerceAtLeast(1))
    } ?: rotated

    val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val cm = ColorMatrix()

    // Contrast & Brightness
    val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
    cm.set(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val filterMatrix = ColorMatrix()
    when (filter) {
        EditorFilter.ORIGINAL -> Unit
        EditorFilter.VIVID -> filterMatrix.setSaturation(1.45f)
        EditorFilter.WARM -> filterMatrix.setScale(1.12f, 1.03f, 0.90f, 1f)
        EditorFilter.COOL -> filterMatrix.setScale(0.92f, 1.02f, 1.15f, 1f)
        EditorFilter.BW -> filterMatrix.setSaturation(0f)
        EditorFilter.SEPIA -> {
            filterMatrix.setSaturation(0f)
            val sepia = ColorMatrix().apply { setScale(1.1f, 0.96f, 0.78f, 1f) }
            filterMatrix.postConcat(sepia)
        }
    }
    cm.postConcat(filterMatrix)

    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    canvas.drawBitmap(cropped, 0f, 0f, paint)
    return out
}
