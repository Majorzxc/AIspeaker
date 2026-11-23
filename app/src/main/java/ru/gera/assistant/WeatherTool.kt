package ru.gera.assistant

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Инструмент "погода" для ИИ. Использует бесплатный Open-Meteo (без ключа).
 * Возвращает компактный JSON — модель сама сформулирует из него короткий ответ.
 */
object WeatherTool {

    const val NAME = "get_weather"

    /** Описание инструмента в формате OpenAI tools. */
    fun schema(): JSONObject = JSONObject(
        """
        {
          "type": "function",
          "function": {
            "name": "$NAME",
            "description": "Текущая погода и прогноз на сегодня и завтра для города.",
            "parameters": {
              "type": "object",
              "properties": {
                "city": {
                  "type": "string",
                  "description": "Название города. Если пользователь не назвал город — не заполняй."
                }
              }
            }
          }
        }
        """.trimIndent()
    )

    /** Выполняется в фоновом потоке. */
    fun run(argumentsJson: String, defaultCity: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject())
        val city = args.optString("city").ifBlank { defaultCity }
        if (city.isBlank()) return """{"error":"город не указан и не задан в настройках"}"""

        // 1. Город -> координаты
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&format=json&name=" +
            URLEncoder.encode(city, "UTF-8")
        val geo = JSONObject(Http.get(geoUrl).body)
        val place = geo.optJSONArray("results")?.optJSONObject(0)
            ?: return """{"error":"город '$city' не найден"}"""
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")

        // 2. Погода по координатам
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,wind_speed_10m,precipitation,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&forecast_days=2&wind_speed_unit=ms&timezone=auto"
        val w = JSONObject(Http.get(url).body)
        val cur = w.getJSONObject("current")
        val daily = w.getJSONObject("daily")

        val result = JSONObject()
        result.put("city", place.optString("name", city))
        result.put("now", JSONObject().apply {
            put("temp_c", cur.optDouble("temperature_2m"))
            put("feels_like_c", cur.optDouble("apparent_temperature"))
            put("wind_ms", cur.optDouble("wind_speed_10m"))
            put("precipitation_mm", cur.optDouble("precipitation"))
            put("conditions", describe(cur.optInt("weather_code")))
        })
        val days = listOf("today", "tomorrow")
        for (i in days.indices) {
            result.put(days[i], JSONObject().apply {
                put("min_c", daily.getJSONArray("temperature_2m_min").optDouble(i))
                put("max_c", daily.getJSONArray("temperature_2m_max").optDouble(i))
                put("precip_probability_pct", daily.getJSONArray("precipitation_probability_max").optInt(i))
                put("conditions", describe(daily.getJSONArray("weather_code").optInt(i)))
            })
        }
        return result.toString()
    }

    /** Коды погоды WMO -> короткое описание. */
    private fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1, 2 -> "переменная облачность"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "дождь"
        71, 73, 75, 77, 85, 86 -> "снег"
        95, 96, 99 -> "гроза"
        else -> "неизвестно"
    }
}
