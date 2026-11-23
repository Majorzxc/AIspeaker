package ru.gera.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Записывает одну фразу с микрофона: ждёт начала речи и останавливается после паузы.
 * Определение речи простое — по громкости относительно фонового шума.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK = SAMPLE_RATE / 10          // 100 мс
        private const val SILENCE_TO_STOP_MS = 1200         // пауза, после которой считаем фразу законченной
        private const val WAIT_FOR_SPEECH_MS = 6000         // сколько ждать начала речи
        private const val MAX_PHRASE_MS = 15000             // максимальная длина фразы
    }

    /** Возвращает PCM 16 бит, 16 кГц, моно — или null, если речи не было. */
    @SuppressLint("MissingPermission") // разрешение проверяется до запуска сервиса
    suspend fun recordPhrase(useHeadset: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_COMMUNICATION идёт через Bluetooth-гарнитуру, VOICE_RECOGNITION — микрофон телефона
        val source = if (useHeadset) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 2 * 4)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("микрофон недоступен")
        }

        val out = ByteArrayOutputStream()
        val buf = ShortArray(CHUNK)
        val bytes = ByteArray(CHUNK * 2)

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

                for (i in 0 until n) {
                    bytes[2 * i] = (buf[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
                }
                val chunk = bytes.copyOf(n * 2)
                val level = rms(buf, n)

                // Первые 300 мс — замеряем фоновый шум
                if (noiseChunks < 3) {
                    noise += level
                    noiseChunks++
                    preRoll.addLast(chunk)
                    continue
                }
                val threshold = maxOf(noise / noiseChunks * 2.5, 400.0)

                if (!speechStarted) {
                    if (level > threshold) {
                        speechStarted = true
                        EventLog.add("Слышу речь…")
                        preRoll.forEach { out.write(it) }
                        out.write(chunk)
                    } else {
                        preRoll.addLast(chunk)
                        if (preRoll.size > 3) preRoll.removeFirst()
                        if (elapsedMs > WAIT_FOR_SPEECH_MS) return@withContext null
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

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / n)
    }
}
