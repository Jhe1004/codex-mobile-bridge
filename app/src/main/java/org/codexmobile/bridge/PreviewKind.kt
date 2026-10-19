package org.codexmobile.bridge

internal enum class PreviewKind {
    HTML,
    PDF,
    SVG,
    IMAGE,
    MEDIA,
    TEXT,
    EXTERNAL,
}

internal fun previewKind(preview: FilePreview): PreviewKind {
    val mime = preview.mimeType.lowercase().substringBefore(';')
    val extension = preview.name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "text/html" || extension in setOf("html", "htm") -> PreviewKind.HTML
        mime == "application/pdf" || extension == "pdf" -> PreviewKind.PDF
        mime == "image/svg+xml" || extension == "svg" -> PreviewKind.SVG
        mime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif") -> PreviewKind.IMAGE
        mime.startsWith("audio/") || mime.startsWith("video/") || extension in setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "mp4", "m4v", "webm", "mov", "3gp") -> PreviewKind.MEDIA
        mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/x-yaml") ||
            extension in setOf("md", "markdown", "json", "xml", "yaml", "yml", "csv", "tsv", "py", "r", "js", "mjs", "ts", "kt", "java", "c", "cpp", "h", "hpp", "sh", "log", "toml", "ini", "sql") -> PreviewKind.TEXT
        else -> PreviewKind.EXTERNAL
    }
}
