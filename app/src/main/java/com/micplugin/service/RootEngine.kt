package com.micplugin.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root engine installer and control channel for the system capture effect.
 *
 * The effect is libmicup_effect.so, built from cpp/effect/micup_effect.c and
 * shipped inside the APK. Installing it means:
 *   1. copying that .so into a Magisk module next to the soundfx search paths
 *   2. patching the device's audio_effects.xml so audioserver loads it as a
 *      preprocess effect on the mic and voice_communication sources
 *   3. writing sepolicy.rule so audioserver may read the parameter file
 *   4. rebooting
 *
 * Control afterwards is a 64-byte file at /data/local/tmp/micup_params.bin.
 * The effect polls it at ~6 Hz, so slider changes land without touching the
 * audio callback synchronisation.
 */
@Singleton
class RootEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "MicUp.RootEngine"
        const val MODULE_ID = "micup_plus"
        const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
        const val PARAM_PATH = "/data/local/tmp/micup_params.bin"
        const val BOOT_LOG = "/data/local/tmp/micup_boot.log"
        private const val BACKUP_DIR = "/data/adb/micup_plus_backup"
        private const val EFFECT_UUID = "6d696375-7000-4d69-6355-700000000001"
        private const val PARAM_MAGIC = 0x4D555050.toInt() // "MUPP"
        private const val PARAM_VERSION = 1
        private const val PARAM_BYTES = 64

        /** Framework search order: odm wins over vendor, vendor over system. */
        private val CONFIG_CANDIDATES = listOf(
            "/odm/etc/audio_effects.xml",
            "/vendor/etc/audio_effects.xml",
            "/system/etc/audio_effects.xml",
        )

        /** Runs before zygote: the parameter file must exist with shell ownership. */
        private val POST_FS_DATA = """
            #!/system/bin/sh
            touch /data/local/tmp/micup_params.bin 2>/dev/null
            chmod 0644 /data/local/tmp/micup_params.bin 2>/dev/null
            chown 2000:2000 /data/local/tmp/micup_params.bin 2>/dev/null
        """.trimIndent() + "\n"

        /** Runs at late_start: dumps the post-boot state for the app to read. */
        private val SERVICE_SH = """
            #!/system/bin/sh
            sleep 25
            {
                echo "boot report ${'$'}(date)"
                echo ""
                echo "-- overlay in live config --"
                grep -n micup /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml 2>&1 | head -n 20
                echo ""
                echo "-- preprocess block --"
                grep -n -A12 '<preprocess' /vendor/etc/audio_effects.xml 2>&1 | head -n 40
                echo ""
                echo "-- library on device --"
                ls -l /odm/lib64/soundfx/libmicup_effect.so /vendor/lib64/soundfx/libmicup_effect.so /system/lib64/soundfx/libmicup_effect.so 2>&1
                echo ""
                echo "-- parameter file --"
                ls -l /data/local/tmp/micup_params.bin 2>&1
                echo ""
                echo "-- effect log --"
                logcat -d -s MicUpEffect 2>&1 | tail -n 30
            } > /data/local/tmp/micup_boot.log 2>&1
            chmod 0644 /data/local/tmp/micup_boot.log
            chown 2000:2000 /data/local/tmp/micup_boot.log
        """.trimIndent() + "\n"

        private val UNINSTALL_SH = """
            #!/system/bin/sh
            rm -f /data/local/tmp/micup_params.bin
            rm -f /data/local/tmp/micup_boot.log
        """.trimIndent() + "\n"

        private val DIAG_SCRIPT = """
            echo "==== MicUp++ diagnosis ===="
            date
            echo "-- device --"
            getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.display.id
            getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi
            getprop ro.build.type; getprop ro.debuggable
            echo "-- root --"
            id
            echo "-- magisk --"
            magisk -v 2>&1; magisk -V 2>&1
            echo "-- module --"
            ls -l $MODULE_DIR 2>&1
            cat $MODULE_DIR/module.prop 2>&1
            echo "-- module libs --"
            ls -l $MODULE_DIR/system/lib64/soundfx 2>&1
            ls -l $MODULE_DIR/system/vendor/lib64/soundfx 2>&1
            echo "-- configs present --"
            ls -l /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml 2>&1
            echo "-- micup in configs --"
            grep -n micup /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml 2>&1
            echo "-- preprocess block --"
            grep -n -A14 '<preprocess' /vendor/etc/audio_effects.xml 2>&1
            echo "-- stream types --"
            grep -n -o 'stream type="[^"]*"' /vendor/etc/audio_effects.xml 2>&1
            echo "-- library on device --"
            ls -l /odm/lib64/soundfx/libmicup_effect.so /vendor/lib64/soundfx/libmicup_effect.so /system/lib64/soundfx/libmicup_effect.so 2>&1
            echo "-- effect log --"
            logcat -d -s MicUpEffect 2>&1 | tail -n 40
            echo "-- effect related log --"
            logcat -d 2>&1 | grep -i -E 'micup|EffectFactory|effectsfactory' | tail -n 40
            echo "-- audio flinger --"
            dumpsys media.audio_flinger 2>&1 | grep -i -B2 -A4 micup
            echo "-- flinger effects --"
            dumpsys media.audio_flinger 2>&1 | grep -i -A8 'Effects' | head -n 60
            echo "-- avc denials --"
            dmesg 2>/dev/null | grep -i avc | tail -n 20
            echo "-- boot report --"
            cat /data/local/tmp/micup_boot.log 2>&1
            echo "==== end ===="
        """.trimIndent()
    }

    data class ShellResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    data class EngineStatus(
        val rootAvailable: Boolean = false,
        val moduleInstalled: Boolean = false,
        val libInstalled: Boolean = false,
        val patched: Boolean = false,
        val effectLoaded: Boolean = false,
        val paramsFlowing: Boolean = false,
        val configPath: String = "",
        val detail: String = "",
    )

    data class VoiceParams(
        val enabled: Boolean = true,
        val preset: Int = 0,
        val pitch: Float = 0f,
        val body: Float = 0f,
        val lowDb: Float = 0f,
        val midDb: Float = 0f,
        val highDb: Float = 0f,
        val drive: Float = 0f,
        val mix: Float = 1f,
        val gateDb: Float = -60f,
        val compDb: Float = -18f,
        val compRatio: Float = 4f,
        val reverb: Float = 0f,
        val inputGainDb: Float = 0f,
    ) {
        fun toBytes(): ByteArray {
            val b = ByteBuffer.allocate(PARAM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(PARAM_MAGIC)
            b.putInt(PARAM_VERSION)
            b.putInt(if (enabled) 1 else 0)
            b.putInt(preset)
            b.putFloat(pitch)
            b.putFloat(body)
            b.putFloat(lowDb)
            b.putFloat(midDb)
            b.putFloat(highDb)
            b.putFloat(drive)
            b.putFloat(mix)
            b.putFloat(gateDb)
            b.putFloat(compDb)
            b.putFloat(compRatio)
            b.putFloat(reverb)
            b.putFloat(inputGainDb)
            return b.array()
        }
    }

    // ─── Shell ────────────────────────────────────────────────────────────────

    suspend fun su(script: String, timeoutSec: Long = 30): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                ShellResult(-1, "timeout: $out")
            } else {
                ShellResult(process.exitValue(), out.trim())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "su failed: ${t.message}")
            ShellResult(-1, t.message ?: "su unavailable")
        }
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    suspend fun status(): EngineStatus = withContext(Dispatchers.IO) {
        val uid = su("id -u")
        if (!uid.ok || uid.output.trim() != "0") {
            return@withContext EngineStatus(
                rootAvailable = false,
                detail = "Root not granted. Approve the Magisk superuser prompt for MicUp++.",
            )
        }

        val module = su("[ -f $MODULE_DIR/module.prop ] && echo yes || echo no").output.contains("yes")
        val libInstalled = su(
            "ls $MODULE_DIR/system/vendor/lib64/soundfx/libmicup_effect.so " +
                "$MODULE_DIR/system/lib64/soundfx/libmicup_effect.so 2>/dev/null | head -n1"
        ).output.isNotBlank()

        val configs = existingConfigs()
        val patched = configs.any { path ->
            (su("grep -c 'name=\"micup\"' \"$path\" 2>/dev/null").output.trim().toIntOrNull() ?: 0) > 0
        }

        val loaded = (su("logcat -d -s MicUpEffect 2>/dev/null | grep -c 'instance created'")
            .output.trim().toIntOrNull() ?: 0) > 0
        val paramsFlowing = (su("logcat -d -s MicUpEffect 2>/dev/null | grep -c 'params:'")
            .output.trim().toIntOrNull() ?: 0) > 0

        EngineStatus(
            rootAvailable = true,
            moduleInstalled = module,
            libInstalled = libInstalled,
            patched = patched,
            effectLoaded = loaded,
            paramsFlowing = paramsFlowing,
            configPath = configs.firstOrNull().orEmpty(),
            detail = when {
                configs.isEmpty() -> "No audio_effects.xml found (odm/vendor/system)."
                !module -> "Root ready. Engine not installed."
                !libInstalled -> "Module present but the effect library is missing — reinstall."
                !loaded -> "Installed. Reboot, then open a mic app to load it into audioserver."
                !paramsFlowing -> "Effect is loaded and running on defaults. Push a preset to feed it."
                else -> "Engine active on the capture path."
            },
        )
    }

    private suspend fun existingConfigs(): List<String> {
        val out = su("ls -1 /odm/etc/audio_effects.xml /vendor/etc/audio_effects.xml /system/etc/audio_effects.xml 2>/dev/null")
        val present = out.output.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return CONFIG_CANDIDATES.filter { it in present }
    }

    // ─── Install ──────────────────────────────────────────────────────────────

    suspend fun install(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = su("id -u")
            if (!uid.ok || uid.output.trim() != "0") {
                error("Root not granted. Approve the Magisk prompt for MicUp++ and retry.")
            }

            val configs = existingConfigs()
            if (configs.isEmpty()) {
                error("No audio_effects.xml on this device — this ROM hardcodes effects in the audio HAL.")
            }

            val lib = stageEffectLib()
            val cache = context.cacheDir

            // Patch each config the framework might pick, from a pristine stock
            // copy so a previous broken install gets repaired instead of kept.
            val overlays = mutableListOf<Pair<File, String>>()
            configs.forEachIndexed { index, configPath ->
                val backup = backupPathFor(configPath)
                val hasBackup = su("[ -f $backup ] && echo 1 || echo 0").output.trim() == "1"
                val base = if (hasBackup) su("cat \"$backup\"") else su("cat \"$configPath\"")
                if (!base.ok || base.output.isBlank()) error("Cannot read $configPath")
                if (!hasBackup) {
                    val stock = File(cache, "stock_$index.xml").apply { writeText(base.output + "\n") }
                    su(
                        "mkdir -p $BACKUP_DIR && cp \"${stock.absolutePath}\" $backup " +
                            "&& chmod 0644 $backup"
                    )
                }
                val patchedXml = patchConfig(base.output)
                    ?: error("$configPath has no <libraries>/<effects> block — schema not recognised.")
                val file = File(cache, "audio_effects_$index.xml").apply { writeText(patchedXml) }
                overlays += file to overlayPathFor(configPath)
            }

            val propCache = File(cache, "module.prop").apply { writeText(moduleProp()) }
            val sepolicyCache = File(cache, "sepolicy.rule").apply { writeText(sepolicyRule()) }
            val postCache = File(cache, "post-fs-data.sh").apply { writeText(POST_FS_DATA) }
            val serviceCache = File(cache, "service.sh").apply { writeText(SERVICE_SH) }
            val uninstallCache = File(cache, "uninstall.sh").apply { writeText(UNINSTALL_SH) }

            val libDirs = listOf(
                "$MODULE_DIR/system/lib64/soundfx",
                "$MODULE_DIR/system/vendor/lib64/soundfx",
                "$MODULE_DIR/system/odm/lib64/soundfx",
            )

            val script = buildString {
                appendLine("set -e")
                appendLine("rm -rf $MODULE_DIR")
                libDirs.forEach { appendLine("mkdir -p $it") }
                libDirs.forEach {
                    appendLine("cp \"${lib.absolutePath}\" $it/libmicup_effect.so")
                    appendLine("chmod 0644 $it/libmicup_effect.so")
                }
                overlays.forEach { (file, dest) ->
                    appendLine("mkdir -p \"${dest.substringBeforeLast('/')}\"")
                    appendLine("cp \"${file.absolutePath}\" \"$dest\"")
                    appendLine("chmod 0644 \"$dest\"")
                }
                appendLine("cp \"${propCache.absolutePath}\" $MODULE_DIR/module.prop")
                appendLine("cp \"${sepolicyCache.absolutePath}\" $MODULE_DIR/sepolicy.rule")
                appendLine("cp \"${postCache.absolutePath}\" $MODULE_DIR/post-fs-data.sh")
                appendLine("cp \"${serviceCache.absolutePath}\" $MODULE_DIR/service.sh")
                appendLine("cp \"${uninstallCache.absolutePath}\" $MODULE_DIR/uninstall.sh")
                appendLine("chmod 0755 $MODULE_DIR/post-fs-data.sh $MODULE_DIR/service.sh $MODULE_DIR/uninstall.sh")
                appendLine("chmod 0644 $MODULE_DIR/module.prop $MODULE_DIR/sepolicy.rule")
                appendLine("sync")
                appendLine("echo installed")
            }

            val res = su(script, timeoutSec = 90)
            if (!res.ok || !res.output.contains("installed")) {
                error("Install failed: ${res.output.takeLast(400)}")
            }

            // Seed the parameter file so the effect starts with a readable block.
            writeParams(VoiceParams())
            "Installed. Reboot once — audioserver only reads the effect config at boot."
        }
    }

    suspend fun uninstall(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = su(
                "rm -rf $MODULE_DIR && rm -rf $BACKUP_DIR && rm -f $PARAM_PATH && echo removed"
            )
            if (!res.ok) error("Uninstall failed: ${res.output}")
            "Removed. Reboot to restore the stock capture path."
        }
    }

    suspend fun reboot(): Boolean = su("reboot").ok

    // ─── Parameters ───────────────────────────────────────────────────────────

    suspend fun writeParams(params: VoiceParams): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val staged = File(context.cacheDir, "micup_params.bin")
            staged.writeBytes(params.toBytes())
            val res = su(
                "cp \"${staged.absolutePath}\" $PARAM_PATH && " +
                    "chmod 0644 $PARAM_PATH && chown 2000:2000 $PARAM_PATH && sync && echo ok"
            )
            if (!res.ok || !res.output.contains("ok")) {
                error("Could not write parameters: ${res.output.takeLast(200)}")
            }
        }
    }

    // ─── Diagnosis ────────────────────────────────────────────────────────────

    /**
     * Everything needed to tell where the chain broke: root, module, overlay,
     * library path, framework log, effect log, SELinux denials, boot report.
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        su(DIAG_SCRIPT, timeoutSec = 90).output
    }

    // ─── Config patch ─────────────────────────────────────────────────────────

    private fun overlayPathFor(configPath: String): String = when {
        configPath.startsWith("/odm/") -> "$MODULE_DIR/system/odm/etc/audio_effects.xml"
        configPath.startsWith("/vendor/") -> "$MODULE_DIR/system/vendor/etc/audio_effects.xml"
        else -> "$MODULE_DIR/system/etc/audio_effects.xml"
    }

    private fun backupPathFor(configPath: String): String =
        "$BACKUP_DIR/" + configPath.trim('/').replace('/', '_')

    /**
     * Adds the library, the effect and the preprocess apply entries without
     * touching anything the device already declares (AEC, NS, AGC keep working).
     * Idempotent: any previous micup entries are stripped first.
     */
    private fun patchConfig(xmlIn: String): String? {
        var xml = stripMicup(xmlIn)
        val libraryLine = "        <library name=\"micup\" path=\"libmicup_effect.so\"/>"
        val effectLine = "        <effect name=\"micup\" library=\"micup\" uuid=\"$EFFECT_UUID\"/>"

        xml = when {
            xml.contains("</libraries>") ->
                xml.replaceFirst("</libraries>", "$libraryLine\n    </libraries>")
            xml.contains("<libraries/>") || xml.contains("<libraries />") ->
                replaceSelfClosing(xml, "libraries", "<libraries>\n$libraryLine\n    </libraries>")
            else -> return null
        }

        xml = when {
            xml.contains("</effects>") ->
                xml.replaceFirst("</effects>", "$effectLine\n    </effects>")
            xml.contains("<effects/>") || xml.contains("<effects />") ->
                replaceSelfClosing(xml, "effects", "<effects>\n$effectLine\n    </effects>")
            else -> return null
        }

        return mergePreprocess(xml)
    }

    private fun replaceSelfClosing(xml: String, tag: String, replacement: String): String {
        val re = Regex("<$tag\\s*/>")
        val m = re.find(xml) ?: return xml
        return xml.substring(0, m.range.first) + replacement + xml.substring(m.range.last + 1)
    }

    private fun stripMicup(xmlIn: String): String {
        var xml = xmlIn
        xml = Regex("\\s*<apply\\s+effect=\"micup\"\\s*/>").replace(xml, "")
        xml = Regex("\\s*<effect\\s+[^>]*name=\"micup\"[^>]*/>").replace(xml, "")
        xml = Regex("\\s*<library\\s+[^>]*name=\"micup\"[^>]*/>").replace(xml, "")
        return xml
    }

    /** Inserts <apply effect="micup"/> into existing streams, or adds them. */
    private fun mergePreprocess(xml: String): String {
        val open = Regex("<preprocess(\\s[^>]*)?>").find(xml)
        if (open == null) {
            val idx = xml.indexOf("</effects>")
            if (idx < 0) return xml
            val body = buildString {
                appendLine()
                appendLine("    <preprocess>")
                for (type in listOf("voice_communication", "mic")) {
                    appendLine("        <stream type=\"$type\">")
                    appendLine("            <apply effect=\"micup\"/>")
                    appendLine("        </stream>")
                }
                append("    </preprocess>")
            }
            val at = idx + "</effects>".length
            return xml.substring(0, at) + body + xml.substring(at)
        }

        val bodyStart = open.range.last + 1
        val bodyEnd = xml.indexOf("</preprocess>", bodyStart)
        if (bodyEnd < 0) return xml

        val body = xml.substring(bodyStart, bodyEnd)
        val merged = mergeStreams(body)
        return xml.substring(0, bodyStart) + merged + xml.substring(bodyEnd)
    }

    private fun mergeStreams(body: String): String {
        var out = body
        for (type in listOf("voice_communication", "mic")) {
            val tag = findStreamTag(out, type)
            if (tag == null) {
                out = out.trimEnd() + "\n        <stream type=\"$type\">\n" +
                    "            <apply effect=\"micup\"/>\n        </stream>\n    "
            } else {
                val (start, end, selfClosing) = tag
                if (selfClosing) {
                    val replacement = "<stream type=\"$type\">\n" +
                        "            <apply effect=\"micup\"/>\n        </stream>"
                    out = out.substring(0, start) + replacement + out.substring(end + 1)
                } else {
                    val close = out.indexOf("</stream>", end)
                    if (close >= 0) {
                        out = out.substring(0, close) +
                            "            <apply effect=\"micup\"/>\n        " + out.substring(close)
                    }
                }
            }
        }
        return out
    }

    private data class StreamTag(val start: Int, val end: Int, val selfClosing: Boolean)

    private fun findStreamTag(body: String, type: String): StreamTag? {
        var i = body.indexOf("<stream")
        while (i >= 0) {
            val end = body.indexOf('>', i)
            if (end < 0) return null
            val tag = body.substring(i, end + 1)
            if (tag.contains("type=\"$type\"")) {
                return StreamTag(i, end, tag.trimEnd().endsWith("/>"))
            }
            i = body.indexOf("<stream", end)
        }
        return null
    }

    // ─── Native library staging ───────────────────────────────────────────────

    /**
     * Returns a filesystem copy of libmicup_effect.so. With extractNativeLibs=false
     * the APK keeps the library inside the archive and nativeLibraryDir has no
     * readable file, so fall back to pulling the entry out of the APK.
     */
    private fun stageEffectLib(): File {
        val dir = File(context.cacheDir, "engine")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "libmicup_effect.so")
        if (out.exists() && out.length() > 0) return out

        val direct = File(context.applicationInfo.nativeLibraryDir, "libmicup_effect.so")
        if (direct.exists() && direct.length() > 0) {
            direct.copyTo(out, overwrite = true)
            return out
        }

        val abis = android.os.Build.SUPPORTED_ABIS.toList() + listOf("arm64-v8a", "armeabi-v7a")
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val entry = abis.firstNotNullOfOrNull { abi ->
                zip.getEntry("lib/$abi/libmicup_effect.so")
            } ?: error("libmicup_effect.so not in APK for any supported ABI")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    // ─── Module files ─────────────────────────────────────────────────────────

    private fun moduleProp(): String = """
        id=$MODULE_ID
        name=MicUp++ System Voice Engine
        version=2.2.0
        versionCode=20200
        author=MicUp++
        description=Capture-path voice changer for MicUp++. Loads libmicup_effect.so into audioserver through audio_effects.xml.
    """.trimIndent() + "\n"

    private fun sepolicyRule(): String = """
        allow audioserver shell_data_file file { open read getattr map };
        allow audioserver shell_data_file dir { search open read getattr };
    """.trimIndent() + "\n"
}
