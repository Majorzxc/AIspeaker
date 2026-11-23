package ru.gera.assistant

import android.content.Context
import androidx.core.content.edit

/** Все настройки приложения в одном месте (хранятся в SharedPreferences). */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("gera_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit { putString("base_url", v.trim()) }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit { putString("api_key", v.trim()) }

    var model: String
        get() = prefs.getString("model", "") ?: ""
        set(v) = prefs.edit { putString("model", v.trim()) }

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT
        set(v) = prefs.edit { putString("system_prompt", v) }

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 200)
        set(v) = prefs.edit { putInt("max_tokens", v) }

    /** Скорость речи: 1.0 — обычная, 0.5 — медленно, 2.0 — быстро. */
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(v) = prefs.edit { putFloat("speech_rate", v) }

    var city: String
        get() = prefs.getString("city", "") ?: ""
        set(v) = prefs.edit { putString("city", v.trim()) }

    /** Давать ли модели инструменты (погода). Некоторые провайдеры их не поддерживают. */
    var toolsEnabled: Boolean
        get() = prefs.getBoolean("tools_enabled", true)
        set(v) = prefs.edit { putBoolean("tools_enabled", v) }

    /** Слушать микрофон Bluetooth-гарнитуры вместо микрофона телефона (экспериментально). */
    var useHeadsetMic: Boolean
        get() = prefs.getBoolean("headset_mic", false)
        set(v) = prefs.edit { putBoolean("headset_mic", v) }

    /** true — распознавать через API (/audio/transcriptions), false — офлайн Vosk. */
    var sttViaApi: Boolean
        get() = prefs.getBoolean("stt_via_api", false)
        set(v) = prefs.edit { putBoolean("stt_via_api", v) }

    /** Отдельный провайдер для распознавания. Пусто — используется основной адрес/ключ. */
    var sttBaseUrl: String
        get() = prefs.getString("stt_base_url", "") ?: ""
        set(v) = prefs.edit { putString("stt_base_url", v.trim()) }

    var sttApiKey: String
        get() = prefs.getString("stt_api_key", "") ?: ""
        set(v) = prefs.edit { putString("stt_api_key", v.trim()) }

    var sttModel: String
        get() = prefs.getString("stt_model", "whisper-1") ?: "whisper-1"
        set(v) = prefs.edit { putString("stt_model", v.trim().ifEmpty { "whisper-1" }) }

    /** Чувствительность микрофона 0..100: выше — можно говорить тише. */
    var micSensitivity: Int
        get() = prefs.getInt("mic_sensitivity", 60)
        set(v) = prefs.edit { putInt("mic_sensitivity", v.coerceIn(0, 100)) }

    /** Слушать ли постоянно слово-триггер. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", false)
        set(v) = prefs.edit { putBoolean("wake_enabled", v) }

    /** Слова/фразы-триггеры через запятую. */
    var wakeWords: String
        get() = prefs.getString("wake_words", "гера") ?: "гера"
        set(v) = prefs.edit { putString("wake_words", v.trim().ifEmpty { "гера" }) }

    companion object {
        val DEFAULT_PROMPT = """
            Ты — Гера, голосовой ассистент. Твой ответ будет прочитан вслух в наушник.
            Правила:
            - Отвечай максимально коротко: одно-два предложения, без вступлений и повторения вопроса.
            - Никакого markdown, списков, эмодзи и ссылок — только простой текст для озвучки.
            - Числа пиши цифрами, единицы измерения — словами (градусов, метров в секунду).
            - Если нужны свежие данные о погоде — используй инструмент get_weather.
            - Если не знаешь ответа — так и скажи одной фразой.
        """.trimIndent()
    }
}
