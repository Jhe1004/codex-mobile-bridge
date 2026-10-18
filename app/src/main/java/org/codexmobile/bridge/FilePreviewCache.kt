package org.codexmobile.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val CACHE_PREFS = "file_preview_cache_v1"
private const val CACHE_DIRECTORY = "mobile_files"
private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

internal fun stableCacheKey(threadId: String, target: String): String =
    sha256Hex("$threadId\n$target".toByteArray(Charsets.UTF_8))

internal fun cacheFileName(sha256: String, originalName: String): String {
    val safeName = truncateUtf8(
        originalName
        .replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]"), "_")
        .ifBlank { "download.bin" },
        160,
    )
    return "${sha256.lowercase()}-$safeName"
}

private fun truncateUtf8(value: String, maxBytes: Int): String {
    val result = StringBuilder()
    var used = 0
    for (character in value) {
        val bytes = character.toString().toByteArray(Charsets.UTF_8).size
        if (used + bytes > maxBytes) break
        result.append(character)
        used += bytes
    }
    return result.toString().ifBlank { "download.bin" }
}

internal object FilePreviewCache {
    fun find(context: Context, threadId: String, target: String): FilePreview? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val key = stableCacheKey(threadId, target)
        val encoded = prefs.getString(key, null) ?: return null
        val value = runCatching { JSONObject(encoded) }.getOrNull() ?: return removeInvalid(prefs, key)
        val fileName = value.optString("fileName")
        val file = File(directory(context), fileName)
        val expectedSize = value.optLong("size", -1L)
        if (fileName.isBlank() || file.parentFile != directory(context) || !file.isFile || file.length() != expectedSize) {
            return removeInvalid(prefs, key)
        }
        prefs.edit().putString(key, value.put("lastAccessMs", System.currentTimeMillis()).toString()).apply()
        return FilePreview(
            localPath = file.absolutePath,
            sourcePath = value.getString("sourcePath"),
            name = value.getString("name"),
            mimeType = value.optString("mimeType", "application/octet-stream"),
            size = expectedSize,
            line = value.optInt("line").takeIf { value.has("line") && !value.isNull("line") },
        )
    }

    fun store(
        context: Context,
        threadId: String,
        requestedTarget: String,
        preview: FilePreview,
        sha256: String,
        mtimeMs: Long,
    ) {
        val file = File(preview.localPath)
        val value = JSONObject()
            .put("fileName", file.name)
            .put("sourcePath", preview.sourcePath)
            .put("name", preview.name)
            .put("mimeType", preview.mimeType)
            .put("size", preview.size)
            .put("sha256", sha256)
            .put("mtimeMs", mtimeMs)
            .put("lastAccessMs", System.currentTimeMillis())
        preview.line?.let { value.put("line", it) }
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(stableCacheKey(threadId, requestedTarget), value.toString())
            .putString(stableCacheKey(threadId, preview.sourcePath), value.toString())
            .apply()
        prune(context, file)
    }

    fun directory(context: Context): File = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }

    private fun prune(context: Context, keep: File) {
        val files = directory(context).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".partial") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var retained = 0L
        for (file in files) {
            if (file == keep || retained + file.length() <= MAX_CACHE_BYTES) {
                retained += file.length()
            } else {
                file.delete()
            }
        }
    }

    private fun removeInvalid(
        prefs: android.content.SharedPreferences,
        key: String,
    ): FilePreview? {
        prefs.edit().remove(key).apply()
        return null
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

