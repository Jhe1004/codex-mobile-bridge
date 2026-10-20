package org.codexmobile.bridge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val CACHE_PREFS = "file_preview_cache_v2"
private const val LEGACY_CACHE_PREFS = "file_preview_cache_v1"
private const val LEGACY_CACHE_DIRECTORY = "mobile_files"
private const val MAX_CACHE_BYTES = 512L * 1024L * 1024L

internal fun stableCacheKey(threadId: String, target: String): String =
    sha256Hex("$threadId\n$target".toByteArray(Charsets.UTF_8))

internal data class FileRequestTarget(val path: String, val fragment: String?)

internal fun parseFileRequestTarget(target: String): FileRequestTarget {
    val marker = target.indexOf('#')
    if (marker < 0) return FileRequestTarget(target, null)
    return FileRequestTarget(
        path = target.substring(0, marker),
        fragment = target.substring(marker + 1).takeIf { it.isNotBlank() },
    )
}

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
    fun find(context: Context, projectId: String, threadId: String, target: String): FilePreview? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val key = stableCacheKey("$projectId\n$threadId", target)
        val encoded = prefs.getString(key, null)
            ?: return migrateLegacy(context, projectId, threadId, target)
        val value = runCatching { JSONObject(encoded) }.getOrNull() ?: return removeInvalid(prefs, key)
        val fileName = value.optString("fileName")
        val directory = directory(context, projectId, threadId)
        val file = File(directory, fileName)
        val expectedSize = value.optLong("size", -1L)
        if (fileName.isBlank() || file.parentFile != directory || !file.isFile || file.length() != expectedSize) {
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
        projectId: String,
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
            .put("projectId", projectId)
            .put("threadId", threadId)
        preview.line?.let { value.put("line", it) }
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(stableCacheKey("$projectId\n$threadId", requestedTarget), value.toString())
            .putString(stableCacheKey("$projectId\n$threadId", preview.sourcePath), value.toString())
            .apply()
        prune(context, file)
    }

    fun directory(context: Context, projectId: String, threadId: String): File =
        ProjectCacheLayout.previewDirectory(ProjectCacheLayout.root(context), projectId, threadId).apply { mkdirs() }

    fun removeMetadata(context: Context, projectIds: Set<String>, threadIds: Set<String>) {
        if (projectIds.isEmpty() && threadIds.isEmpty()) return
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.forEach { (key, encoded) ->
            val value = (encoded as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            if (value.optString("projectId") in projectIds || value.optString("threadId") in threadIds) editor.remove(key)
        }
        editor.apply()
    }

    fun removeLegacyProjects(context: Context, projectRoots: List<String>) {
        if (projectRoots.isEmpty()) return
        val roots = projectRoots.map { File(it).normalize().path }
        val prefs = context.getSharedPreferences(LEGACY_CACHE_PREFS, Context.MODE_PRIVATE)
        val directory = File(context.cacheDir, LEGACY_CACHE_DIRECTORY)
        val editor = prefs.edit()
        prefs.all.forEach { (key, encoded) ->
            val value = (encoded as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@forEach
            val source = value.optString("sourcePath").takeIf(String::isNotBlank)?.let { File(it).normalize().path }
                ?: return@forEach
            if (roots.none { root -> source == root || source.startsWith("$root${File.separator}") }) return@forEach
            val file = File(directory, value.optString("fileName"))
            if (file.parentFile == directory) file.delete()
            editor.remove(key)
        }
        editor.apply()
    }

    private fun prune(context: Context, keep: File) {
        val files = ProjectCacheLayout.root(context).walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".partial") && it.parentFile?.parentFile?.name == "files" }
            .sortedByDescending { it.lastModified() }
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

    private fun migrateLegacy(
        context: Context,
        projectId: String,
        threadId: String,
        target: String,
    ): FilePreview? {
        val legacyPrefs = context.getSharedPreferences(LEGACY_CACHE_PREFS, Context.MODE_PRIVATE)
        val legacyKey = stableCacheKey(threadId, target)
        val encoded = legacyPrefs.getString(legacyKey, null) ?: return null
        val value = runCatching { JSONObject(encoded) }.getOrNull() ?: return null
        val legacy = File(File(context.cacheDir, LEGACY_CACHE_DIRECTORY), value.optString("fileName"))
        val expectedSize = value.optLong("size", -1L)
        if (!legacy.isFile || legacy.length() != expectedSize) {
            legacyPrefs.edit().remove(legacyKey).apply()
            return null
        }
        val destination = File(directory(context, projectId, threadId), legacy.name)
        if (destination.exists()) {
            legacy.delete()
        } else if (!legacy.renameTo(destination)) {
            legacy.copyTo(destination, overwrite = false)
            legacy.delete()
        }
        val preview = FilePreview(
            localPath = destination.absolutePath,
            sourcePath = value.optString("sourcePath", target),
            name = value.optString("name", legacy.name),
            mimeType = value.optString("mimeType", "application/octet-stream"),
            size = expectedSize,
            line = value.optInt("line").takeIf { value.has("line") && !value.isNull("line") },
        )
        store(
            context = context,
            projectId = projectId,
            threadId = threadId,
            requestedTarget = target,
            preview = preview,
            sha256 = value.optString("sha256"),
            mtimeMs = value.optLong("mtimeMs"),
        )
        legacyPrefs.edit().remove(legacyKey).apply()
        return preview
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

