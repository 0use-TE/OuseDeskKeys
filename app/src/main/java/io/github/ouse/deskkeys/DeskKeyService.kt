package io.github.ouse.deskkeys

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.FileDescriptor
import java.io.PrintWriter

class DeskKeyService : AccessibilityService() {
    private val prefs by lazy { DeskState.prefs(this) }
    private var appLabels: AppLabels? = null
    private var labelLocale = ""
    private val held = mutableSetOf<Pair<Int, Int>>()
    private val metaReleaseGuard = MetaReleaseGuard()
    private var lastScreenshotTime = -1000L
    private val desktopHistory by lazy { DesktopHistory(prefs.getString("last_app", null)) }
    private val homePackages = setOf("com.zui.launcher", "com.zui.desktoplauncher", "com.android.launcher3")
    private val taskbarContainerIds = setOf("taskbar_view", "taskbar", "taskbar_icons", "taskbar_apps")
    private data class Icon(val node: AccessibilityNodeInfo, val label: String, val bounds: Rect)

    override fun onServiceConnected() {
        WindowBridge.initialize(this)
        if (appLabels == null) appLabels = AppLabels(this)
        labelLocale = resources.configuration.locales.toLanguageTags()
        DeskState.ensureDefaults(this)
        prefs.edit().putBoolean("recording", false).apply()
        DeskState.connected.value = true
        report("桌面快捷键服务已连接", false)
        showNotification()
        if (prefs.getBoolean("desktop_enabled", true) && isWorkstationMode()) rememberForegroundApp()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Observe input-focus changes only; scrolling and animations need no work.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_FOCUSED == 0) return
        if (prefs.getBoolean("desktop_enabled", true) && isWorkstationMode()) rememberForegroundApp()
    }

    private fun findIcons(): List<Icon> {
        val found = mutableListOf<Icon>()
        var visited = 0
        fun collect(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 18 || ++visited > 512) return
            // Launcher descriptions may include badge counts; TextView text is the app title.
            val label = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim().orEmpty()
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            if (node.isVisibleToUser && node.isClickable && TaskbarRules.isAppIcon(label, id)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                found += Icon(node, label, bounds)
                return
            }
            for (i in 0 until node.childCount) {
                if (visited >= 512) break
                node.getChild(i)?.let { collect(it, depth + 1) }
            }
        }
        fun locate(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 18 || ++visited > 512) return
            val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
            if (id in taskbarContainerIds) { collect(node); return }
            for (i in 0 until node.childCount) {
                if (visited >= 512) break
                node.getChild(i)?.let { locate(it, depth + 1) }
            }
        }
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() !in homePackages || window.displayId != Display.DEFAULT_DISPLAY) continue
            val title = window.title?.toString().orEmpty()
            if (title.contains("Taskbar", ignoreCase = true) || title.contains("任务栏")) collect(root) else locate(root)
        }
        return found.distinctBy { it.bounds to it.label }
            .sortedWith(compareBy<Icon> { it.bounds.centerX() }.thenBy { it.bounds.centerY() })
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (prefs.getBoolean("recording", false)) {
            held.clear()
            metaReleaseGuard.clear()
            return false
        }
        val id = event.deviceId to event.keyCode
        if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) metaReleaseGuard.down(event.deviceId, event.keyCode)
            if (event.action == KeyEvent.ACTION_UP) return metaReleaseGuard.up(event.deviceId, event.keyCode)
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) return held.remove(id)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (id in held) return true
        val modifiers = modifiers(event)
        val screenshot = prefs.getBoolean("screenshot_enabled", true) && ShortcutRules.matches(event.keyCode, modifiers,
            prefs.getInt("screenshot_key", KeyEvent.KEYCODE_A), prefs.getInt("screenshot_modifiers", ShortcutRules.CTRL or ShortcutRules.ALT))
        val digit = when (event.keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_1 + 1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_1 + 1
            else -> null
        }
        val index = if (prefs.getBoolean("switch_enabled", true)) ShortcutRules.taskbarIndex(digit, modifiers) else null
        val desktop = prefs.getBoolean("desktop_enabled", true) && modifiers == ShortcutRules.META && event.keyCode == KeyEvent.KEYCODE_D
        val requestedAction = WindowActions.shortcut(event.keyCode, modifiers)
        val windowAction = requestedAction?.takeIf {
            prefs.getBoolean(if (it == WindowActions.CLOSE) "close_enabled" else "minimize_enabled", true) && WindowBridge.canPerform(it)
        }
        if (!screenshot && index == null && !desktop && windowAction == null) return false
        // Ordinary typing never queries system settings or accessibility windows.
        if (!screenshot && !isWorkstationMode()) return false
        // An unavailable taskbar must neither show a toast nor consume another service's shortcut.
        val slot = index
        val icons = if (!screenshot && slot != null) findIcons() else emptyList()
        if (!screenshot && slot != null && icons.getOrNull(slot) == null) return false
        if (!screenshot && getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
        held += id
        if (event.isMetaPressed) metaReleaseGuard.chord(event.deviceId)
        if (event.repeatCount != 0) return true
        log("shortcut key=${event.keyCode} modifiers=$modifiers device=${event.deviceId}")
        if (screenshot) requestSystemScreenshot()
        else if (windowAction != null) WindowBridge.perform(windowAction)
        else if (desktop) toggleDesktop()
        else clickTaskbarIcon(icons, index!!)
        return true
    }

    private fun freshTaskbarIcon(index: Int, label: String, requestedAt: Long): Icon? {
        if (!DeskState.connected.value || !isWorkstationMode() || !prefs.getBoolean("switch_enabled", true) ||
            prefs.getBoolean("recording", false) || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !WindowActions.freshRequest(requestedAt, android.os.SystemClock.elapsedRealtime())) return null
        return findIcons().getOrNull(index)?.takeIf { TaskbarRules.appLabel(it.label) == label }
    }

    private fun clickTaskbarIcon(icons: List<Icon>, index: Int) {
        val icon = icons.getOrNull(index) ?: return
        if (!prefs.getBoolean("minimize_enabled", true) || !WindowBridge.canPerform(WindowActions.MINIMIZE)) {
            clickIcon(icon)
            return
        }
        val label = TaskbarRules.appLabel(icon.label)
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        appLabels?.resolve(label) { target ->
            val fresh = freshTaskbarIcon(index, label, requestedAt) ?: return@resolve
            log("taskbar slot=${index + 1} label=$label package=$target")
            if (target != null && prefs.getBoolean("minimize_enabled", true) && WindowBridge.canPerform(WindowActions.MINIMIZE)) {
                WindowBridge.perform(WindowActions.TOGGLE_TASKBAR, target, requestedAt) {
                    freshTaskbarIcon(index, label, requestedAt)?.let(::clickIcon)
                }
            } else clickIcon(fresh)
        }
    }

    private fun clickIcon(icon: Icon) {
        if (icon.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) report("已点击任务栏：${icon.label}", false)
        else log("taskbar icon click unavailable")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val locale = newConfig.locales.toLanguageTags()
        if (locale != labelLocale) {
            labelLocale = locale
            appLabels?.invalidate()
        }
    }

    private fun isWorkstationMode(): Boolean = try {
        Settings.System.getInt(contentResolver, "zui_ov_desktop_mode", 0) == 1
    } catch (_: SecurityException) { false }

    private fun foregroundPackage(): String? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.displayId == Display.DEFAULT_DISPLAY }
        return (apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive })?.root?.packageName?.toString()
    }

    private fun rememberForegroundApp() {
        val current = foregroundPackage()
        if (desktopHistory.observe(current, current in homePackages, current != null)) {
            prefs.edit().putString("last_app", desktopHistory.lastApp).apply()
        }
    }

    private fun toggleDesktop() {
        val current = foregroundPackage()
        if (current == null) { report("暂时无法识别当前应用，请稍后重试"); return }
        if (current !in homePackages) {
            rememberForegroundApp()
            if (performGlobalAction(GLOBAL_ACTION_HOME)) report("已显示桌面，再按 Win＋D 返回上次应用", false)
            else report("系统未接受显示桌面请求")
            return
        }
        val target = desktopHistory.lastApp
        if (target.isNullOrBlank()) { report("还没有记录到上次应用，请先打开一次应用"); return }
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(target, 0)).toString() } catch (_: Exception) { null }
        val matchingIcons = if (label == null) emptyList() else findIcons().filter {
            TaskbarRules.appLabel(it.label) == label
        }
        if (matchingIcons.size == 1 && matchingIcons[0].node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            report("已返回上次应用", false)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch == null) { report("上次应用当前没有可用的启动入口"); return }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            report("已请求返回上次应用", false)
        } catch (_: RuntimeException) { report("系统未允许切回上次应用，请从任务栏打开") }
    }

    private fun requestSystemScreenshot() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastScreenshotTime < 600) return
        lastScreenshotTime = now
        if (performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)) report("已请求系统全屏截图", false)
        else report("系统暂未接受全屏截图请求，请检查无障碍权限")
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("desk_keys", "桌面快捷键状态", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "desk_keys").setSmallIcon(R.drawable.ic_keys)
            .setContentTitle("OuseDeskKeys 已运行").setContentText("Meta＋数字切换应用 · 自定义全屏截图")
            .setContentIntent(open).setOngoing(true).build()
        try { manager.notify(1, notification) } catch (_: SecurityException) { }
    }

    private fun report(message: String, toast: Boolean = true) {
        DeskState.message.value = message
        log(message)
        if (toast) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun log(message: String) {
        DeskLog.record(message)
    }
    override fun dump(fd: FileDescriptor, out: PrintWriter, args: Array<out String>) {
        out.println("OuseDeskKeys connected=${DeskState.connected.value}")
        out.println("Shizuku connected=${WindowBridge.connected.value} status=${WindowBridge.status.value}")
        DeskLog.snapshot().forEach(out::println)
        fun tree(node: AccessibilityNodeInfo, depth: Int = 0) {
            if (depth > 16) return
            val bounds = Rect().also(node::getBoundsInScreen)
            out.println("${" ".repeat(depth)}${node.viewIdResourceName} label=${node.contentDescription ?: node.text} click=${node.isClickable} visible=${node.isVisibleToUser} bounds=$bounds")
            for (i in 0 until node.childCount) node.getChild(i)?.let { tree(it, depth + 1) }
        }
        for (window in windows) {
            val root = window.root ?: continue
            out.println("window title=${window.title} type=${window.type} package=${root.packageName}")
            val title = window.title?.toString().orEmpty()
            if (root.packageName?.toString() in setOf("com.zui.launcher", "com.zui.desktoplauncher") &&
                (title.contains("Taskbar", ignoreCase = true) || title.contains("任务栏"))) tree(root)
        }
    }
    override fun onInterrupt() { held.clear(); metaReleaseGuard.clear() }
    override fun onDestroy() {
        appLabels?.close()
        appLabels = null
        held.clear()
        metaReleaseGuard.clear()
        DeskState.connected.value = false
        getSystemService(NotificationManager::class.java).cancel(1)
        super.onDestroy()
    }
    companion object {
        fun modifiers(event: KeyEvent): Int = (if (event.isMetaPressed) ShortcutRules.META else 0) or
            (if (event.isShiftPressed) ShortcutRules.SHIFT else 0) or (if (event.isCtrlPressed) ShortcutRules.CTRL else 0) or
            (if (event.isAltPressed) ShortcutRules.ALT else 0)
    }
}
