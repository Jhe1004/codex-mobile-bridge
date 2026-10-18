package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageMergeTest {
    private val optimistic = ChatMessage(
        id = "local-client-1",
        role = "user",
        text = "压缩期间发送的问题",
        detail = "正在发送…",
        clientId = "client-1",
    )

    @Test
    fun keepsOptimisticMessageWhileServerHistoryIsStillBehind() {
        val result = mergeServerMessages(
            threadId = "thread-1",
            serverMessages = listOf(ChatMessage("a1", "assistant", "较早的回答")),
            pendingOutgoing = PendingOutgoingMessage("thread-1", optimistic),
        )

        assertEquals(listOf("a1", "local-client-1"), result.messages.map { it.id })
        assertEquals("client-1", result.pendingOutgoing?.message?.clientId)
    }

    @Test
    fun removesOptimisticCopyAfterServerAcknowledgesClientId() {
        val result = mergeServerMessages(
            threadId = "thread-1",
            serverMessages = listOf(
                ChatMessage("u1", "user", optimistic.text, clientId = "client-1"),
            ),
            pendingOutgoing = PendingOutgoingMessage("thread-1", optimistic),
        )

        assertEquals(listOf("u1"), result.messages.map { it.id })
        assertNull(result.pendingOutgoing)
    }
}
