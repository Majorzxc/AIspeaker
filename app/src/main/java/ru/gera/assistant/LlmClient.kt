package ru.gera.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Клиент для любого OpenAI-совместимого API (POST {baseUrl}/chat/completions).
 * Хранит короткую историю диалога, чтобы можно было задавать уточняющие вопросы.
 */
class LlmClient(private val settings: Settings) {

    private val history = mutableListOf<JSONObject>()
    private var lastActivity = 0L

    suspend fun ask(userText: String): String = withContext(Dispatchers.IO) {
        if (settings.baseUrl.isBlank() || settings.model.isBlank()) {
            return@withContext "Не настроен адрес API или модель."
        }

        // Через 5 минут тишины начинаем разговор заново
        if (System.currentTimeMillis() - lastActivity > 5 * 60_000) history.clear()
        lastActivity = System.currentTimeMillis()

        history.add(msg("user", userText))
        trimHistory()

        val messages = JSONArray()
        messages.put(msg("system", buildSystemPrompt()))
        history.forEach { messages.put(it) }

        var useTools = settings.toolsEnabled
        // Цикл: модель может несколько раз попросить вызвать инструмент
        repeat(4) {
            val resp = try {
                call(messages, useTools)
            } catch (e: ToolsUnsupported) {
                useTools = false // провайдер не понимает tools — пробуем без них
                call(messages, false)
            }

            val message = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls")

            if (toolCalls == null || toolCalls.length() == 0) {
                val content = if (message.isNull("content")) "" else message.optString("content")
                val answer = cleanForSpeech(content)
                history.add(msg("assistant", answer))
                return@withContext answer.ifBlank { "Пустой ответ от модели." }
            }

            // Модель попросила вызвать инструменты — выполняем и отдаём результат обратно
            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val output = runTool(fn.optString("name"), fn.optString("arguments", "{}"))
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.optString("id"))
                    put("content", output)
                })
            }
        }
        "Не получилось получить ответ."
    }

    private fun call(messages: JSONArray, withTools: Boolean): JSONObject {
        val body = JSONObject().apply {
            put("model", settings.model)
            put("messages", messages)
            put("max_tokens", settings.maxTokens)
            put("temperature", 0.5)
            if (withTools) put("tools", JSONArray().put(WeatherTool.schema()))
        }
        val headers = if (settings.apiKey.isNotBlank())
            mapOf("Authorization" to "Bearer ${settings.apiKey}") else emptyMap()

        val resp = Http.postJson(endpoint(), body.toString(), headers)
        if (resp.code in 200..299) return JSONObject(resp.body)

        // 400/422 при отправке tools обычно значит "не поддерживаем инструменты"
        if (withTools && (resp.code == 400 || resp.code == 422)) throw ToolsUnsupported()
        throw ApiException(resp.code, resp.body.take(300))
    }

    private fun runTool(name: String, args: String): String = try {
        when (name) {
            WeatherTool.NAME -> WeatherTool.run(args, settings.city)
            else -> """{"error":"неизвестный инструмент"}"""
        }
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
    }

    /** Принимаем адрес в любом виде: с /chat/completions на конце или без. */
    private fun endpoint(): String {
        val base = settings.baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun buildSystemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru-RU")).format(Date())
        val city = settings.city.ifBlank { "не указан" }
        return settings.systemPrompt + "\n\nСейчас: $now. Город пользователя: $city."
    }

    private fun trimHistory() {
        while (history.size > 8) history.removeAt(0)
        // История должна начинаться с вопроса пользователя
        while (history.isNotEmpty() && history[0].optString("role") != "user") history.removeAt(0)
    }

    fun reset() = history.clear()

    private fun msg(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    /** Убираем то, что плохо звучит при озвучке: markdown, блоки рассуждений. */
    private fun cleanForSpeech(text: String): String = text
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("[*#_`>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private class ToolsUnsupported : Exception()
    class ApiException(val code: Int, body: String) : Exception("ошибка API $code: $body")
}
