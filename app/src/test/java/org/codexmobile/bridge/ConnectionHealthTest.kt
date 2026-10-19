package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionHealthTest {
    @Test
    fun allLayersReadyIsHealthy() {
        assertEquals(ConnectionHealth.HEALTHY, connectionHealth(true, true, true))
    }

    @Test
    fun relayOnlyIsDegraded() {
        assertEquals(ConnectionHealth.DEGRADED, connectionHealth(true, false, false))
        assertEquals(ConnectionHealth.DEGRADED, connectionHealth(true, true, false))
    }

    @Test
    fun missingRelayIsDisconnected() {
        assertEquals(ConnectionHealth.DISCONNECTED, connectionHealth(false, true, true))
        assertEquals(ConnectionHealth.DISCONNECTED, connectionHealth(false, false, false))
    }

    @Test
    fun selectedConnectedThreadIsReadyWithoutDesktopWake() {
        assertEquals(
            SessionConnectionHealth.READY,
            sessionConnectionHealth(true, true, true, "thread-1", null),
        )
    }

    @Test
    fun selectedPreparingThreadIsPreparing() {
        assertEquals(
            SessionConnectionHealth.PREPARING,
            sessionConnectionHealth(true, true, true, "thread-1", "thread-1"),
        )
    }

    @Test
    fun connectedThreadDoesNotNeedPreheatingToBeHealthy() {
        assertEquals(
            SessionConnectionHealth.READY,
            sessionConnectionHealth(true, true, true, "thread-1", null),
        )
    }

    @Test
    fun disconnectedThreadIsUnhealthy() {
        assertEquals(
            SessionConnectionHealth.UNHEALTHY,
            sessionConnectionHealth(false, true, true, "thread-1", null),
        )
    }
}
