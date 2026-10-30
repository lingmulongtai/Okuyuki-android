package com.okuyuki.android.host

import org.junit.Assert.*
import org.junit.Test

class HostSessionTest {
    @Test fun `delayed share from a stopped session cannot enter a restarted session`() {
        val first = HostState(running = true, sessionId = "first")
        val second = HostState(running = true, sessionId = "second")
        assertTrue(acceptsPanoramaShare("first", first))
        assertFalse(acceptsPanoramaShare("first", HostState()))
        assertFalse(acceptsPanoramaShare("first", second))
        assertFalse(acceptsPanoramaShare(null, second))
    }
}
