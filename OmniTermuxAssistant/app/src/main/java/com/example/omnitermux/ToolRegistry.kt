package com.example.omnitermux

import org.json.JSONObject

data class ToolSpec(
    val name: String,
    val description: String,
    val required: Set<String>,
    val allowedKeys: Set<String>,
    val command: String
) {
    fun validate(args: JSONObject): Boolean {
        val names = args.names()
        val keys = if (names == null) emptySet() else (0 until names.length()).map { names.optString(it) }.toSet()
        if (!keys.containsAll(required) || keys.any { it !in allowedKeys }) return false
        return when (name) {
            "web_search" -> args.optString("query").isNotBlank() && args.optString("query").length <= 500
            "open_url" -> args.optString("url").let { it.startsWith("https://") || it.startsWith("http://") }
            "dial_number" -> args.optString("number").matches(Regex("^[+0-9*#() -]{3,30}$"))
            "open_whatsapp" -> args.optString("number").matches(Regex("^[+0-9]{7,20}$")) && args.optString("message").length <= 2000
            "send_sms" -> args.optString("number").matches(Regex("^[+0-9*#() -]{3,30}$")) && args.optString("message").isNotBlank() && args.optString("message").length <= 2000
            else -> false
        }
    }
}

class ToolRegistry {
    private val specs = listOf(
        ToolSpec("web_search", "Open a web search in the browser",
            setOf("query"), setOf("query"), "web_search"),
        ToolSpec("open_url", "Open a URL in the Android browser",
            setOf("url"), setOf("url"), "open_url"),
        ToolSpec("dial_number", "Open the phone dialer with a number filled in; does not place a call",
            setOf("number"), setOf("number"), "dial_number"),
        ToolSpec("open_whatsapp", "Open WhatsApp with a recipient and prefilled message; user taps Send",
            setOf("number", "message"), setOf("number", "message"), "open_whatsapp"),
        ToolSpec("send_sms", "Send an SMS through Termux:API (user confirmation required)",
            setOf("number", "message"), setOf("number", "message"), "send_sms")
    ).associateBy { it.name }

    fun get(name: String): ToolSpec? = specs[name]
    fun schemas(): List<JSONObject> = specs.values.map {
        JSONObject().put("name", it.name).put("description", it.description)
            .put("required", it.required.toList()).put("arguments", it.allowedKeys.toList())
    }
}
