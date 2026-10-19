package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConversationCacheTest {
    @Test
    fun persistsMessagesAndMatchesOnlyAnIdenticalRevision() {
        val root = Files.createTempDirectory("conversation-cache-test").toFile()
        try {
            val store = ConversationCacheStore(root)
            val thread = ThreadSummary("thread-1", "title", "", "/tmp", 10, "project", contentRevision = "file:10:20")
            val messages = listOf(
                ChatMessage("u1", "user", "question", clientId = "client-1"),
                ChatMessage("a1", "assistant", "answer"),
            )
            store.save(thread, messages)
            val restored = store.load(thread.id)
            assertEquals(messages, restored?.messages)
            assertTrue(cacheMatchesThread(restored, thread))
            assertFalse(cacheMatchesThread(restored, thread.copy(contentRevision = "file:11:21")))
            assertFalse(cacheMatchesThread(restored, thread.copy(contentRevision = "")))
        } finally {
            root.deleteRecursively()
        }
    }
}
