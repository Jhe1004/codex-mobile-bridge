package org.codexmobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadActivityTest {
    @Test
    fun runningAlwaysTakesPriority() {
        assertEquals(
            ThreadActivity.RUNNING,
            displayedThreadActivity(ThreadActivity.RUNNING, "turn-2", "turn-1", true, false),
        )
    }

    @Test
    fun unseenCompletionBecomesUnread() {
        assertEquals(
            ThreadActivity.UNREAD,
            displayedThreadActivity(ThreadActivity.IDLE, "turn-2", "turn-1", true, false),
        )
    }

    @Test
    fun openingThreadMarksCompletionRead() {
        assertEquals(
            ThreadActivity.IDLE,
            displayedThreadActivity(ThreadActivity.IDLE, "turn-2", "turn-1", true, true),
        )
    }

    @Test
    fun firstSnapshotCreatesBaselineWithoutFalseUnread() {
        assertEquals(
            ThreadActivity.IDLE,
            displayedThreadActivity(ThreadActivity.IDLE, "turn-2", null, false, false),
        )
    }
}
