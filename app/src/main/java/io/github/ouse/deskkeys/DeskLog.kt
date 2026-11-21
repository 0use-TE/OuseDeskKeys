package io.github.ouse.deskkeys

import android.util.Log

/** Shortcut diagnostics only, bounded in memory; no files or periodic work. */
object DeskLog {
    private val entries = ArrayDeque<String>()
    @Synchronized fun record(message: String) {
        if (entries.size >= 64) entries.removeFirst()
        entries.addLast("${System.currentTimeMillis()} $message")
        Log.i("OuseDeskKeys", message)
    }
    @Synchronized fun snapshot(): List<String> = entries.toList()
}
