package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSendReadinessTest {
    private val thread = ThreadSummary("thread-1", "测试", "", "", 0L, "project-1")

    private fun state(
        relay: Boolean = true,
        mac: Boolean = true,
        codex: Boolean = true,
        content: ConversationContentState = ConversationContentState.LIVE,
        preparing: String? = null,
        prepared: String? = "thread-1",
        running: Boolean = false,
        pending: Boolean = false,
    ) = BridgeUiState(
        relayConnected = relay,
        macConnected = mac,
        appServerReady = codex,
        selectedThread = thread.copy(activity = if (running) ThreadActivity.RUNNING else ThreadActivity.IDLE),
        conversationContentState = content,
        preparingThreadId = preparing,
        preparedThreadId = prepared,
        sendPending = pending,
    )

    @Test
    fun readyRequiresEveryLayerAndPreparedThread() {
        assertEquals(ConversationSendReadiness.READY, conversationSendReadiness(state()))
        assertEquals(ConversationSendReadiness.RELAY_OFFLINE, conversationSendReadiness(state(relay = false)))
        assertEquals(ConversationSendReadiness.OFFLINE_MODE, conversationSendReadiness(state().copy(offlineMode = true)))
        assertEquals(ConversationSendReadiness.MAC_OFFLINE, conversationSendReadiness(state(mac = false)))
        assertEquals(ConversationSendReadiness.CODEX_NOT_READY, conversationSendReadiness(state(codex = false)))
        assertEquals(
            ConversationSendReadiness.CONTENT_NOT_SYNCED,
            conversationSendReadiness(state(content = ConversationContentState.SYNCING)),
        )
        assertEquals(
            ConversationSendReadiness.THREAD_PREPARING,
            conversationSendReadiness(state(preparing = "thread-1", prepared = null)),
        )
        assertEquals(
            ConversationSendReadiness.THREAD_NOT_PREPARED,
            conversationSendReadiness(state(prepared = null)),
        )
        assertEquals(
            ConversationSendReadiness.DELIVERY_UNCONFIRMED,
            conversationSendReadiness(
                state().copy(
                    pendingOutgoing = PendingOutgoingMessage(
                        "thread-1",
                        ChatMessage("local-1", "user", "测试", clientId = "client-1"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun runningOrPendingTurnBlocksAnotherSend() {
        assertEquals(ConversationSendReadiness.TURN_RUNNING, conversationSendReadiness(state(running = true)))
        assertEquals(ConversationSendReadiness.SEND_PENDING, conversationSendReadiness(state(pending = true)))
    }

    @Test
    fun onlyUncertainTransportFailuresKeepPendingDelivery() {
        assertEquals(true, deliveryFailureIsAmbiguous("连接中断"))
        assertEquals(true, deliveryFailureIsAmbiguous("请求超时，正在自动重试"))
        assertEquals(true, deliveryFailureIsAmbiguous("connection reset by peer"))
        assertEquals(false, deliveryFailureIsAmbiguous("当前未连接"))
        assertEquals(false, deliveryFailureIsAmbiguous("thread not found"))
    }
}
