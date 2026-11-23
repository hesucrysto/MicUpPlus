package com.micplugin.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathPrefs: PluginPathPrefs,
) {
    companion object {
        private const val TAG = "PluginManager"
        const val APK_PLUGIN_ACTION = "com.micplugin.AUDIO_PLUGIN"

        /** ELF symbol names are stable and cheap to find in the first MiB. */
        private const val SYMBOL_CLAP = "clap_entry"
        private const val SYMBOL_LV2 = "lv2_descriptor"
        private const val SYMBOL_VST3 = "GetPluginFactory"
    }

    private val _discovered = MutableStateFlow<List<PluginDescriptor>>(emptyList())
    val discovered: StateFlow<List<PluginDescriptor>> = _discovered

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    suspend fun scanAll() = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, PluginDescriptor>()

        // One walk over every configured root. The old code walked the shared
        // "plugins" root once per format, which produced duplicate entries and
        // classified every .so as the wrong format.
        val roots = PluginFormat.values()
            .filter { it != PluginFormat.APK }
            .flatMap { pathPrefs.allPaths(context, it) }
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
            .distinctBy { it.absolutePath }
            .filter { it.exists() }

        for (root in roots) {
            root.walkTopDown()
                .onFail { file, e -> Log.w(TAG, "Skipping unreadable path ${file.absolutePath}: $e") }
                .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
                .forEach { so ->
                    val format = detectFormat(so, root) ?: return@forEach
                    if (!isLoadable(so, format)) return@forEach
                    val canonical = runCatching { so.canonicalPath }.getOrElse { so.absolutePath }

                    val bundle = bundleRootFor(so)
                    val display = (bundle ?: so).name
                        .removeSuffix(".lv2").removeSuffix(".clap").removeSuffix(".vst3")
                        .removeSuffix(".so")
                        .replace('_', ' ').replace('-', ' ')

                    val descriptor = PluginDescriptor(
                        id = "${format.name.lowercase()}:$canonical",
                        name = display.ifBlank { "Unnamed plugin" },
                        format = format,
                        path = so.absolutePath,
                        params = if (format == PluginFormat.LV2) parseLv2TtlParams(so.absolutePath) else emptyList(),
                        description = bundle?.let { "Bundle: ${it.name}" }.orEmpty(),
                    )
                    found[canonical] = descriptor
                }
        }

        found.putAll(scanApkPlugins().associateBy { it.id })
        _discovered.value = found.values.sortedWith(compareBy({ it.format.ordinal }, { it.name.lowercase() }))
        _scanError.value = null
        Log.i(TAG, "Scan complete: ${found.size} plugins")
        Log.i(TAG, "Roots: ${roots.map { it.absolutePath }}")
    }

    fun reportScanError(message: String) {
        _scanError.value = message
    }

    private fun detectFormat(so: File, root: File): PluginFormat? {
        // Directory layout is the strongest hint: plugins/lv2, plugins/clap, plugins/vst3.
        val path = so.absolutePath.lowercase()
        if (path.contains("/lv2/") || path.endsWith(".lv2.so")) return PluginFormat.LV2
        if (path.contains("/clap/") || path.endsWith(".clap.so")) return PluginFormat.CLAP
        if (path.contains("/vst3/") || path.contains(".vst3/")) return PluginFormat.VST3

        // Bundle ancestor (xxx.lv2/xxx.so) is the next hint.
        var p: File? = so.parentFile
        var guard = 0
        while (p != null && guard++ < 6) {
            when (p.extension.lowercase()) {
                "lv2" -> return PluginFormat.LV2
                "clap" -> return PluginFormat.CLAP
                "vst3" -> return PluginFormat.VST3
            }
            if (p == root) break
            p = p.parentFile
        }

        // Last resort: inspect the ELF's dynamic string table for entry symbols.
        return detectFormatFromSymbols(so)
    }

    private fun detectFormatFromSymbols(file: File): PluginFormat? {
        return try {
            val length = file.length()
            if (length <= 0 || length > 256L * 1024 * 1024) return null
            val readLen = minOf(length, 2L * 1024 * 1024).toInt()
            val bytes = ByteArray(readLen)
            file.inputStream().use { it.read(bytes) }
            val text = String(bytes, Charsets.ISO_8859_1)
            when {
                text.contains(SYMBOL_CLAP) -> PluginFormat.CLAP
                text.contains(SYMBOL_LV2) -> PluginFormat.LV2
                text.contains(SYMBOL_VST3) -> PluginFormat.VST3
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Symbol scan failed for ${file.absolutePath}: $e")
            null
        }
    }

    private fun isLoadable(so: File, format: PluginFormat): Boolean {
        // VST3 binaries need a Linux/Android VST3 build. Do not offer random
        // desktop .so files that happen to contain the symbol name.
        if (format == PluginFormat.VST3) {
            val path = so.absolutePath.lowercase()
            return path.contains(".vst3/") || path.contains("/vst3/")
        }
        return so.canRead()
    }

    private fun bundleRootFor(so: File): File? {
        var p: File? = so.parentFile
        var guard = 0
        while (p != null && guard++ < 6) {
            if (p.extension.lowercase() in setOf("lv2", "clap", "vst3")) return p
            p = p.parentFile
        }
        return null
    }

    fun scanApkPlugins(): List<PluginDescriptor> {
        val pm = context.packageManager
        val intent = Intent(APK_PLUGIN_ACTION)
        val svcs = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return svcs.mapNotNull { info ->
            runCatching {
                val pkg = info.serviceInfo.packageName
                val label = pm.getPackageInfo(pkg, 0).applicationInfo
                    ?.let { pm.getApplicationLabel(it).toString() } ?: pkg
                PluginDescriptor(id = "apk:$pkg", name = label, format = PluginFormat.APK, path = pkg)
            }.getOrNull()
        }
    }

    fun parseApkPluginInfo(json: String): List<PluginParam> {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val params = obj["params"]?.jsonArray ?: return emptyList()
            params.map { el ->
                val p = el.jsonObject
                PluginParam(
                    id = p["id"]?.jsonPrimitive?.int ?: 0,
                    name = p["name"]?.jsonPrimitive?.content ?: "Param",
                    min = p["min"]?.jsonPrimitive?.float ?: 0f,
                    max = p["max"]?.jsonPrimitive?.float ?: 1f,
                    default = p["default"]?.jsonPrimitive?.float ?: 0f,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin info: $e")
            emptyList()
        }
    }

    private fun parseLv2TtlParams(soPath: String): List<PluginParam> {
        val so = File(soPath)
        val dir = so.parentFile ?: return emptyList()
        val stem = so.nameWithoutExtension
        val bundle = bundleRootFor(so)

        val candidates = buildList {
            if (bundle != null) {
                add(File(bundle, "manifest.ttl"))
                add(File(bundle, "$stem.ttl"))
                add(File(bundle, "${bundle.nameWithoutExtension}.ttl"))
            }
            add(File(dir, "$stem.ttl"))
            add(File(dir, "manifest.ttl"))
            add(File(dir, "plugin.ttl"))
        }.distinctBy { it.absolutePath }

        val ttl = candidates.mapNotNull { p -> p.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } }
            .firstOrNull() ?: return emptyList()

        val params = mutableListOf<PluginParam>()
        var inPort = false
        var idx = -1; var nm = ""; var mn = 0f; var mx = 1f; var df = 0f
        var isCtrl = false; var isInput = false

        for (line in ttl.lines()) {
            val t = line.trim()
            if (t.startsWith("[")) {
                if (inPort && idx >= 0 && isCtrl && isInput)
                    params += PluginParam(idx, nm.ifEmpty { "p$idx" }, mn, mx, df)
                inPort = true; idx = -1; nm = ""; mn = 0f; mx = 1f; df = 0f
                isCtrl = false; isInput = false
                if (t.contains("]")) inPort = false
                continue
            }
            if (t == "]") {
                if (inPort && idx >= 0 && isCtrl && isInput)
                    params += PluginParam(idx, nm.ifEmpty { "p$idx" }, mn, mx, df)
                inPort = false; continue
            }
            if (!inPort) continue
            if (t.contains("lv2:ControlPort")) isCtrl = true
            if (t.contains("lv2:InputPort")) isInput = true
            if (t.contains("lv2:AudioPort")) isCtrl = false
            t.split("lv2:index").getOrNull(1)?.trim()?.split(Regex("\\s"))
                ?.firstOrNull()?.trimEnd(';', ',', '.')?.toIntOrNull()?.let { idx = it }
            if (nm.isEmpty() && t.contains("lv2:name")) {
                val q = t.indexOf('"'); val q2 = t.lastIndexOf('"')
                if (q >= 0 && q2 > q) nm = t.substring(q + 1, q2)
            }
            t.split("lv2:minimum").getOrNull(1)?.trim()?.split(Regex("\\s"))
                ?.firstOrNull()?.trimEnd(';', ',', '.')?.toFloatOrNull()?.let { mn = it }
            t.split("lv2:maximum").getOrNull(1)?.trim()?.split(Regex("\\s"))
                ?.firstOrNull()?.trimEnd(';', ',', '.')?.toFloatOrNull()?.let { mx = it }
            t.split("lv2:default").getOrNull(1)?.trim()?.split(Regex("\\s"))
                ?.firstOrNull()?.trimEnd(';', ',', '.')?.toFloatOrNull()?.let { df = it }
        }
        Log.i(TAG, "TTL scan: ${params.size} params from $soPath")
        return params
    }
}
