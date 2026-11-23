package ru.gera.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Записывает одну фразу с микрофона: ждёт начала речи и останавливается после паузы.
 * Определение речи простое — по громкости относительно фонового шума.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = AudioUtil.SAMPLE_RATE
        private const val CHUNK = AudioUtil.CHUNK
        private const val SILENCE_TO_STOP_MS = 1200         // пауза, после которой считаем фразу законченной
        private const val WAIT_FOR_SPEECH_MS = 6000         // сколько ждать начала речи
        private const val MAX_PHRASE_MS = 15000             // максимальная длина фразы
    }

    /** Возвращает PCM 16 бит, 16 кГц, моно — или null, если речи не было. */
    suspend fun recordPhrase(useHeadset: Boolean, sensitivity: Int): ByteArray? = withContext(Dispatchers.IO) {
        val record = AudioUtil.openMic(useHeadset)

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
            EventLog.add("Микрофон слушает — говорите")
            while (true) {
                ensureActive()
                val n = record.read(buf, 0, CHUNK)
                if (n <= 0) throw IllegalStateException("ошибка чтения микрофона ($n)")
                elapsedMs += 100

                val chunk = AudioUtil.toBytes(buf, n)
                val level = AudioUtil.rms(buf, n)

                // Первые 300 мс — замеряем фоновый шум
                if (noiseChunks < 3) {
                    noise += level
                    noiseChunks++
                    preRoll.addLast(chunk)
                    continue
                }
                val threshold = AudioUtil.speechThreshold(noise / noiseChunks, sensitivity)

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
                            EventLog.add("Речь не услышана (шум ${(noise / noiseChunks).toInt()}, порог ${threshold.toInt()})")
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
}
