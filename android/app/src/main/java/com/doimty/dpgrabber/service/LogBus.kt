package com.doimty.dpgrabber.service

import com.doimty.dpgrabber.engine.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory run log. The foreground service appends lines; the UI
 * observes them while visible. Nothing is written to disk.
 */
object LogBus {
    data class Line(val level: LogLevel, val time: String, val text: String)

    private const val MAX_LINES = 500
    private val lock = Any()
    private val lines = ArrayList<Line>()
    private val listeners = LinkedHashSet<(Line) -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun post(level: LogLevel, text: String) {
        val line: Line
        val snapshot: List<(Line) -> Unit>
        synchronized(lock) {
            // SimpleDateFormat is not thread-safe; format inside the lock.
            line = Line(level, timeFormat.format(Date()), text)
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
            snapshot = listeners.toList()
        }
        snapshot.forEach { it(line) }
    }

    fun snapshot(): List<Line> = synchronized(lock) { lines.toList() }

    fun addListener(listener: (Line) -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: (Line) -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}
