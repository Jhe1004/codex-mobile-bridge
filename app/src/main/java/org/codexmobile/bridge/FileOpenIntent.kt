package org.codexmobile.bridge

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal fun openPreviewWithAnotherApp(context: Context, preview: FilePreview): Boolean = runCatching {
    val file = File(preview.localPath)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, preview.mimeType.ifBlank { "application/octet-stream" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(preview.name, uri)
    }
    context.startActivity(Intent.createChooser(intent, "使用其他应用打开"))
    true
}.getOrDefault(false)
