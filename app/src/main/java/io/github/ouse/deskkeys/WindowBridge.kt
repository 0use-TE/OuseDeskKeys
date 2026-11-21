package io.github.ouse.deskkeys

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import rikka.shizuku.Shizuku
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object WindowBridge {
    val connected = mutableStateOf(false)
    val connecting = mutableStateOf(false)
    val status = mutableStateOf("Shizuku 未连接")
    val capabilities = mutableIntStateOf(0)
    private lateinit var context: Application
    private var initialized = false
    private val session = BridgeSession()
    private var token = 0L
    private var connection: ServiceConnection? = null
    private var death: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var awaitingPermission = false
    private var remote: IWindowControl? = null
    private var authorized = false
    private val main = Handler(Looper.getMainLooper())
    private var connectionTimeout: Runnable? = null
    private var operationTimeout: Runnable? = null
    // Recovery has its own worker, so a blocked window RPC cannot block helper teardown.
    // Both workers expire when idle; there is no polling or automatic action retry.
    private val rpcWorker = idleWorker()
    private val controlWorker = idleWorker()
    private val rpcCalls = AtomicInteger()
    private val controlCalls = AtomicInteger()
    private val args get() = Shizuku.UserServiceArgs(ComponentName(context, WindowControlService::class.java))
        .daemon(false).processNameSuffix("window_control").debuggable(false).version(11)

    private fun idleWorker() = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
    private fun control(block: () -> Unit) {
        controlCalls.incrementAndGet()
        controlWorker.execute { try { block() } finally { controlCalls.decrementAndGet() } }
    }
    private fun rpc(block: () -> Unit) {
        rpcCalls.incrementAndGet()
        rpcWorker.execute { try { block() } finally { rpcCalls.decrementAndGet() } }
    }
    private fun optedIn() = DeskState.prefs(context).getBoolean("shizuku_enabled", false)
    // Only called on workers: permission Binder calls must not block keyboard filtering.
    private fun permitted(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    private fun release(old: ServiceConnection, remove: Boolean) = control {
        try { Shizuku.unbindUserService(args, old, remove) } catch (_: Exception) { }
    }
    private fun clear(message: String) {
        val old = connection
        connection = null
        session.invalidate()
        connectionTimeout?.let(main::removeCallbacks)
        operationTimeout?.let(main::removeCallbacks)
        connectionTimeout = null
        operationTimeout = null
        death?.let { (binder, recipient) ->
            try { binder.unlinkToDeath(recipient, 0) } catch (_: Exception) { }
        }
        death = null
        remote = null
        authorized = false
        awaitingPermission = false
        capabilities.intValue = 0
        connected.value = false
        connecting.value = false
        status.value = message
        DeskLog.record(message)
        if (old != null) release(old, true)
    }

    private fun armConnectionTimeout(generation: Long) {
        connectionTimeout?.let(main::removeCallbacks)
        connectionTimeout = Runnable {
            if (session.accepts(generation)) clear("Shizuku 连接超时，已停止此次连接，请重试")
        }.also { main.postDelayed(it, 12000) }
    }

    fun initialize(value: Context) {
        if (initialized) return
        context = value.applicationContext as Application
        initialized = true
        Shizuku.addBinderDeadListener { clear("Shizuku 已停止") }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == 1701 && awaitingPermission && session.accepts(token)) {
                awaitingPermission = false
                if (result == PackageManager.PERMISSION_GRANTED && optedIn()) {
                    authorized = true
                    bind(token)
                } else clear("Shizuku 未授权")
            }
        }
        Shizuku.addBinderReceivedListenerSticky {
            if (optedIn() && !connected.value && !connecting.value) startConnection(false)
        }
    }

    fun connect() {
        DeskState.prefs(context).edit().putBoolean("shizuku_enabled", true).apply()
        startConnection(true)
    }
    private fun startConnection(requestPermission: Boolean) {
        if (connected.value || connecting.value) return
        // Do not accumulate replacement threads if the system or Shizuku itself is unresponsive.
        if (rpcCalls.get() != 0 || controlCalls.get() != 0) {
            status.value = "正在回收旧连接；若长时间未恢复，请重启 Shizuku 后重连"
            return
        }
        token = session.open()
        val generation = token
        connecting.value = true
        status.value = "正在连接 Shizuku"
        armConnectionTimeout(generation)
        control {
            val available = try { Shizuku.pingBinder() } catch (_: Exception) { false }
            val granted = available && permitted()
            main.post {
                if (!session.accepts(generation)) return@post
                when {
                    !available -> clear("请先在 Shizuku 中启动服务")
                    granted -> { authorized = true; bind(generation) }
                    !requestPermission -> clear("Shizuku 未授权，请点击连接")
                    else -> {
                        awaitingPermission = true
                        status.value = "等待 Shizuku 授权"
                        control {
                            val sent = try { Shizuku.requestPermission(1701); true } catch (_: Exception) { false }
                            main.post {
                                if (!session.accepts(generation) || !awaitingPermission) return@post
                                if (!sent) clear("无法申请 Shizuku 授权")
                                else {
                                    // User input has no deadline; the UI offers Cancel while awaiting permission.
                                    connectionTimeout?.let(main::removeCallbacks)
                                    connectionTimeout = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bind(generation: Long) {
        if (!session.accepts(generation) || !optedIn()) return
        armConnectionTimeout(generation)
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (!session.accepts(generation) || connection !== this || !optedIn()) {
                    release(this, false) // A late callback must not remove a replacement helper.
                    return
                }
                if (binder == null || !binder.isBinderAlive) { clear("Shizuku 连接失败"); return }
                if (remote != null) return
                val recipient = IBinder.DeathRecipient {
                    main.post { if (session.accepts(generation)) clear("Shizuku 窗口服务已断开") }
                }
                try { binder.linkToDeath(recipient, 0) }
                catch (_: Exception) { clear("Shizuku 窗口服务已断开"); return }
                death = binder to recipient
                val service = IWindowControl.Stub.asInterface(binder)
                remote = service
                rpc {
                    val mask = try { if (permitted()) service.capabilities() else 0 } catch (_: Exception) { 0 }
                    main.post {
                        if (!session.accepts(generation) || remote !== service) return@post
                        if (mask == 0) { clear("当前系统接口不可用或 Shizuku 未授权"); return@post }
                        if (!session.markReady(generation)) return@post
                        // Keep the watchdog through capabilities(), not merely through Binder delivery.
                        connectionTimeout?.let(main::removeCallbacks)
                        connectionTimeout = null
                        capabilities.intValue = mask
                        connected.value = true
                        connecting.value = false
                        status.value = "Shizuku 已连接"
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (session.accepts(generation)) clear("Shizuku 窗口服务已断开")
            }
        }
        connection = callback
        control {
            try {
                if (session.accepts(generation) && optedIn()) Shizuku.bindUserService(args, callback)
            } catch (_: Exception) {
                main.post { if (session.accepts(generation)) clear("Shizuku 连接失败，请重试") }
            }
        }
    }

    fun disconnect() {
        DeskState.prefs(context).edit().putBoolean("shizuku_enabled", false).apply()
        clear("Shizuku 未连接")
    }
    // Constant-time cached check on the keyboard thread; revalidate permission before each worker RPC.
    fun canPerform(action: Int): Boolean = connected.value && authorized && remote?.asBinder()?.isBinderAlive == true &&
        capabilities.intValue and WindowActions.requiredCapability(action) != 0

    fun refresh() {
        if ((!connected.value && !connecting.value) || controlCalls.get() != 0) return
        val generation = token
        control {
            val valid = permitted()
            main.post {
                if (session.accepts(generation) && !valid) clear("Shizuku 已断开或授权已撤销")
            }
        }
    }

    fun perform(action: Int, targetPackage: String? = null, requestedAt: Long = SystemClock.elapsedRealtime(),
                onNotFocused: (() -> Unit)? = null) {
        if (!canPerform(action) || !WindowActions.freshRequest(requestedAt, SystemClock.elapsedRealtime())) return
        val service = remote ?: return
        val operation = session.begin() ?: return
        operationTimeout = Runnable {
            if (session.finish(operation)) clear("窗口操作超时，已断开旧服务，请重新连接 Shizuku")
        }.also { main.postDelayed(it, 1500) }
        rpc {
            var lost = false
            val result = try {
                if (!session.accepts(operation.generation) || !optedIn()) return@rpc
                if (!permitted()) { lost = true; "Shizuku 已断开或授权已撤销" }
                else service.performWindow(action, targetPackage, requestedAt)
            } catch (_: Exception) { lost = true; "Shizuku 通信失败，请重新连接" }
            main.post {
                if (!session.finish(operation)) return@post
                operationTimeout?.let(main::removeCallbacks)
                operationTimeout = null
                DeskLog.record("window action=$action package=$targetPackage result=${result.ifEmpty { "accepted" }}")
                if (lost) { clear(result); return@post }
                if (result == WindowActions.NOT_FOCUSED) {
                    if (WindowActions.freshRequest(requestedAt, SystemClock.elapsedRealtime())) onNotFocused?.invoke()
                } else if (result.isNotEmpty()) {
                    status.value = result
                    android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
