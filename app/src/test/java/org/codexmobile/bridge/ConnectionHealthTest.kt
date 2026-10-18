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
    fun selectedPreparedThreadIsReady() {
        assertEquals(
            SessionConnectionHealth.READY,
            sessionConnectionHealth(true, true, true, "thread-1", null, "thread-1"),
        )
    }

    @Test
    fun selectedPreparingThreadIsPreparing() {
        assertEquals(
            SessionConnectionHealth.PREPARING,
            sessionConnectionHealth(true, true, true, "thread-1", "thread-1", null),
        )
    }

    @Test
    fun staleOrDisconnectedThreadIsUnhealthy() {
        assertEquals(
            SessionConnectionHealth.UNHEALTHY,
            sessionConnectionHealth(true, true, true, "thread-1", null, "thread-2"),
        )
        assertEquals(
            SessionConnectionHealth.UNHEALTHY,
            sessionConnectionHealth(false, true, true, "thread-1", null, "thread-1"),
        )
    }
}
