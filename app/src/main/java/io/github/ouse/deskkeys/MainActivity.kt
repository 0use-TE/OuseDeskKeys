package io.github.ouse.deskkeys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private var recording by mutableStateOf(false)
    private var shortcutName by mutableStateOf("")
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeskState.ensureDefaults(this)
        WindowBridge.initialize(this)
        updateShortcutName()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            BackHandler(enabled = recording) {
                recording = false
                DeskState.prefs(this).edit().putBoolean("recording", false).apply()
            }
            var switchApps by remember { mutableStateOf(DeskState.prefs(this).getBoolean("switch_enabled", true)) }
            var desktop by remember { mutableStateOf(DeskState.prefs(this).getBoolean("desktop_enabled", true)) }
            var screenshots by remember { mutableStateOf(DeskState.prefs(this).getBoolean("screenshot_enabled", true)) }
            var closeWindow by remember { mutableStateOf(DeskState.prefs(this).getBoolean("close_enabled", true)) }
            var minimizeWindow by remember { mutableStateOf(DeskState.prefs(this).getBoolean("minimize_enabled", true)) }
            var theme by remember { mutableIntStateOf(DeskState.prefs(this).getInt("theme", 0)) }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            val palette = if (dark) darkColorScheme(primary = Color(0xFF78E4CB), background = Color(0xFF0C1420), surface = Color(0xFF162332), surfaceVariant = Color(0xFF1C3041))
                else lightColorScheme(primary = Color(0xFF08786B), background = Color(0xFFF3F6F8), surface = Color.White, surfaceVariant = Color(0xFFE8F1EF))
            MaterialTheme(colorScheme = palette) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("OuseDeskKeys", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { theme = (theme + 1) % 3; DeskState.prefs(this@MainActivity).edit().putInt("theme", theme).apply() }) {
                                Text(listOf("自动", "浅色", "深色")[theme], fontSize = 20.sp)
                            }
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().clickable { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (DeskState.connected.value) "●  正在运行" else "○  尚未启用", fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                Text("设置 ›", fontSize = 20.sp)
                            }
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("任务栏切换", fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Switch(switchApps, { switchApps = it; DeskState.prefs(this@MainActivity).edit().putBoolean("switch_enabled", it).apply() })
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Win＋D 切换桌面", fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Switch(desktop, { desktop = it; DeskState.prefs(this@MainActivity).edit().putBoolean("desktop_enabled", it).apply() })
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("全屏截图", fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    Switch(screenshots, { screenshots = it; DeskState.prefs(this@MainActivity).edit().putBoolean("screenshot_enabled", it).apply() })
                                }
                                OutlinedButton(onClick = {
                                    recording = !recording
                                    DeskState.prefs(this@MainActivity).edit().putBoolean("recording", recording).apply()
                                }) { Text(if (recording) "按下组合键 · 点此取消" else shortcutName, fontSize = 22.sp) }
                            }
                        }
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(WindowBridge.status.value, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                                OutlinedButton(onClick = {
                                    if (WindowBridge.connected.value || WindowBridge.connecting.value) WindowBridge.disconnect() else WindowBridge.connect()
                                }) { Text(if (WindowBridge.connected.value) "断开 Shizuku" else if (WindowBridge.connecting.value) "取消连接" else "连接 Shizuku", fontSize = 22.sp) }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("关闭窗口 · Ctrl＋Alt＋W", fontSize = 22.sp, modifier = Modifier.weight(1f))
                                    Switch(WindowBridge.connected.value && closeWindow,
                                        { closeWindow = it; DeskState.prefs(this@MainActivity).edit().putBoolean("close_enabled", it).apply() },
                                        enabled = WindowBridge.capabilities.intValue and WindowActions.CLOSE != 0)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("最小化 · Ctrl＋Alt＋M", fontSize = 22.sp, modifier = Modifier.weight(1f))
                                    Switch(WindowBridge.capabilities.intValue and WindowActions.MINIMIZE != 0 && minimizeWindow,
                                        { minimizeWindow = it; DeskState.prefs(this@MainActivity).edit().putBoolean("minimize_enabled", it).apply() },
                                        enabled = WindowBridge.capabilities.intValue and WindowActions.MINIMIZE != 0)
                                }
                                Text("Win＋数字：切换／最小化任务栏应用", fontSize = 20.sp)
                            }
                        }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("后台运行设置", fontSize = 20.sp)
                        }
                    }
                    }
                }
            }
        }
    }
    // Hardware Back must bypass shortcut recording; gestures use BackHandler above.
    @SuppressLint("GestureBackNavigation")
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!recording) return super.onKeyDown(keyCode, event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.onKeyDown(keyCode, event)
        }
        if (event.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(event.keyCode)) return true
        val modifiers = DeskKeyService.modifiers(event)
        if (modifiers == 0 && event.keyCode != KeyEvent.KEYCODE_SYSRQ) {
            DeskState.message.value = "请使用组合键，或单独使用 Print Screen"
            android.widget.Toast.makeText(this, DeskState.message.value, android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        val digit = event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 || event.keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9
        if ((modifiers == ShortcutRules.META && (digit || event.keyCode == KeyEvent.KEYCODE_D)) ||
            WindowActions.shortcut(event.keyCode, modifiers) != null) {
            DeskState.message.value = "此组合已用于桌面快捷键，请选择其他组合"
            android.widget.Toast.makeText(this, DeskState.message.value, android.widget.Toast.LENGTH_SHORT).show()
            return true
        }
        DeskState.prefs(this).edit().putInt("screenshot_key", event.keyCode).putInt("screenshot_modifiers", modifiers).putBoolean("recording", false).apply()
        recording = false
        updateShortcutName()
        return true
    }
    private fun updateShortcutName() {
        val prefs = DeskState.prefs(this)
        val mask = prefs.getInt("screenshot_modifiers", ShortcutRules.CTRL or ShortcutRules.ALT)
        shortcutName = buildList {
            if (mask and ShortcutRules.META != 0) add("Meta")
            if (mask and ShortcutRules.CTRL != 0) add("Ctrl")
            if (mask and ShortcutRules.ALT != 0) add("Alt")
            if (mask and ShortcutRules.SHIFT != 0) add("Shift")
            add(KeyEvent.keyCodeToString(prefs.getInt("screenshot_key", KeyEvent.KEYCODE_A)).removePrefix("KEYCODE_"))
        }.joinToString("＋")
    }
    override fun onPause() {
        recording = false
        DeskState.prefs(this).edit().putBoolean("recording", false).apply()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        WindowBridge.refresh()
    }
}
