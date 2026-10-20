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
            val restored = store.load(thread)
            assertEquals(messages, restored?.messages)
            assertTrue(cacheMatchesThread(restored, thread))
            assertFalse(cacheMatchesThread(restored, thread.copy(contentRevision = "file:11:21")))
            assertFalse(cacheMatchesThread(restored, thread.copy(contentRevision = "")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsTwoThousandMessagesAndMergesARecentTail() {
        val root = Files.createTempDirectory("conversation-cache-2000-test").toFile()
        try {
            val store = ConversationCacheStore(root)
            val thread = ThreadSummary("thread-2000", "title", "", "/tmp", 10, "project", contentRevision = "messages:2000")
            val messages = (1..2_100).map { ChatMessage("m$it", if (it % 2 == 0) "assistant" else "user", "message-$it") }
            store.save(thread, messages)
            val restored = store.load(thread)?.messages.orEmpty()
            assertEquals(2_000, restored.size)
            assertEquals("m101", restored.first().id)
            assertEquals("m2100", restored.last().id)

            val merged = mergeConversationTail(restored, listOf(ChatMessage("m2100", "assistant", "updated"), ChatMessage("m2101", "user", "new")))
            assertEquals(2_000, merged.size)
            assertEquals("updated", merged[merged.lastIndex - 1].text)
            assertEquals("m2101", merged.last().id)
        } finally {
            root.deleteRecursively()
        }
    }
}
