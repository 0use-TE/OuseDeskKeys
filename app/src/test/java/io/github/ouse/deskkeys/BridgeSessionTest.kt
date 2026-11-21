package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class BridgeSessionTest {
    @Test fun connectionTimeoutRejectsLateCapabilityResult() {
        val session = BridgeSession()
        val token = session.open()
        assertNull(session.begin())
        session.invalidate() // Connection watchdog fires while capabilities() is blocked.
        assertFalse(session.markReady(token))
        assertNull(session.begin())
    }
    @Test fun operationTimeoutRejectsLateCompletionAndAllowsFreshSession() {
        val session = BridgeSession()
        val oldToken = session.open()
        assertTrue(session.markReady(oldToken))
        val oldOperation = session.begin()!!
        session.invalidate() // Operation watchdog fires; late result cannot click an icon.
        val newToken = session.open()
        assertTrue(session.markReady(newToken))
        val newOperation = session.begin()!!
        assertFalse(session.finish(oldOperation))
        assertNull(session.begin()) // Old result must not release the new busy guard.
        assertTrue(session.finish(newOperation))
        assertNotNull(session.begin())
    }
    @Test fun disconnectAndRebindDoNotAcceptAnOldServiceCallback() {
        val session = BridgeSession()
        val old = session.open()
        session.invalidate()
        val replacement = session.open()
        assertFalse(session.accepts(old))
        assertFalse(session.markReady(old))
        assertTrue(session.markReady(replacement))
        assertFalse(session.markReady(replacement))
    }
    @Test fun onlyOneOperationCanBePendingAndCompletedOnce() {
        val session = BridgeSession()
        session.markReady(session.open())
        val operation = session.begin()!!
        assertNull(session.begin())
        assertTrue(session.finish(operation))
        assertFalse(session.finish(operation))
    }
}
