package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class WindowActionsTest {
    @Test fun taskbarToggleMinimizesOnlyTheMatchingFocusedApplication() {
        assertTrue(WindowActions.matchesTarget("example.a", "example.a", "example.a"))
        assertTrue(WindowActions.matchesTarget("example.a", "example.child", "example.a"))
        assertFalse(WindowActions.matchesTarget("example.a", "example.b", "example.b"))
        assertFalse(WindowActions.matchesTarget("example.a", "com.zui.launcher", "com.zui.launcher"))
        assertFalse(WindowActions.matchesTarget("", null, null))
        assertEquals(WindowActions.MINIMIZE, WindowActions.requiredCapability(WindowActions.TOGGLE_TASKBAR))
    }
    @Test fun windowShortcutsRequireExactModifiersAndIgnoreF4() {
        val mask = ShortcutRules.CTRL or ShortcutRules.ALT
        assertEquals(WindowActions.CLOSE, WindowActions.shortcut(51, mask))
        assertEquals(WindowActions.MINIMIZE, WindowActions.shortcut(41, mask))
        assertNull(WindowActions.shortcut(51, mask or ShortcutRules.SHIFT))
        assertNull(WindowActions.shortcut(51, ShortcutRules.CTRL))
        assertNull(WindowActions.shortcut(134, ShortcutRules.ALT))
    }
    @Test fun homeRecentsAndSystemWindowsCannotBeClosed() {
        for (type in listOf(0, 2, 3, 4, 5)) assertFalse(WindowActions.allowedTarget(type, "example.app"))
        assertFalse(WindowActions.allowedTarget(1, "com.android.systemui"))
        assertFalse(WindowActions.allowedTarget(1, "com.zui.launcher"))
        assertFalse(WindowActions.allowedTarget(1, ""))
        assertTrue(WindowActions.allowedTarget(1, "example.app"))
    }
    @Test fun staleAndFutureRequestsAreRejected() {
        assertTrue(WindowActions.freshRequest(1000, 1000))
        assertTrue(WindowActions.freshRequest(1000, 1750))
        assertFalse(WindowActions.freshRequest(1000, 1751))
        assertFalse(WindowActions.freshRequest(1000, 999))
    }
}
