package com.example.omnitermux

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

class ModelStore(private val context: Context) {
    val directory: File = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("model_store", Context.MODE_PRIVATE)

    fun activeModel(): File? = File(directory, "active-model.gguf").takeIf { it.isFile && it.length() > 100_000_000L }
    fun activeMmproj(): File? = File(directory, "active-mmproj.gguf").takeIf { it.isFile && it.length() > 1_000_000L }
    fun modelPublished() = prefs.getBoolean("model_published", false)
    fun mmprojPublished() = prefs.getBoolean("mmproj_published", false)

    fun import(uri: Uri, displayName: String?, kind: String): File {
        require(kind == "model" || kind == "mmproj")
        val name = (displayName ?: "model.gguf").substringAfterLast('/').substringAfterLast('\\')
        require(name.endsWith(".gguf", true)) { "Please select a .gguf file." }
        val target = File(directory, if (kind == "model") "active-model.gguf" else "active-mmproj.gguf")
        val temp = File(directory, "${target.name}.importing")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read selected file." }
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        val minBytes = if (kind == "model") 100_000_000L else 1_000_000L
        require(temp.length() > minBytes) { "Selected file is too small for a $kind GGUF file." }
        // GGUF files start with the ASCII magic bytes GGUF. This catches selecting a ZIP,
        // HTML download page, or unrelated file before handing it to llama.cpp.
        val magic = ByteArray(4)
        temp.inputStream().use { input -> require(input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") {
            "This file does not have the GGUF header. Select a compatible .gguf checkpoint."
        } }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Could not finalize import." }
        publishForTermux(target, if (kind == "model") "active-model.gguf" else "active-mmproj.gguf")
        prefs.edit().putBoolean(if (kind == "model") "model_published" else "mmproj_published", true).apply()
        return target
    }


    fun exportBundledRuntime(): Boolean = exportBundledAsset("llama-runtime.zip")

    fun exportBundledDispatcher(): Boolean = exportBundledAsset("dispatch.sh")

    private fun exportBundledAsset(assetName: String): Boolean {
        return try {
            val temp = File(directory, "$assetName.tmp")
            context.assets.open(assetName).use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            publishForTermux(temp, assetName)
            temp.delete()
            true
        } catch (_: Exception) {
            false // Local source builds may not have CI-produced assets.
        }
    }

    /** Publishes a copy to Download/OmniTermuxAssistant so Termux can read it. */
    private fun publishForTermux(source: File, displayName: String) {
        require(Build.VERSION.SDK_INT >= 29) { "Termux model sharing requires Android 10 or newer." }
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/OmniTermuxAssistant/"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val query = resolver.query(collection, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(displayName, relativePath), null)
        query?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "Could not publish $displayName for Termux." }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Could not open shared model destination." }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
