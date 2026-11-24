package ru.gera.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioDeviceInfo
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

    /** Варианты выбора микрофона: ключ -> название для экрана. */
    val MIC_CHOICES = listOf(
        "builtin" to "Микрофон телефона",
        "bluetooth" to "Bluetooth-гарнитура (режим звонка)",
        "wired" to "Проводная / USB-гарнитура",
        "auto" to "Авто (как решит Android)"
    )

    /**
     * Открывает выбранный микрофон и включает шумоподавление, если телефон его умеет.
     * Автоусиление (AGC) не включаем: в тишине оно "раздувает" шум,
     * а громкость фразы мы и так выравниваем сами перед распознаванием.
     */
    @SuppressLint("MissingPermission")
    fun openMic(context: android.content.Context, choice: String): AudioRecord {
        val useHeadset = choice == "bluetooth"
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
        // Просим Android использовать конкретный микрофон
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val wanted = when (choice) {
            "builtin" -> listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
            "wired" -> listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
            "bluetooth" -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            else -> emptyList()
        }
        if (wanted.isNotEmpty()) {
            val device = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in wanted }
            if (device != null) record.setPreferredDevice(device)
            else EventLog.add("Выбранный микрофон не найден, использую стандартный")
        }

        val id = record.audioSessionId
        if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(id)?.enabled = false }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(id)?.enabled = true }
        return record
    }

    /** Название микрофона, через который реально идёт запись (вызывать после startRecording). */
    fun routedName(record: AudioRecord): String {
        val d = record.routedDevice ?: return "неизвестно"
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "телефон"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "проводная гарнитура"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> "тип ${d.type}"
        }
        return "$type (${d.productName})"
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
    fun normalize(pcm: ByteArray, maxGain: Double = 6.0): ByteArray {
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
