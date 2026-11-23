package ru.gera.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.abs
import kotlin.math.sqrt

/** Общие функции для работы с записью звука. */
object AudioUtil {

    const val SAMPLE_RATE = 16000
    const val CHUNK = SAMPLE_RATE / 10 // 100 мс

    /**
     * Порог "это речь" по громкости. Чувствительность 0..100:
     * чем выше, тем тише можно говорить (но тем легче ложные срабатывания от шума).
     */
    fun speechThreshold(noiseLevel: Double, sensitivity: Int): Double {
        val s = sensitivity.coerceIn(0, 100) / 100.0
        val minLevel = 500.0 * (1 - s) + 40.0 * s
        val multiplier = 3.0 * (1 - s) + 1.4 * s
        return maxOf(noiseLevel * multiplier, minLevel)
    }

    /** Открывает микрофон и включает автоусиление/шумоподавление, если телефон их умеет. */
    @SuppressLint("MissingPermission")
    fun openMic(useHeadset: Boolean): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // VOICE_COMMUNICATION — через Bluetooth-гарнитуру и с обработкой голоса
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
        val id = record.audioSessionId
        if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(id)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(id)?.enabled = true }
        return record
    }

    fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / maxOf(n, 1))
    }

    /** ShortArray -> байты PCM (little-endian) с усилением и защитой от перегруза. */
    fun toBytes(buf: ShortArray, n: Int, gain: Double = 1.0): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (buf[i] * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /**
     * Выравнивает громкость всей фразы: тихую запись поднимает до нормального уровня.
     * Распознаватели заметно лучше работают с нормально громким звуком.
     */
    fun normalize(pcm: ByteArray, maxGain: Double = 12.0): ByteArray {
        val n = pcm.size / 2
        if (n == 0) return pcm
        var peak = 1
        for (i in 0 until n) {
            val v = abs(sample(pcm, i))
            if (v > peak) peak = v
        }
        val gain = minOf(29000.0 / peak, maxGain)
        if (gain <= 1.05) return pcm
        val out = ByteArray(pcm.size)
        for (i in 0 until n) {
            val v = (sample(pcm, i) * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun sample(pcm: ByteArray, i: Int): Int =
        (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
}
