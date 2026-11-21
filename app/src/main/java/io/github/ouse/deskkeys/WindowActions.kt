package io.github.ouse.deskkeys

object WindowActions {
    const val CLOSE = 1
    const val MINIMIZE = 2
    const val TOGGLE_TASKBAR = 4
    const val NOT_FOCUSED = "@not-focused"
    fun requiredCapability(action: Int): Int = if (action == TOGGLE_TASKBAR) MINIMIZE else action
    fun matchesTarget(target: String, topPackage: String?, basePackage: String?): Boolean =
        target.isNotBlank() && (target == topPackage || target == basePackage)
    fun shortcut(key: Int, modifiers: Int): Int? = if (modifiers != (ShortcutRules.CTRL or ShortcutRules.ALT)) null else when (key) {
        android.view.KeyEvent.KEYCODE_W -> CLOSE
        android.view.KeyEvent.KEYCODE_M -> MINIMIZE
        else -> null
    }
    fun allowedTarget(activityType: Int, packageName: String): Boolean = activityType == 1 && packageName.isNotBlank() &&
        packageName !in setOf("com.android.systemui", "com.zui.launcher", "com.zui.desktoplauncher", "com.android.launcher3")
    fun freshRequest(requestedAt: Long, now: Long): Boolean = now >= requestedAt && now - requestedAt <= 750
}
