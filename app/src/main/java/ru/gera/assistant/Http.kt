package ru.gera.assistant

import java.net.HttpURLConnection
import java.net.URL

/** Минимальный HTTP-клиент на стандартном HttpURLConnection, чтобы не тянуть лишних библиотек. */
object Http {

    class Response(val code: Int, val body: String)

    fun get(url: String): Response = request("GET", url, null, emptyMap())

    fun postJson(url: String, json: String, headers: Map<String, String>): Response =
        request("POST", url, json, headers + ("Content-Type" to "application/json; charset=utf-8"))

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
