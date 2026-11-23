package ru.gera.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream

/**
 * Постоянно слушает микрофон и ждёт слово-триггер (например, "гера").
 *
 * Как работает: офлайн-распознаватель Vosk запускается с крошечным словарём
 * [слово-триггер, "[unk]" = любое другое слово]. Это дёшево по процессору.
 * Когда во фразе встретилось слово-триггер с достаточной уверенностью —
 * возвращаем звук всей фразы: в ней может быть сразу и вопрос ("Гера, какая погода").
 */
class WakeWordListener(private val context: Context) {

    companion object {
        private const val MAX_BUFFER_CHUNKS = 120 // 12 секунд звука
    }

    /** Список слов/фраз-триггеров из настройки "гера, эй гера". */
    fun parseWords(raw: String): List<String> =
        raw.lowercase().split(',', ';', '\n')
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("гера") }

    /** Блокируется, пока не услышит слово-триггер. Возвращает звук фразы, в которой оно прозвучало. */
    suspend fun awaitWakeWord(words: List<String>, sensitivity: Int): ByteArray = withContext(Dispatchers.IO) {
        val model = VoskEngine.model(context)

        // Словарь: отдельные слова из всех фраз + [unk]
        val vocab = JSONArray()
        words.flatMap { it.split(' ') }.distinct().forEach { vocab.put(it) }
        vocab.put("[unk]")

        val rec = Recognizer(model, AudioUtil.SAMPLE_RATE.toFloat(), vocab.toString())
        rec.setWords(true)

        // Чем выше чувствительность — тем ниже требуемая уверенность и сильнее усиление
        val s = sensitivity.coerceIn(0, 100) / 100.0
        val minConfidence = 0.85 - 0.35 * s
        val gain = 1.0 + 3.0 * s

        val record = AudioUtil.openMic(useHeadset = false)
        val buf = ShortArray(AudioUtil.CHUNK)
        val phrase = ArrayDeque<ByteArray>()

        record.startRecording()
        EventLog.add("Жду слово-триггер: ${words.joinToString(" / ")}")
        try {
            while (true) {
                ensureActive()
                val n = record.read(buf, 0, AudioUtil.CHUNK)
                if (n <= 0) throw IllegalStateException("ошибка чтения микрофона ($n)")

                val chunk = AudioUtil.toBytes(buf, n, gain)
                phrase.addLast(chunk)
                if (phrase.size > MAX_BUFFER_CHUNKS) phrase.removeFirst()

                // true — Vosk решил, что фраза закончилась (была пауза)
                if (rec.acceptWaveForm(chunk, chunk.size)) {
                    if (containsWakeWord(rec.result, words, minConfidence)) {
                        val out = ByteArrayOutputStream()
                        phrase.forEach { out.write(it) }
                        return@withContext out.toByteArray()
                    }
                    phrase.clear()
                }
            }
            ByteArray(0) // сюда не доходим: выход из цикла только через return или исключение
        } finally {
            runCatching { record.stop() }
            record.release()
            rec.close()
        }
    }

    private fun containsWakeWord(resultJson: String, words: List<String>, minConfidence: Double): Boolean {
        val result = JSONObject(resultJson).optJSONArray("result") ?: return false
        val heard = (0 until result.length()).map {
            val w = result.getJSONObject(it)
            w.optString("word") to w.optDouble("conf", 0.0)
        }
        if (heard.isEmpty()) return false

        for (phrase in words) {
            val parts = phrase.split(' ')
            // Ищем фразу-триггер как последовательность слов
            for (start in 0..heard.size - parts.size) {
                val window = heard.subList(start, start + parts.size)
                if (window.map { it.first } == parts) {
                    val conf = window.minOf { it.second }
                    EventLog.add("Услышал «$phrase» (уверенность ${"%.2f".format(conf)})")
                    if (conf >= minConfidence) return true
                }
            }
        }
        return false
    }
}
