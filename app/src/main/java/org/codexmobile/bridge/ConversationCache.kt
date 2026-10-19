package org.codexmobile.bridge

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.security.MessageDigest

internal data class CachedConversation(
    val threadId: String,
    val contentRevision: String,
    val savedAtMs: Long,
    val messages: List<ChatMessage>,
)

internal fun cacheMatchesThread(cache: CachedConversation?, thread: ThreadSummary): Boolean =
    cache != null && thread.contentRevision.startsWith("file:") && cache.contentRevision == thread.contentRevision

internal class ConversationCacheStore(private val root: File) {
    @Synchronized
    fun load(threadId: String): CachedConversation? {
        val file = cacheFile(threadId)
        if (!file.isFile || file.length() !in 1..MAX_ENTRY_BYTES) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) error("unsupported cache")
                val storedThreadId = input.readBoundedString()
                if (storedThreadId != threadId) error("thread mismatch")
                val contentRevision = input.readBoundedString()
                val savedAtMs = input.readLong()
                val count = input.readInt()
                if (count !in 0..MAX_MESSAGES) error("invalid message count")
                val messages = buildList(count) {
                    repeat(count) {
                        add(
                            ChatMessage(
                                id = input.readBoundedString(),
                                role = input.readBoundedString(),
                                text = input.readBoundedString(),
                                detail = input.readBoundedString(),
                                clientId = input.readBoundedString(),
                            )
                        )
                    }
                }
                CachedConversation(storedThreadId, contentRevision, savedAtMs, messages)
            }
        }.onFailure { file.delete() }.getOrNull()?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    @Synchronized
    fun save(thread: ThreadSummary, messages: List<ChatMessage>) {
        if (thread.id.isBlank() || thread.contentRevision.isBlank()) return
        root.mkdirs()
        val retained = trimToLimit(messages.takeLast(MAX_MESSAGES))
        val destination = cacheFile(thread.id)
        val temporary = File(root, "${destination.name}.partial")
        runCatching {
            DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeBoundedString(thread.id)
                output.writeBoundedString(thread.contentRevision)
                output.writeLong(System.currentTimeMillis())
                output.writeInt(retained.size)
                retained.forEach { message ->
                    output.writeBoundedString(message.id)
                    output.writeBoundedString(message.role)
                    output.writeBoundedString(message.text)
                    output.writeBoundedString(message.detail)
                    output.writeBoundedString(message.clientId)
                }
            }
            if (temporary.length() > MAX_ENTRY_BYTES) error("cache entry too large")
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "cache rename failed" }
            destination.setLastModified(System.currentTimeMillis())
            prune()
        }.onFailure { temporary.delete() }
    }

    private fun trimToLimit(messages: List<ChatMessage>): List<ChatMessage> {
        var bytes = 128L
        val keptNewestFirst = mutableListOf<ChatMessage>()
        for (message in messages.asReversed()) {
            val messageBytes = listOf(message.id, message.role, message.text, message.detail, message.clientId)
                .sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 4L }
            if (keptNewestFirst.isNotEmpty() && bytes + messageBytes > MAX_ENTRY_BYTES - 1024L) break
            bytes += messageBytes
            keptNewestFirst += message
        }
        return keptNewestFirst.asReversed()
    }

    private fun prune() {
        var total = 0L
        root.listFiles()
            ?.filter { it.isFile && it.extension == "bin" }
            ?.sortedByDescending { it.lastModified() }
            ?.forEachIndexed { index, file ->
                total += file.length()
                if (index >= MAX_ENTRIES || total > MAX_TOTAL_BYTES) file.delete()
            }
    }

    private fun cacheFile(threadId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(threadId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(root, "$name.bin")
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "cache string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readInt()
        if (length !in 0..MAX_STRING_BYTES) throw EOFException("invalid cache string")
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x434D4243
        private const val FORMAT_VERSION = 1
        private const val MAX_MESSAGES = 120
        private const val MAX_STRING_BYTES = 4 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 12L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        private const val MAX_ENTRIES = 40

        fun forContext(context: Context): ConversationCacheStore =
            ConversationCacheStore(File(context.filesDir, "conversation_cache_v1"))
    }
}
