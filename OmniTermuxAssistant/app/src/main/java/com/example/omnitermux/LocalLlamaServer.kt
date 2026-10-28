package com.example.omnitermux

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the llama.cpp server running locally under Termux. No prompt leaves the device on this path. */
class LocalLlamaServer(private val baseUrl: String = "http://127.0.0.1:8080") {
    fun isAvailable(): Boolean = try {
        val c = URL("$baseUrl/health").openConnection() as HttpURLConnection
        c.connectTimeout = 700
        c.readTimeout = 700
        try { c.requestMethod = "GET"; c.responseCode in 200..299 } finally { c.disconnect() }
    } catch (_: Exception) { false }

    fun generate(prompt: String, maxTokens: Int = 512, imageDataUrl: String? = null): String {
        val c = URL("$baseUrl/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3_000
            c.readTimeout = 180_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val content: Any = if (imageDataUrl == null) prompt else JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
            val body = JSONObject()
                .put("model", "local-model")
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", """You are Omni Termux Assistant. Be helpful and concise. Never claim you completed an action unless the app confirms it. For an action that matches a tool, return ONLY one JSON object with keys "tool", "arguments", "message". Allowed tools: web_search(query), open_url(url), dial_number(number), open_whatsapp(number,message), send_sms(number,message). Do not output shell commands or invent other tools. The app validates the arguments and asks the user before running a tool. For ordinary questions, answer naturally in plain text."""))
                    .put(JSONObject().put("role", "user").put("content", content)))
                .put("max_tokens", maxTokens)
                .put("temperature", 0.2)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Local llama.cpp HTTP $code: ${raw.take(300)}")
            return JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally { c.disconnect() }
    }
}
