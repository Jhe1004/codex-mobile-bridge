package org.codexmobile.bridge

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

internal data class CachedCatalog(
    val savedAtMs: Long,
    val projects: List<ProjectSummary>,
    val threads: List<ThreadSummary>,
)

internal class CatalogCacheStore(private val file: File) {
    @Synchronized
    fun load(): CachedCatalog? {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) error("unsupported catalog cache")
                val savedAtMs = input.readLong()
                val projectCount = input.readInt()
                if (projectCount !in 0..MAX_PROJECTS) error("invalid project count")
                val projects = buildList(projectCount) {
                    repeat(projectCount) {
                        add(
                            ProjectSummary(
                                id = input.readBoundedString(),
                                name = input.readBoundedString(),
                                rootPath = input.readBoundedString(),
                                threadCount = input.readInt(),
                                order = input.readInt(),
                            )
                        )
                    }
                }
                val threadCount = input.readInt()
                if (threadCount !in 0..MAX_THREADS) error("invalid thread count")
                val threads = buildList(threadCount) {
                    repeat(threadCount) {
                        val activityOrdinal = input.readInt()
                        val activity = ThreadActivity.entries.getOrNull(activityOrdinal) ?: ThreadActivity.IDLE
                        add(
                            ThreadSummary(
                                id = input.readBoundedString(),
                                title = input.readBoundedString(),
                                preview = input.readBoundedString(),
                                cwd = input.readBoundedString(),
                                updatedAt = input.readLong(),
                                projectId = input.readBoundedString(),
                                // A running marker is never trustworthy after a cold offline start.
                                activity = if (activity == ThreadActivity.RUNNING) ThreadActivity.IDLE else activity,
                                completionRevision = input.readBoundedString(),
                                contentRevision = input.readBoundedString(),
                            )
                        )
                    }
                }
                CachedCatalog(savedAtMs, projects, threads)
            }
        }.onFailure { file.delete() }.getOrNull()
    }

    @Synchronized
    fun save(projects: List<ProjectSummary>, threads: List<ThreadSummary>, allowEmpty: Boolean = false) {
        if (!allowEmpty && projects.isEmpty() && threads.isEmpty()) return
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.partial")
        runCatching {
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeLong(System.currentTimeMillis())
                output.writeInt(projects.size.coerceAtMost(MAX_PROJECTS))
                projects.take(MAX_PROJECTS).forEach { project ->
                    output.writeBoundedString(project.id)
                    output.writeBoundedString(project.name)
                    output.writeBoundedString(project.rootPath)
                    output.writeInt(project.threadCount)
                    output.writeInt(project.order)
                }
                output.writeInt(threads.size.coerceAtMost(MAX_THREADS))
                threads.take(MAX_THREADS).forEach { thread ->
                    output.writeInt(thread.activity.ordinal)
                    output.writeBoundedString(thread.id)
                    output.writeBoundedString(thread.title)
                    output.writeBoundedString(thread.preview)
                    output.writeBoundedString(thread.cwd)
                    output.writeLong(thread.updatedAt)
                    output.writeBoundedString(thread.projectId)
                    output.writeBoundedString(thread.completionRevision)
                    output.writeBoundedString(thread.contentRevision)
                }
            }
            if (temporary.length() > MAX_FILE_BYTES) error("catalog cache too large")
            if (file.exists()) file.delete()
            check(temporary.renameTo(file)) { "catalog cache rename failed" }
        }.onFailure { temporary.delete() }
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "catalog string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readInt()
        if (length !in 0..MAX_STRING_BYTES) throw EOFException("invalid catalog string")
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x434D4247
        private const val FORMAT_VERSION = 1
        private const val MAX_PROJECTS = 500
        private const val MAX_THREADS = 5_000
        private const val MAX_STRING_BYTES = 1024 * 1024
        private const val MAX_FILE_BYTES = 16L * 1024L * 1024L

        fun forContext(context: Context): CatalogCacheStore =
            CatalogCacheStore(File(context.filesDir, "catalog_cache_v1.bin"))
    }
}
