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
