package io.github.ouse.deskkeys

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

object DeskState {
    val connected = mutableStateOf(false)
    val message = mutableStateOf("请启用桌面快捷键服务")
    fun prefs(context: Context) = context.getSharedPreferences("desk_keys", Context.MODE_PRIVATE)
    fun ensureDefaults(context: Context) {
        val preferences = prefs(context)
        if (preferences.getInt("defaults_version", 0) < 2) {
            preferences.edit().putInt("screenshot_key", KeyEvent.KEYCODE_A)
                .putInt("screenshot_modifiers", ShortcutRules.CTRL or ShortcutRules.ALT)
                .putInt("defaults_version", 2).apply()
        }
    }
}

object TaskbarRules {
    private val runningSuffix = Regex("\\s+(活跃|正在运行|active|running)$", RegexOption.IGNORE_CASE)
    private val notificationLabel = Regex("^[“\"](.+)[”\"]\\s*有\\s*[\\d,，]+\\s*条通知$")
    fun appLabel(label: String): String {
        val name = label.trim().replace(runningSuffix, "")
        return (notificationLabel.matchEntire(name)?.groupValues?.get(1) ?: name).trim().replace(runningSuffix, "")
    }
    private val controls = setOf("应用列表", "全部应用", "所有应用", "应用抽屉", "搜索", "开始", "任务栏分隔线",
        "all apps", "app list", "search", "start", "home", "back", "recent apps")
    private val controlIds = setOf("all_apps_button", "start_button", "home", "back", "recent_apps", "ime_switcher", "accessibility_button", "search", "search_box", "taskbar_search")
    fun isAppIcon(label: String, id: String): Boolean = label.trim().isNotEmpty() &&
        label.trim().lowercase() !in controls && id.substringAfterLast('/') !in controlIds
}

class MetaReleaseGuard {
    private val pressed = mutableMapOf<Int, MutableSet<Int>>()
    private val used = mutableSetOf<Int>()
    fun down(device: Int, key: Int) {
        val keys = pressed.getOrPut(device) { mutableSetOf() }
        if (keys.isEmpty()) used.remove(device)
        keys.add(key)
    }
    fun chord(device: Int) { used.add(device) }
    fun up(device: Int, key: Int): Boolean {
        val consume = device in used
        val keys = pressed[device]
        keys?.remove(key)
        if (keys.isNullOrEmpty()) { pressed.remove(device); used.remove(device) }
        return consume
    }
    fun clear() { pressed.clear(); used.clear() }
}

object ShortcutRules {
    const val META = 1
    const val SHIFT = 2
    const val CTRL = 4
    const val ALT = 8
    fun matches(actualKey: Int, actualModifiers: Int, key: Int, modifiers: Int) =
        actualKey == key && actualModifiers == modifiers
    fun taskbarIndex(digit: Int?, modifiers: Int): Int? =
        if (modifiers == META && digit != null && digit in 1..9) digit - 1 else null
}

class DesktopHistory(initialApp: String? = null) {
    var lastApp: String? = initialApp
        private set
    fun observe(packageName: String?, home: Boolean, applicationWindow: Boolean): Boolean {
        if (!applicationWindow || home || packageName.isNullOrBlank() || packageName == "com.android.systemui" || packageName == lastApp) return false
        lastApp = packageName
        return true
    }
}
