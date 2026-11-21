package io.github.ouse.deskkeys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owned by the accessibility service. Indexes on demand; no timers or polling. */
class AppLabels(private val context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var index: AppLabelIndex? = null
    private var revision = 0
    private var loading = false
    private var closed = false
    private var pending: Pair<String, (String?) -> Unit>? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { invalidate() }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun invalidate() {
        revision++
        index = null
        pending = null
    }

    fun resolve(label: String, callback: (String?) -> Unit) {
        if (closed) return
        index?.let { callback(it.find(label)); return }
        // Keep only the latest cold-cache key request instead of queuing old shortcuts.
        pending = label to callback
        if (loading) return
        loading = true
        val startedRevision = revision
        worker.execute {
            val loaded = try {
                val manager = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val entries = manager.queryIntentActivities(intent, 0).flatMap {
                    val pkg = it.activityInfo.packageName
                    listOf(it.loadLabel(manager).toString() to pkg,
                        manager.getApplicationLabel(it.activityInfo.applicationInfo).toString() to pkg)
                }
                AppLabelIndex(entries)
            } catch (_: RuntimeException) { null }
            main.post {
                loading = false
                if (closed || startedRevision != revision) return@post
                index = loaded
                val request = pending
                pending = null
                request?.second?.invoke(loaded?.find(request.first))
            }
        }
    }

    override fun close() {
        closed = true
        invalidate()
        context.unregisterReceiver(receiver)
        worker.shutdownNow()
    }
}
