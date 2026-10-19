package org.codexmobile.bridge

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePreviewScreen(preview: FilePreview) {
    val kind = previewKind(preview)
    if (kind == PreviewKind.HTML) {
        HtmlBrowserScreen(preview)
        return
    }
    val context = LocalContext.current
    val file = remember(preview.localPath) { File(preview.localPath) }
    val openExternal: () -> Unit = {
        if (!openPreviewWithAnotherApp(context, preview)) {
            Toast.makeText(context, "手机上没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(onBack = BridgeClient::closeFilePreview)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(preview.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(formatBytes(preview.size), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = BridgeClient::closeFilePreview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回会话") } },
                actions = {
                    IconButton(onClick = openExternal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, "使用其他应用打开")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                kind == PreviewKind.PDF -> PdfPreview(file)
                kind == PreviewKind.SVG -> SvgPreview(file)
                kind == PreviewKind.IMAGE -> ImagePreview(file)
                kind == PreviewKind.MEDIA -> MediaPreview(preview)
                kind == PreviewKind.TEXT -> TextPreview(file, preview.line)
                else -> UnsupportedPreview(preview, openExternal)
            }
        }
    }
}

@Composable
private fun ImagePreview(file: File) {
    val imageState by produceState<ImageLoadState>(ImageLoadState.Loading, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    val largest = maxOf(info.size.width, info.size.height)
                    if (largest > 3200) decoder.setTargetSampleSize((largest / 2400).coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.fold(
                onSuccess = { ImageLoadState.Ready(it) },
                onFailure = { ImageLoadState.Failed(it.message ?: "图片解码失败") },
            )
        }
    }
    when (val state = imageState) {
        ImageLoadState.Loading -> LoadingPreview()
        is ImageLoadState.Failed -> PreviewFailure("无法预览这张图片：${state.message}")
        is ImageLoadState.Ready -> ZoomableImageView(state.drawable, file.absolutePath)
    }
}

@Composable
private fun ZoomableImageView(drawable: Drawable, imageKey: String) {
    var scale by remember(imageKey) { mutableFloatStateOf(1f) }
    var offset by remember(imageKey) { mutableStateOf(Offset.Zero) }
    var viewportWidth by remember(imageKey) { mutableFloatStateOf(0f) }
    var viewportHeight by remember(imageKey) { mutableFloatStateOf(0f) }

    fun clampOffset(candidate: Offset, candidateScale: Float): Offset {
        if (candidateScale <= 1f) return Offset.Zero
        val maxX = viewportWidth * (candidateScale - 1f) / 2f
        val maxY = viewportHeight * (candidateScale - 1f) / 2f
        return Offset(
            candidate.x.coerceIn(-maxX, maxX),
            candidate.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                viewportWidth = it.width.toFloat()
                viewportHeight = it.height.toFloat()
                offset = clampOffset(offset, scale)
            }
            .pointerInput(imageKey) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 8f)
                    scale = nextScale
                    offset = clampOffset(offset + pan, nextScale)
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(12, 12, 12, 12)
                }
            },
            update = { view ->
                if (view.drawable !== drawable) {
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val drawable: Drawable) : ImageLoadState
    data class Failed(val message: String) : ImageLoadState
}

@Composable
private fun SvgPreview(file: File) {
    if (file.length() > SVG_PREVIEW_MAX_BYTES) {
        PreviewFailure("SVG 超过 10 MB，请使用右上角按钮交给其他应用打开。")
        return
    }
    val document by produceState<SvgLoadState>(SvgLoadState.Loading, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching { buildSvgPreviewHtml(file.readBytes()) }.fold(
                onSuccess = { SvgLoadState.Ready(it) },
                onFailure = { SvgLoadState.Failed(it.message ?: "SVG 读取失败") },
            )
        }
    }
    when (val state = document) {
        SvgLoadState.Loading -> {
            LoadingPreview()
            return
        }
        is SvgLoadState.Failed -> {
            PreviewFailure("无法预览这个 SVG：${state.message}")
            return
        }
        is SvgLoadState.Ready -> {
            val documentKey = "${file.absolutePath}:${file.lastModified()}"
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                },
                update = { view ->
                    if (view.tag != documentKey) {
                        view.tag = documentKey
                        view.loadDataWithBaseURL(
                            "https://codex.local/",
                            state.html,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private sealed interface SvgLoadState {
    data object Loading : SvgLoadState
    data class Ready(val html: String) : SvgLoadState
    data class Failed(val message: String) : SvgLoadState
}

@Composable
private fun MediaPreview(preview: FilePreview) {
    val context = LocalContext.current
    val file = remember(preview.localPath) { File(preview.localPath) }
    val uri = remember(preview.localPath) {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    AndroidView(
        factory = { appContext ->
            VideoView(appContext).apply {
                val controls = MediaController(appContext)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoURI(uri)
                setOnPreparedListener { controls.show(0) }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PdfPreview(file: File) {
    val pageCount by produceState(0, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
        }
    }
    var page by remember(file.absolutePath) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, file.absolutePath, page) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file, page) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (page > 0) page -= 1 }, enabled = page > 0) { Text("上一页") }
            Text(if (pageCount > 0) "${page + 1} / $pageCount" else "正在读取…")
            Button(onClick = { if (page + 1 < pageCount) page += 1 }, enabled = page + 1 < pageCount) { Text("下一页") }
        }
        if (bitmap == null) LoadingPreview() else Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "PDF 第 ${page + 1} 页",
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun TextPreview(file: File, line: Int?) {
    val content by produceState("正在读取…", file.absolutePath, line) {
        value = withContext(Dispatchers.IO) { readTextWindow(file, line) }
    }
    Text(
        content,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun UnsupportedPreview(preview: FilePreview, openExternal: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(preview.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("文件已经安全传到手机。此格式需要使用手机中的对应应用打开。", modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = openExternal) { Text("使用手机应用打开") }
    }
}

@Composable
private fun LoadingPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun PreviewFailure(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

private fun readTextWindow(file: File, line: Int?): String {
    if (line != null && line > 0) {
        val lines = file.bufferedReader().useLines { sequence -> sequence.drop((line - 101).coerceAtLeast(0)).take(220).toList() }
        return "从第 ${line.coerceAtLeast(1)} 行附近显示\n\n" + lines.joinToString("\n")
    }
    val limit = 2_000_000
    val text = file.inputStream().bufferedReader().use { reader ->
        val buffer = CharArray(limit)
        val count = reader.read(buffer)
        if (count <= 0) "" else String(buffer, 0, count)
    }
    return if (file.length() > limit) "$text\n\n……手机预览只显示前 200 万字符，可使用其他应用打开完整文件。" else text
}

private fun renderPdfPage(file: File, pageIndex: Int): Bitmap? {
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (pageIndex !in 0 until renderer.pageCount) return@use null
            renderer.openPage(pageIndex).use { page ->
                val scale = (1600f / page.width).coerceAtMost(2f)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
