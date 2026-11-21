package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class DesktopHistoryTest {
    @Test fun desktopAndSystemOverlaysPreservePreviousApplication() {
        val history = DesktopHistory("example.previous")
        assertFalse(history.observe("com.zui.launcher", home = true, applicationWindow = true))
        assertFalse(history.observe("com.android.systemui", home = false, applicationWindow = true))
        assertFalse(history.observe("example.overlay", home = false, applicationWindow = false))
        assertFalse(history.observe(null, home = false, applicationWindow = true))
        assertEquals("example.previous", history.lastApp)
        assertTrue(history.observe("example.next", home = false, applicationWindow = true))
        assertEquals("example.next", history.lastApp)
        assertFalse(history.observe("example.next", home = false, applicationWindow = true))
    }
}
