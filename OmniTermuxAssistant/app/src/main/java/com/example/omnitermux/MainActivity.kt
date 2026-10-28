package com.example.omnitermux

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.util.Base64
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var prompt: EditText
    private lateinit var output: TextView
    private lateinit var localServer: LocalLlamaServer
    private lateinit var modelStore: ModelStore
    private lateinit var hybrid: HybridInference
    private lateinit var orchestrator: Orchestrator
    private val registry by lazy { ToolRegistry() }
    private val pickModel = 701
    private val pickMmproj = 702
    private val pickImage = 703
    private var imageDataUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localServer = LocalLlamaServer()
        modelStore = ModelStore(this)
        modelStore.exportBundledRuntime()
        modelStore.exportBundledDispatcher()
        hybrid = HybridInference(this, localServer)
        orchestrator = Orchestrator(this, registry)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 24, 22, 20) }
        layout.addView(TextView(this).apply { text = "Omni Termux Assistant"; textSize = 24f })
        status = TextView(this).apply { text = runtimeStatus() }
        layout.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "Paste/import GGUF"; setOnClickListener { chooseModel() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "Import mmproj"; setOnClickListener { chooseMmproj() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(actions)
        layout.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply { text = "Start local AI"; setOnClickListener { startLocalModel() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply { text = "Attach image"; setOnClickListener { chooseImage() } }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@MainActivity).apply { text = "Hybrid settings"; setOnClickListener { showHybridSettings() } }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        layout.addView(TextView(this).apply { text = "Request"; textSize = 18f })
        prompt = EditText(this).apply {
            hint = "Ask a question or request an action…"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        layout.addView(prompt)
        layout.addView(Button(this).apply {
            text = "Run assistant"
            setOnClickListener { submit() }
        })
        layout.addView(TextView(this).apply { text = "Response"; textSize = 18f })
        output = TextView(this).apply { text = "Import the model checkpoint and matching mmproj, set up Termux, then start local AI. Cloud fallback can be configured separately." }
        layout.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)

        modelStore.activeModel()?.let { file ->
            status.text = "Checkpoint imported: ${file.name}; local server ${if (localServer.isAvailable()) "online" else "offline"}; cloud fallback ${if (hybrid.config().enabled) "enabled" else "disabled"}."
        }
    }

    private fun runtimeStatus(): String =
        "Local llama.cpp: ${if (localServer.isAvailable()) "online" else "offline"} · Cloud fallback: ${if (hybrid.config().enabled) "enabled" else "disabled"}"

    private fun chooseModel() {
        chooseGguf(pickModel)
    }

    private fun chooseMmproj() { chooseGguf(pickMmproj) }

    private fun chooseImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(i, pickImage)
    }

    private fun chooseGguf(requestCode: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-gguf"))
        }
        startActivityForResult(i, requestCode)
    }

    private fun startLocalModel() {
        if (modelStore.activeModel() == null || modelStore.activeMmproj() == null) {
            output.text = "Import both the model .gguf and matching mmproj .gguf first. For LFM3-Omni, the projector is needed for multimodal input."
            return
        }
        AlertDialog.Builder(this).setTitle("Start local AI in Termux")
            .setMessage("This starts the bundled llama.cpp runtime through Termux. One-time setup is required: install Termux, run termux-setup-storage, and enable Termux's Allow external apps to run commands option. Import the main GGUF and its matching mmproj first.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { orchestrator.executeRuntime("start_local_model") }
                    output.text = result
                    status.text = runtimeStatus()
                }
            }.show()
    }

    @Deprecated("Legacy activity result API kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == pickImage) {
            lifecycleScope.launch {
                status.text = "Preparing image…"
                try {
                    imageDataUrl = withContext(Dispatchers.IO) { imageToDataUrl(uri) }
                    output.text = "Image attached. Enter a question and tap Run assistant. The image will be sent to the local server first, or to your configured cloud provider if fallback is needed."
                    status.text = runtimeStatus()
                } catch (e: Exception) {
                    imageDataUrl = null
                    output.text = "Could not attach image: ${e.message}"
                    status.text = runtimeStatus()
                }
            }
            return
        }
        if (requestCode != pickModel && requestCode != pickMmproj) return
        lifecycleScope.launch {
            status.text = "Importing GGUF…"
            try {
                val kind = if (requestCode == pickModel) "model" else "mmproj"
                val file = withContext(Dispatchers.IO) {
                    val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ix >= 0 && c.moveToFirst()) c.getString(ix) else "$kind.gguf"
                    }
                    modelStore.import(uri, name, kind)
                }
                status.text = runtimeStatus()
                output.text = "Imported $kind: ${file.name}\nA copy is available to Termux in Download/OmniTermuxAssistant/.\n${if (modelStore.activeModel() != null && modelStore.activeMmproj() != null) "Both GGUF files are ready. Tap Start local AI." else "Import the other GGUF file too."}"
            } catch (e: Exception) {
                status.text = runtimeStatus()
                output.text = "Import failed: ${e.message}"
            }
        }
    }

    private fun imageToDataUrl(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not read image." }
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or invalid image." }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not read image." }
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Unsupported or invalid image.")
        val scaled = if (bitmap.width > 1024 || bitmap.height > 1024) {
            val scale = minOf(1024f / bitmap.width, 1024f / bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        val bytes = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun showHybridSettings() {
        val c = hybrid.config()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val enabled = CheckBox(this).apply { text = "Enable cloud fallback"; isChecked = c.enabled }
        val url = EditText(this).apply { hint = "OpenAI-compatible base URL (HTTPS)"; setText(c.baseUrl); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val model = EditText(this).apply { hint = "Cloud model ID"; setText(c.model) }
        val key = EditText(this).apply { hint = "API key"; setText(c.apiKey); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(enabled); box.addView(url); box.addView(model); box.addView(key)
        AlertDialog.Builder(this).setTitle("Hybrid inference settings").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                hybrid.save(HybridConfig(enabled.isChecked, url.text.toString(), model.text.toString(), key.text.toString()))
                status.text = runtimeStatus()
                output.text = "Settings saved. Cloud fallback sends prompts to the configured provider when local inference is unavailable."
            }.show()
    }

    private fun submit() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) return
        lifecycleScope.launch {
            status.text = "Thinking…"
            val result = withContext(Dispatchers.IO) {
                try { hybrid.generate(text, imageDataUrl = imageDataUrl) }
                catch (e: Exception) { "Inference error: ${e.message}" }
            }
            // The cloud response is displayed as text; it is NOT automatically executed as a tool.
            // Tool execution remains a separate, validated, explicit-confirmation path.
            output.text = result
            imageDataUrl = null
            status.text = runtimeStatus()
            maybeOfferToolPlan(result)
        }
    }

    private fun maybeOfferToolPlan(text: String) {
        val plan = try { JSONObject(text) } catch (_: Exception) { return }
        val toolName = plan.optString("tool", "none")
        if (toolName == "none") return
        val spec = registry.get(toolName) ?: run { output.append("\n\nRejected unknown tool: $toolName"); return }
        val args = plan.optJSONObject("arguments") ?: JSONObject()
        if (!spec.validate(args)) { output.append("\n\nRejected invalid tool arguments."); return }
        AlertDialog.Builder(this).setTitle("Confirm tool call")
            .setMessage("Tool: $toolName\nArguments: $args\n\nExecute this action?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run") { _, _ ->
                lifecycleScope.launch {
                    output.append("\n\n" + orchestrator.execute(spec, args))
                }
            }.show()
    }
}
