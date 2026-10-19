package org.codexmobile.bridge

internal data class BrowserDocumentPolicy(
    val mimeType: String,
    val encoding: String?,
    val javaScriptEnabled: Boolean,
    val fitDocumentOnFirstLoad: Boolean,
)

internal fun usesImmersiveBrowser(preview: FilePreview): Boolean = when (previewKind(preview)) {
    PreviewKind.HTML, PreviewKind.SVG -> true
    else -> false
}

internal fun browserDocumentPolicy(preview: FilePreview): BrowserDocumentPolicy = when (previewKind(preview)) {
    PreviewKind.SVG -> BrowserDocumentPolicy(
        mimeType = "image/svg+xml",
        encoding = "UTF-8",
        javaScriptEnabled = false,
        fitDocumentOnFirstLoad = true,
    )
    PreviewKind.HTML -> BrowserDocumentPolicy(
        mimeType = "text/html",
        encoding = "UTF-8",
        javaScriptEnabled = true,
        fitDocumentOnFirstLoad = false,
    )
    else -> error("只有 HTML 和 SVG 可以使用内置浏览器")
}

