package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOs4SuperIslandTest {
    private val thread = ThreadSummary(
        id = "thread-1",
        title = "测试会话",
        preview = "",
        cwd = "/fixture",
        updatedAt = 0L,
        projectId = "project-1",
    )

    @Test
    fun enablesOnlyForXiaomiHyperOs4WithIslandProtocol() {
        assertTrue(SuperIslandCapability("Xiaomi", "OS4.0", true, 3).eligible)
        assertTrue(SuperIslandCapability("Redmi", "OS4.1", true, 4).eligible)
        assertFalse(SuperIslandCapability("Xiaomi", "OS3.0", true, 3).eligible)
        assertFalse(SuperIslandCapability("Samsung", "OS4.0", true, 3).eligible)
        assertFalse(SuperIslandCapability("Xiaomi", "OS4.0", false, 3).eligible)
        assertFalse(SuperIslandCapability("Xiaomi", "OS4.0", true, 2).eligible)
    }

    @Test
    fun hidesWhenNoChatIsOpen() {
        assertNull(superIslandPresentation(BridgeUiState()))
    }

    @Test
    fun reportsHealthyWorkingAndApprovalStates() {
        val ready = connectedState()
        assertEquals(SuperIslandPhase.READY, superIslandPresentation(ready)?.phase)

        val working = ready.copy(activeTurnId = "turn-1")
        assertEquals(SuperIslandPhase.WORKING, superIslandPresentation(working)?.phase)

        val approval = ready.copy(approval = PendingApproval("1", "confirm", "允许执行测试"))
        assertEquals(SuperIslandPhase.APPROVAL, superIslandPresentation(approval)?.phase)
    }

    @Test
    fun reportsCompletedAndDisconnectedStates() {
        val now = 10_000L
        val completed = connectedState().copy(lastTurnCompletedAtMs = now - 1_000L)
        assertEquals(SuperIslandPhase.COMPLETED, superIslandPresentation(completed, now)?.phase)

        val disconnected = connectedState().copy(relayConnected = false)
        assertEquals(SuperIslandPhase.DISCONNECTED, superIslandPresentation(disconnected, now)?.phase)

        val offline = connectedState().copy(offlineMode = true, relayConnected = false, macConnected = false, appServerReady = false)
        assertEquals(SuperIslandPhase.OFFLINE, superIslandPresentation(offline, now)?.phase)
    }

    private fun connectedState() = BridgeUiState(
        relayConnected = true,
        macConnected = true,
        appServerReady = true,
        selectedThread = thread,
        preparedThreadId = thread.id,
    )
}
