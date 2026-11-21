package io.github.ouse.deskkeys

import android.app.TaskInfo
import android.os.SystemClock
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException

/** Instantiated by Shizuku under shell/root identity, not an Android Service. */
@Keep
class WindowControlService : IWindowControl.Stub() {
    private val managerClass = Class.forName("android.app.ActivityTaskManager")
    private val manager = managerClass.getMethod("getInstance").invoke(null)
    private val remote = managerClass.getMethod("getService").invoke(null)!!
    private val remoteClass = Class.forName("android.app.IActivityTaskManager")
    private val getTasks = managerClass.getMethod("getTasks", Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val focusedField = TaskInfo::class.java.getField("isFocused")
    private val visibleField = TaskInfo::class.java.getField("isVisible")
    private val displayField = TaskInfo::class.java.getField("displayId")
    private val getActivityType = TaskInfo::class.java.getMethod("getActivityType")
    private val close = remoteClass.getMethod("removeTask", Int::class.javaPrimitiveType)
    private val minimize = try {
        remoteClass.getMethod("minimizeRootTask", Int::class.javaPrimitiveType)
    } catch (_: NoSuchMethodException) { null }

    override fun capabilities(): Int {
        getTasks.invoke(manager, 1, false, false, 0) // Read-only permission check.
        return WindowActions.CLOSE or (if (minimize != null) WindowActions.MINIMIZE else 0)
    }

    private fun focusedTask(): TaskInfo? {
        val tasks = getTasks.invoke(manager, 100, false, false, 0) as? List<*> ?: return null
        val task = tasks.filterIsInstance<TaskInfo>().firstOrNull {
            displayField.getInt(it) == 0 && focusedField.getBoolean(it) && visibleField.getBoolean(it)
        } ?: return null
        return task
    }

    @Synchronized
    override fun performWindow(action: Int, targetPackage: String?, requestedAt: Long): String {
        return try {
            if (!WindowActions.freshRequest(requestedAt, SystemClock.elapsedRealtime())) return "操作已过期，请重新按快捷键"
            val method = when (action) {
                WindowActions.CLOSE -> close
                WindowActions.MINIMIZE, WindowActions.TOGGLE_TASKBAR -> minimize ?: return "当前系统没有可用的最小化接口"
                else -> return "未知窗口操作"
            }
            val focused = focusedTask() ?: return "当前没有可操作的聚焦应用窗口"
            android.util.Log.i("OuseWindowControl", "action=$action target=$targetPackage task=${focused.taskId} top=${focused.topActivity?.packageName} base=${focused.baseActivity?.packageName}")
            if (action == WindowActions.TOGGLE_TASKBAR) {
                if (targetPackage.isNullOrBlank()) return "缺少目标应用"
                if (!WindowActions.matchesTarget(targetPackage, focused.topActivity?.packageName,
                        focused.baseActivity?.packageName)) return WindowActions.NOT_FOCUSED
            } else if (targetPackage != null) return "不支持此窗口操作"
            if (!WindowActions.allowedTarget(getActivityType.invoke(focused) as Int,
                    focused.topActivity?.packageName.orEmpty())) return "当前窗口不支持此操作"
            val task = focused
            // Never apply a delayed request after focus has moved elsewhere.
            if (focusedTask()?.taskId != focused.taskId) return "窗口焦点已变化，请重试"
            if (!WindowActions.freshRequest(requestedAt, SystemClock.elapsedRealtime())) return "操作已过期，请重新按快捷键"
            val result = method.invoke(remote, task.taskId)
            if (result == false) "系统未接受窗口操作" else ""
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            if (cause is SecurityException) "系统未授予此窗口操作权限" else "窗口操作失败：${cause.javaClass.simpleName}"
        }
    }

    override fun destroy() { kotlin.system.exitProcess(0) }
}
