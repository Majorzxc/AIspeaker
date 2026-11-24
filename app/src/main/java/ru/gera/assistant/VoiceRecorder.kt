package ru.gera.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Записывает одну фразу с микрофона: ждёт начала речи и останавливается после паузы.
 * Определение речи простое — по громкости относительно фонового шума.
 */
class VoiceRecorder(private val context: android.content.Context) {

    companion object {
        const val SAMPLE_RATE = AudioUtil.SAMPLE_RATE
        private const val CHUNK = AudioUtil.CHUNK
        private const val SILENCE_TO_STOP_MS = 1200         // пауза, после которой считаем фразу законченной
        private const val WAIT_FOR_SPEECH_MS = 6000         // сколько ждать начала речи
        private const val MAX_PHRASE_MS = 15000             // максимальная длина фразы
    }

    /** Возвращает PCM 16 бит, 16 кГц, моно — или null, если речи не было. */
    suspend fun recordPhrase(micChoice: String, sensitivity: Int): ByteArray? = withContext(Dispatchers.IO) {
        val record = AudioUtil.openMic(context, micChoice)

        val out = ByteArrayOutputStream()
        val buf = ShortArray(CHUNK)

        var noise = 0.0
        var noiseChunks = 0
        var speechStarted = false
        var silenceMs = 0
        var elapsedMs = 0
        // Немного звука до начала речи, чтобы не обрезать первый слог
        val preRoll = ArrayDeque<ByteArray>()

        record.startRecording()
        try {
            EventLog.add("Микрофон слушает — говорите [${AudioUtil.routedName(record)}]")
            while (true) {
                ensureActive()
                val n = record.read(buf, 0, CHUNK)
                if (n <= 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
                elapsedMs += 100

                val chunk = AudioUtil.toBytes(buf, n)
                val level = AudioUtil.rms(buf, n)

                // Первые 300 мс — замеряем фоновый шум
                // Первые 300 мс — замеряем фон. Берём минимум, а не среднее:
                // если человек заговорил сразу, его речь не испортит оценку шума
                if (noiseChunks < 3) {
                    noise = if (noiseChunks == 0) level else minOf(noise, level)
                    noiseChunks++
                    preRoll.addLast(chunk)
                    continue
                }
                val threshold = AudioUtil.speechThreshold(noise, sensitivity)

                if (!speechStarted) {
                    if (level > threshold) {
                        speechStarted = true
                        EventLog.add("Слышу речь…")
                        preRoll.forEach { out.write(it) }
                        out.write(chunk)
                    } else {
                        preRoll.addLast(chunk)
                        if (preRoll.size > 4) preRoll.removeFirst()
                        if (elapsedMs > WAIT_FOR_SPEECH_MS) {
                            EventLog.add("Речь не услышана (шум ${noise.toInt()}, порог ${threshold.toInt()})")
                            return@withContext null
                        }
                    }
                } else {
                    out.write(chunk)
                    silenceMs = if (level > threshold) 0 else silenceMs + 100
                    if (silenceMs >= SILENCE_TO_STOP_MS || elapsedMs > MAX_PHRASE_MS) break
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        EventLog.add("Фраза записана (${out.size() / (SAMPLE_RATE * 2 / 10) / 10.0} с)")
        out.toByteArray()
    }

    /** Просто записывает [ms] миллисекунд — для проверки микрофона. */
    suspend fun recordFixed(micChoice: String, ms: Int): ByteArray = withContext(Dispatchers.IO) {
        val record = AudioUtil.openMic(context, micChoice)
        val out = ByteArrayOutputStream()
        val buf = ShortArray(CHUNK)
        var peak = 0.0
        var sum = 0.0
        var chunks = 0
        record.startRecording()
        try {
            EventLog.add("Проверка: говорите 4 секунды [${AudioUtil.routedName(record)}]")
            while (chunks * 100 < ms) {
                ensureActive()
                val n = record.read(buf, 0, CHUNK)
                if (n <= 0) break
                val level = AudioUtil.rms(buf, n)
                peak = maxOf(peak, level)
                sum += level
                chunks++
                out.write(AudioUtil.toBytes(buf, n))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        EventLog.add("Проверка: средний уровень ${(sum / maxOf(chunks, 1)).toInt()}, пик ${peak.toInt()}")
        out.toByteArray()
    }
}
