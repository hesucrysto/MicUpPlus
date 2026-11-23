package com.micplugin.plugin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class ImportResult(
    val success: Boolean,
    val pluginDescriptor: PluginDescriptor? = null,
    val error: String = "",
)

data class ImportSummary(
    val imported: Int = 0,
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun message(): String = when {
        imported == 0 && failed == 0 -> "No plugin files found"
        failed == 0 -> "Imported $imported file(s)"
        imported == 0 -> "Import failed: ${errors.firstOrNull() ?: "unknown error"}"
        else -> "Imported $imported file(s), $failed failed"
    }
}

object PluginImporter {

    private const val TAG = "PluginImporter"
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TREE_FILES = 600

    /**
     * Called when the user opens a plugin file from a file manager.
     * Copies it into the app-private plugin directory and returns a descriptor.
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        return try {
            val fileName = resolveFileName(context, uri)
                ?: return ImportResult(false, error = "Could not read file name")

            val safeName = sanitize(fileName)
            if (safeName.isBlank()) return ImportResult(false, error = "Invalid file name")

            val format = detectFormatFromName(safeName)
            if (format == null && !safeName.endsWith(".so", ignoreCase = true)) {
                return ImportResult(false, error = "Unknown plugin format: $safeName\nSupported: .so .clap .lv2")
            }

            // Copy through a temp file first so a truncated/oversized stream is rejected
            // before it lands in the live plugin folder.
            val tmp = File(context.cacheDir, "import_${System.nanoTime()}_$safeName")
            val copied = copyUriToFile(context, uri, tmp)
            if (!copied) {
                tmp.delete()
                return ImportResult(false, error = "Could not read file")
            }
            if (tmp.length() > MAX_FILE_BYTES) {
                tmp.delete()
                return ImportResult(false, error = "File too large (max 256 MB)")
            }

            val actualFormat = format ?: detectFormatFromFile(tmp)
                ?: run {
                    tmp.delete()
                    return ImportResult(false, error = "Not a recognised CLAP/LV2/VST3 binary")
                }

            val destDir = destDir(context, actualFormat).also { it.mkdirs() }
            val destFile = File(destDir, safeName)
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }

            Log.i(TAG, "Imported $safeName → ${destFile.absolutePath}")

            val descriptor = PluginDescriptor(
                id = "${actualFormat.name.lowercase()}:${destFile.absolutePath}",
                name = safeName.substringBeforeLast('.').replace('_', ' ').replace('-', ' '),
                format = actualFormat,
                path = destFile.absolutePath,
            )
            ImportResult(true, descriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: $e")
            ImportResult(false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Import a folder tree picked with ACTION_OPEN_DOCUMENT_TREE.
     *
     * Android 11+ blocks /Android/data for file managers and MANAGE_EXTERNAL_STORAGE
     * does not help. SAF is the supported path: the tree is copied into the app's
     * private files/plugins/imported directory where the scanner can always read it.
     */
    fun importTree(context: Context, treeUri: Uri): ImportSummary {
        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (e: Exception) {
            Log.e(TAG, "Tree open failed: $e")
            return ImportSummary(failed = 1, errors = listOf("Could not open folder"))
        } ?: return ImportSummary(failed = 1, errors = listOf("Could not open folder"))

        val rootName = sanitize(root.name ?: "imported_${System.nanoTime()}")
        val destRoot = File(File(context.filesDir, "plugins/imported"), rootName)
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            return ImportSummary(failed = 1, errors = listOf("Could not create import directory"))
        }

        var imported = 0
        var failed = 0
        val errors = mutableListOf<String>()
        var seen = 0
        var totalBytes = 0L

        fun copyTree(dir: DocumentFile, dest: File) {
            for (child in dir.listFiles()) {
                if (seen >= MAX_TREE_FILES) {
                    failed++
                    if (errors.size < 5) errors += "Folder contains more than $MAX_TREE_FILES files"
                    return
                }
                val name = sanitize(child.name ?: continue)
                if (name.isBlank()) continue
                val out = File(dest, name)
                if (child.isDirectory) {
                    out.mkdirs()
                    copyTree(child, out)
                } else {
                    seen++
                    val length = child.length()
                    if (length > MAX_FILE_BYTES || totalBytes + length > MAX_FILE_BYTES * 2) {
                        failed++
                        if (errors.size < 5) errors += "$name: too large"
                        continue
                    }
                    val ok = try {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    } catch (e: Exception) {
                        Log.w(TAG, "Copy failed for ${child.uri}: $e")
                        false
                    }
                    if (ok) {
                        imported++
                        totalBytes += length.coerceAtLeast(0)
                    } else {
                        failed++
                        out.delete()
                        if (errors.size < 5) errors += "$name: copy failed"
                    }
                }
            }
        }

        copyTree(root, destRoot)
        Log.i(TAG, "Tree import: $imported copied, $failed failed → ${destRoot.absolutePath}")
        return ImportSummary(imported, failed, errors)
    }

    fun detectFormatFromName(fileName: String): PluginFormat? = when {
        fileName.endsWith(".clap", ignoreCase = true) -> PluginFormat.CLAP
        fileName.endsWith(".lv2", ignoreCase = true) -> PluginFormat.LV2
        fileName.endsWith(".vst3", ignoreCase = true) -> PluginFormat.VST3
        fileName.endsWith(".so", ignoreCase = true) -> null
        else -> null
    }

    /**
     * Read the first 2 MiB and look for the entry symbols. This is the same
     * detection the scanner uses, and it stops a random .so from being sent to
     * the wrong host (which used to auto-default to VST3 and crash).
     */
    fun detectFormatFromFile(file: File): PluginFormat? {
        return try {
            val readLen = minOf(file.length(), 2L * 1024 * 1024).toInt()
            if (readLen <= 0) return null
            val bytes = ByteArray(readLen)
            file.inputStream().use { it.read(bytes) }
            val text = String(bytes, Charsets.ISO_8859_1)
            when {
                text.contains("clap_entry") -> PluginFormat.CLAP
                text.contains("lv2_descriptor") -> PluginFormat.LV2
                text.contains("GetPluginFactory") -> PluginFormat.VST3
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, out: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            Log.e(TAG, "copyUriToFile failed: $e")
            false
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) return cursor.getString(col)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
            .take(120)

    private fun destDir(context: Context, format: PluginFormat): File {
        val sub = when (format) {
            PluginFormat.LV2 -> "plugins/lv2"
            PluginFormat.CLAP -> "plugins/clap"
            PluginFormat.VST3 -> "plugins/vst3"
            else -> "plugins"
        }
        return File(context.filesDir, sub)
    }
}
