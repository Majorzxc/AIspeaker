package ru.gera.assistant

import android.app.Application
import java.io.File

/**
 * Перехватывает вылеты: сохраняет текст ошибки в файл,
 * а при следующем запуске выводит его в журнал на экране.
 */
class GeraApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val crashFile = File(filesDir, "last_crash.txt")
        val logFile = File(filesDir, "last_log.txt")

        if (logFile.exists()) {
            EventLog.restore("--- журнал до вылета ---\n" + logFile.readText())
            logFile.delete()
        }

        if (crashFile.exists()) {
            val trace = crashFile.readText().lines()
                .filter { it.isNotBlank() }
                // Самое полезное: текст ошибки и строки из нашего кода
                .filterIndexed { i, line -> i == 0 || "ru.gera" in line || line.startsWith("Caused by") }
                .take(12)
                .joinToString("\n")
            EventLog.add("ПРОШЛЫЙ ВЫЛЕТ:\n$trace")
            crashFile.delete()
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { crashFile.writeText(android.util.Log.getStackTraceString(error)) }
            runCatching { logFile.writeText(EventLog.text()) }
            previous?.uncaughtException(thread, error)
        }
    }
}
