package io.github.ouse.deskkeys

/** Tokens isolate late Binder callbacks from a disconnected or replacement session. */
class BridgeSession {
    data class Operation(val generation: Long, val sequence: Long)
    private var generation = 0L
    private var active = false
    private var ready = false
    private var sequence = 0L
    private var pending: Operation? = null

    @Synchronized fun open(): Long {
        generation++
        active = true
        ready = false
        pending = null
        return generation
    }
    @Synchronized fun accepts(token: Long): Boolean = active && generation == token
    @Synchronized fun markReady(token: Long): Boolean {
        if (!accepts(token) || ready) return false
        ready = true
        return true
    }
    @Synchronized fun begin(): Operation? {
        if (!active || !ready || pending != null) return null
        return Operation(generation, ++sequence).also { pending = it }
    }
    @Synchronized fun finish(operation: Operation): Boolean {
        if (!accepts(operation.generation) || pending != operation) return false
        pending = null
        return true
    }
    @Synchronized fun invalidate() {
        active = false
        ready = false
        pending = null
    }
}
