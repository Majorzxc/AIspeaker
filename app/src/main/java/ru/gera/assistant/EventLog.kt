package ru.gera.assistant

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал событий для диагностики: показывается на главном экране. */
object EventLog {
    private const val MAX = 40
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(msg: String) {
        Log.i("Gera", msg)
        synchronized(lines) {
            lines.addFirst("${fmt.format(Date())}  $msg")
            while (lines.size > MAX) lines.removeLast()
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }
}
