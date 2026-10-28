package com.example.omnitermux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HybridConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "",
    val apiKey: String = ""
)

class HybridInference(private val context: Context, private val local: LocalLlamaServer) {
    private val prefs = context.getSharedPreferences("hybrid_inference", Context.MODE_PRIVATE)

    fun save(config: HybridConfig) {
        prefs.edit().putBoolean("enabled", config.enabled)
            .putString("baseUrl", config.baseUrl.trimEnd('/'))
            .putString("model", config.model)
            .putString("apiKey", config.apiKey).apply()
    }

    fun config() = HybridConfig(
        enabled = prefs.getBoolean("enabled", true),
        baseUrl = prefs.getString("baseUrl", "https://api.openai.com/v1")!!,
        model = prefs.getString("model", "")!!,
        apiKey = prefs.getString("apiKey", "")!!
    )

    /** Local-first. Cloud is attempted only when enabled and local inference is unavailable/fails. */
    fun generate(prompt: String, maxTokens: Int = 512, imageDataUrl: String? = null): String {
        if (local.isAvailable()) {
            try { return local.generate(prompt, maxTokens, imageDataUrl) }
            catch (e: Exception) {
                if (!config().enabled) throw e
            }
        }
        val c = config()
        if (!c.enabled) throw IllegalStateException("Local llama.cpp server is unavailable and cloud fallback is disabled.")
        require(c.baseUrl.startsWith("https://")) { "Cloud endpoint must use HTTPS." }
        require(c.model.isNotBlank() && c.apiKey.isNotBlank()) { "Configure cloud model and API key in Settings." }
        return requestChatCompletion(c, prompt, maxTokens, imageDataUrl)
    }

    private fun requestChatCompletion(c: HybridConfig, prompt: String, maxTokens: Int, imageDataUrl: String?): String {
        val conn = (URL(c.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${c.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json")
            val content: Any = if (imageDataUrl == null) prompt else JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
            val body = JSONObject()
                .put("model", c.model)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", """You are Omni Termux Assistant. Be helpful and concise. Never claim you completed an action unless the app confirms it. For an action that matches a tool, return ONLY one JSON object with keys "tool", "arguments", "message". Allowed tools: web_search(query), open_url(url), dial_number(number), open_whatsapp(number,message), send_sms(number,message). Do not output shell commands or invent other tools. The app validates the arguments and asks the user before running a tool. For ordinary questions, answer naturally in plain text."""))
                    .put(JSONObject().put("role", "user").put("content", content)))
                .put("max_tokens", maxTokens)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("Cloud API HTTP $code: ${response.take(500)}")
            val root = JSONObject(response)
            return root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally { conn.disconnect() }
    }
}
