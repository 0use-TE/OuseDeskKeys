package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class DesktopControlsTest {
    @Test fun notificationBadgesDoNotChangeTheApplicationName() {
        assertEquals("微信", TaskbarRules.appLabel("“微信”有 2 条通知"))
        assertEquals("QQ", TaskbarRules.appLabel("\"QQ\"有 12 条通知"))
        assertEquals("微信", TaskbarRules.appLabel("“微信”有 2 条通知 活跃"))
        assertEquals("OneTunnel", TaskbarRules.appLabel("OneTunnel 活跃"))
        assertEquals("我的应用有通知", TaskbarRules.appLabel("我的应用有通知"))
        assertEquals("“我的应用”", TaskbarRules.appLabel("“我的应用”"))
    }
    @Test fun taskbarControlsWithoutResourceIdsAreNotNumbered() {
        for (label in listOf("应用列表", "搜索                     ", "  Search ", "All apps", "任务栏分隔线")) {
            assertFalse(label, TaskbarRules.isAppIcon(label, ""))
        }
        assertTrue(TaskbarRules.isAppIcon("QQ", ""))
        assertTrue(TaskbarRules.isAppIcon("OneTunnel 活跃", ""))
        assertTrue(TaskbarRules.isAppIcon("SearchTool", ""))
        assertFalse(TaskbarRules.isAppIcon("Launcher", "all_apps_button"))
    }
    @Test fun metaReleaseIsConsumedAfterAnOwnedShortcut() {
        val guard = MetaReleaseGuard()
        guard.down(43, 117)
        guard.chord(43)
        assertTrue(guard.up(43, 117))
        guard.down(43, 117)
        assertFalse(guard.up(43, 117))
    }
    @Test fun plainMetaAndOtherDevicesArePreserved() {
        val guard = MetaReleaseGuard()
        guard.down(43, 117)
        guard.chord(43)
        guard.down(44, 117)
        assertFalse(guard.up(44, 117))
        assertTrue(guard.up(43, 117))
    }
    @Test fun bothMetaReleasesAreConsumedUntilTheChordEnds() {
        val guard = MetaReleaseGuard()
        guard.down(43, 117)
        guard.down(43, 118)
        guard.chord(43)
        assertTrue(guard.up(43, 117))
        assertTrue(guard.up(43, 118))
    }
    @Test fun interruptedInputDoesNotLeaveAStaleMetaChord() {
        val guard = MetaReleaseGuard()
        guard.chord(43)
        guard.clear()
        assertFalse(guard.up(43, 117))
    }
    @Test fun ctrlAltARequiresTheExactModifiers() {
        val modifiers = ShortcutRules.CTRL or ShortcutRules.ALT
        assertTrue(ShortcutRules.matches(29, modifiers, 29, modifiers))
        assertFalse(ShortcutRules.matches(29, modifiers or ShortcutRules.META, 29, modifiers))
        assertFalse(ShortcutRules.matches(29, ShortcutRules.CTRL, 29, modifiers))
    }
}
