package ru.gera.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Офлайн-распознавание речи Vosk.
 * Модель (~45 МБ) скачивается один раз в память приложения.
 */
object VoskEngine {

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"

    @Volatile
    private var model: Model? = null

    private fun modelDir(context: Context) = File(context.filesDir, "vosk-ru")

    fun isDownloaded(context: Context) = File(modelDir(context), "READY").exists()

    /** Скачивает и распаковывает модель. onProgress получает проценты. */
    suspend fun download(context: Context, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val dir = modelDir(context)
        dir.deleteRecursively()
        dir.mkdirs()

        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("сервер ответил ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (45L * 1024 * 1024)
            var read = 0L
            var lastPct = -1

            // Распаковываем прямо на лету, отрезая верхнюю папку архива
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        val pct = (read * 100 / total).toInt().coerceAtMost(100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val target = File(dir, relative)
                    // Защита от путей вида ../ в архиве
                    if (!target.canonicalPath.startsWith(dir.canonicalPath)) continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            File(dir, "READY").writeText("ok")
        } finally {
            conn.disconnect()
        }
    }

    /** Распознаёт записанную фразу (PCM 16 бит, 16 кГц). */
    suspend fun transcribe(context: Context, pcm: ByteArray): String = withContext(Dispatchers.Default) {
        val m = loadModel(context)
        val rec = Recognizer(m, VoiceRecorder.SAMPLE_RATE.toFloat())
        try {
            rec.acceptWaveForm(pcm, pcm.size)
            JSONObject(rec.finalResult).optString("text").trim()
        } finally {
            rec.close()
        }
    }

    private fun loadModel(context: Context): Model {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            if (!isDownloaded(context)) throw IllegalStateException("офлайн-модель не скачана")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            EventLog.add("Загружаю офлайн-модель…")
            val loaded = Model(modelDir(context).absolutePath)
            model = loaded
            return loaded
        }
    }
}
