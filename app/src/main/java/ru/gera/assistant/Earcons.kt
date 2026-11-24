package ru.gera.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Короткие звуковые сигналы: "говорите" (вверх) и "услышал" (вниз).
 * Синтезируются в коде, файлы не нужны. Перед сигналом — немного тишины,
 * чтобы Bluetooth-наушники "проснулись" и не съели начало звука.
 */
object Earcons {

    private const val RATE = 44100

    /** Восходящие два тона — можно говорить. */
    suspend fun listenStart() = play(tones(listOf(660.0 to 90, 990.0 to 120)))

    /** Нисходящие два тона — запись закончена. */
    suspend fun listenEnd() = play(tones(listOf(990.0 to 90, 660.0 to 120)))

    /** Один низкий тон — ничего не услышал / ошибка. */
    suspend fun fail() = play(tones(listOf(330.0 to 220)))

    /** Проигрывает PCM 16 бит моно с заданной частотой и ждёт окончания. */
    suspend fun playPcm(pcm: ByteArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        val samples = ShortArray(pcm.size / 2) {
            ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort()
        }
        playSamples(samples, sampleRate)
    }

    private suspend fun play(samples: ShortArray) = withContext(Dispatchers.IO) {
        playSamples(samples, RATE)
    }

    private suspend fun playSamples(samples: ShortArray, rate: Int) {
        if (samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            // Ждём длительность звука + запас на задержку Bluetooth
            delay(samples.size * 1000L / rate + 250)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Склеивает тоны (частота, мс) с плавными краями и тишиной в начале. */
    private fun tones(parts: List<Pair<Double, Int>>): ShortArray {
        val lead = RATE * 150 / 1000 // 150 мс тишины для пробуждения Bluetooth
        val total = lead + parts.sumOf { RATE * it.second / 1000 }
        val out = ShortArray(total)
        var pos = lead
        for ((freq, ms) in parts) {
            val n = RATE * ms / 1000
            val fade = RATE * 10 / 1000
            for (i in 0 until n) {
                val env = min(1.0, min(i, n - 1 - i).toDouble() / fade) // без щелчков
                out[pos + i] = (sin(2 * PI * freq * i / RATE) * env * 12000).toInt().toShort()
            }
            pos += n
        }
        return out
    }
}
