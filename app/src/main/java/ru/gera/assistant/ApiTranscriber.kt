package ru.gera.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Распознавание через OpenAI-совместимый API: POST {baseUrl}/audio/transcriptions (multipart). */
class ApiTranscriber(private val settings: Settings) {

    suspend fun transcribe(pcm: ByteArray): String = withContext(Dispatchers.IO) {
        // Свой провайдер распознавания, если задан; иначе — основной
        val base = settings.sttBaseUrl.ifBlank { settings.baseUrl }
            .trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/audio/transcriptions")
        val key = settings.sttApiKey.ifBlank { settings.apiKey }
        val url = "$base/audio/transcriptions"
        val boundary = "----gera" + UUID.randomUUID().toString().replace("-", "")

        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", settings.sttModel)
        field("language", "ru")
        field("response_format", "json")
        field("temperature", "0") // меньше "фантазий" на нечётком звуке
        body.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        body.write(wav(pcm))
        body.write("\r\n--$boundary--\r\n".toByteArray())

        EventLog.add("Распознавание через API: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                EventLog.add("Распознавание API: код $code: ${text.take(200)}")
                throw LlmClient.ApiException(code, text.take(200))
            }
            JSONObject(text).optString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Оборачивает сырой PCM в WAV-заголовок. */
    private fun wav(pcm: ByteArray): ByteArray {
        val rate = VoiceRecorder.SAMPLE_RATE
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
