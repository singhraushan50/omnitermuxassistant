package com.example.omnitermux

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Orchestrator(private val context: Context, private val registry: ToolRegistry) {
    suspend fun execute(spec: ToolSpec, args: JSONObject): String = withContext(Dispatchers.Main) {
        when (spec.name) {
            "open_url" -> {
                val url = args.optString("url")
                if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext "Rejected: URL must use http or https."
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Browser opened."
            }
            "web_search" -> {
                val query = args.optString("query").trim()
                if (query.isBlank() || query.length > 500) return@withContext "Rejected: invalid search query."
                val url = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Search opened in browser."
            }
            "dial_number" -> {
                val number = args.optString("number")
                if (!number.matches(Regex("^[+0-9*#() -]{3,30}$"))) return@withContext "Rejected: invalid phone number."
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Dialer opened. No call was placed."
            }
            "open_whatsapp" -> {
                val number = args.optString("number").filter { it.isDigit() || it == '+' }.removePrefix("+")
                val message = args.optString("message")
                if (!number.matches(Regex("^[0-9]{7,20}$")) || message.length > 2000) return@withContext "Rejected: invalid WhatsApp recipient or message."
                val url = Uri.parse("https://wa.me/$number?text=" + Uri.encode(message))
                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "WhatsApp chat opened with a prefilled message. Review it and tap Send yourself."
            }
            "send_sms" -> sendToTermux(spec.name, args)
            else -> "Rejected: tool not registered."
        }
    }

    suspend fun executeRuntime(command: String): String = withContext(Dispatchers.IO) {
        if (command != "start_local_model" && command != "stop_local_model") return@withContext "Rejected runtime command."
        sendToTermux(command, JSONObject())
    }

    private fun sendToTermux(tool: String, args: JSONObject): String {
        // Termux:Tasker plugin / RunCommandService is an explicit user-installed dependency.
        // Do not pass arbitrary model-generated shell text. A fixed dispatcher receives JSON args.
        // Run the dispatcher through Termux's own bash. The dispatcher lives in shared
        // Downloads (noexec filesystem), so it must not be used as RUN_COMMAND_PATH directly.
        val termuxHome = "/data/data/com.termux/files/home"
        val termuxBash = "/data/data/com.termux/files/usr/bin/bash"
        val dispatcher = "$termuxHome/storage/downloads/OmniTermuxAssistant/dispatch.sh"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", termuxBash)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(dispatcher, tool, args.toString()))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", termuxHome)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        return try {
            context.startService(intent)
            if (tool == "start_local_model") "Start request sent to Termux. Allow up to 90 seconds for LFM3-Omni to load. If it fails, open Termux and inspect ~/omni-tools/server.log." else "Command dispatched to Termux. The current integration reports dispatch status, not a verified tool result."
        } catch (e: Exception) {
            "Could not start Termux command service: ${e.message}. Install Termux and enable its RunCommand permission."
        }
    }
}
