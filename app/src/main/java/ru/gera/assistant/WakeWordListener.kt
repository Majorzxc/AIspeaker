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

    /** Что услышали. */
    sealed class Event(val pcm: ByteArray) {
        /** Vosk уверенно услышал слово-триггер. */
        class Hit(pcm: ByteArray) : Event(pcm)
        /** Короткая фраза (возможно, шёпот) — её нужно проверить через API. */
        class Candidate(pcm: ByteArray) : Event(pcm)
    }

    private var lastCandidateAt = 0L

    /**
     * Блокируется, пока не услышит слово-триггер.
     * @param whisperMode дополнительно отдавать короткие фразы на проверку через API (для шёпота)
     */
    suspend fun awaitWakeWord(words: List<String>, sensitivity: Int, whisperMode: Boolean, micChoice: String): Event = withContext(Dispatchers.IO) {
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

        // Bluetooth-микрофон для постоянного прослушивания не берём: он держит наушники в режиме звонка
        val record = AudioUtil.openMic(context, if (micChoice == "bluetooth") "builtin" else micChoice)
        val buf = ShortArray(AudioUtil.CHUNK)
        val phrase = ArrayDeque<ByteArray>()

        // Простой детектор речи для режима шёпота (по громкости, без усиления)
        var noise = 0.0
        var noiseChunks = 0
        val preRoll = ArrayDeque<ByteArray>()
        val segment = ByteArrayOutputStream()
        var inSegment = false
        var segmentMs = 0
        var silenceMs = 0

        record.startRecording()
        EventLog.add("Жду слово-триггер: ${words.joinToString(" / ")}")
        try {
            while (true) {
                ensureActive()
                val n = record.read(buf, 0, AudioUtil.CHUNK)
                if (n <= 0) throw IllegalStateException("ошибка чтения микрофона ($n)")

                if (whisperMode) {
                    val level = AudioUtil.rms(buf, n)
                    val raw = AudioUtil.toBytes(buf, n)
                    if (noiseChunks < 5) {
                        noise = (noise * noiseChunks + level) / (noiseChunks + 1)
                        noiseChunks++
                    } else {
                        val threshold = AudioUtil.speechThreshold(noise, sensitivity)
                        val loud = level > threshold
                        if (!inSegment) {
                            // Медленно подстраиваемся под фоновый шум
                            if (!loud) noise = noise * 0.97 + level * 0.03
                            preRoll.addLast(raw)
                            if (preRoll.size > 3) preRoll.removeFirst()
                            if (loud) {
                                inSegment = true
                                segment.reset()
                                preRoll.forEach { segment.write(it) }
                                segmentMs = preRoll.size * 100
                                silenceMs = 0
                            }
                        } else {
                            segment.write(raw)
                            segmentMs += 100
                            silenceMs = if (loud) 0 else silenceMs + 100
                            if (segmentMs > 6000) {
                                inSegment = false // слишком длинная речь — это не обращение
                            } else if (silenceMs >= 700) {
                                inSegment = false
                                val speechMs = segmentMs - silenceMs
                                val now = System.currentTimeMillis()
                                if (speechMs in 400..5000 && now - lastCandidateAt > 2500) {
                                    lastCandidateAt = now
                                    return@withContext Event.Candidate(segment.toByteArray())
                                }
                            }
                        }
                    }
                }

                val chunk = AudioUtil.toBytes(buf, n, gain)
                phrase.addLast(chunk)
                if (phrase.size > MAX_BUFFER_CHUNKS) phrase.removeFirst()

                // true — Vosk решил, что фраза закончилась (была пауза)
                if (rec.acceptWaveForm(chunk, chunk.size)) {
                    if (containsWakeWord(rec.result, words, minConfidence)) {
                        val out = ByteArrayOutputStream()
                        phrase.forEach { out.write(it) }
                        return@withContext Event.Hit(out.toByteArray())
                    }
                    phrase.clear()
                }
            }
            Event.Hit(ByteArray(0)) // сюда не доходим: выход из цикла только через return или исключение
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
