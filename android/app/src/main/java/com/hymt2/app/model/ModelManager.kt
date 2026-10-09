package com.hymt2.app.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Finds GGUF models in the app's private/external dirs and imports user-picked files. */
class ModelManager(private val context: Context) {

    /** Directories searched, in priority order. The first is where `adb push` should target. */
    fun searchDirs(): List<File> = listOfNotNull(
        context.getExternalFilesDir(null),
        context.filesDir,
        File("/data/local/tmp/hymt2"),
    )

    fun available(): List<ModelEntry> {
        val seen = HashSet<String>()
        return searchDirs().flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("gguf", true) }.orEmpty().toList()
        }.mapNotNull { file ->
            val spec = KnownModels.forFile(file.name)
                ?: ModelSpec(file.nameWithoutExtension, file.nameWithoutExtension, listOf(file.name), "?", false)
            if (seen.add(spec.id)) ModelEntry(spec, file) else null
        }.sortedBy { e -> KnownModels.all.indexOfFirst { it.id == e.spec.id }.let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** Copies a SAF-picked file into the app files dir. Blocking; call off the main thread. */
    fun import(uri: Uri): Result<File> = runCatching {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "imported.gguf"
        val dest = File(context.filesDir, name)
        val tmp = File(context.filesDir, "$name.part")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        check(tmp.renameTo(dest)) { "could not move imported file" }
        dest
    }
}
