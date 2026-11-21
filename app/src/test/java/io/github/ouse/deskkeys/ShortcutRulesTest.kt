package io.github.ouse.deskkeys

import org.junit.Assert.*
import org.junit.Test

class ShortcutRulesTest {
    @Test fun digitOrderMatchesTaskbarSlots() {
        for (digit in 1..9) assertEquals(digit - 1, ShortcutRules.taskbarIndex(digit, ShortcutRules.META))
    }
    @Test fun otherModifierCombinationsDoNotSwitchApplications() {
        for (mask in listOf(0, ShortcutRules.SHIFT, ShortcutRules.META or ShortcutRules.SHIFT, ShortcutRules.META or ShortcutRules.CTRL)) {
            assertNull(ShortcutRules.taskbarIndex(1, mask))
        }
        assertNull(ShortcutRules.taskbarIndex(null, ShortcutRules.META))
        assertNull(ShortcutRules.taskbarIndex(0, ShortcutRules.META))
    }
    @Test fun screenshotShortcutRequiresTheExactCombination() {
        val modifiers = ShortcutRules.META or ShortcutRules.SHIFT
        assertTrue(ShortcutRules.matches(47, modifiers, 47, modifiers))
        assertFalse(ShortcutRules.matches(47, ShortcutRules.META, 47, modifiers))
        assertFalse(ShortcutRules.matches(47, modifiers or ShortcutRules.CTRL, 47, modifiers))
        assertFalse(ShortcutRules.matches(48, modifiers, 47, modifiers))
    }
}
