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
 * The effect itself is libmicup_effect.so, built from cpp/effect/micup_effect.c
 * and shipped inside the APK. Installing it means:
 *   1. copying that .so into a Magisk module under system/lib64/soundfx
 *   2. patching the device's audio_effects.xml so audioserver loads it as a
 *      preprocess effect for the mic and voice_communication sources
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
        private const val EFFECT_UUID = "6d696375-7000-4d69-6355-700000000001"
        private const val PARAM_MAGIC = 0x4D555050.toInt() // "MUPP"
        private const val PARAM_VERSION = 1
        private const val PARAM_BYTES = 64
    }

    data class ShellResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    data class EngineStatus(
        val rootAvailable: Boolean = false,
        val moduleInstalled: Boolean = false,
        val patched: Boolean = false,
        val effectLoaded: Boolean = false,
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

    suspend fun su(script: String, timeoutSec: Long = 25): ShellResult = withContext(Dispatchers.IO) {
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
        val id = su("id -u")
        if (!id.ok || id.output.trim() != "0") {
            return@withContext EngineStatus(
                rootAvailable = false,
                detail = "Root not granted. Magisk/KernelSU su is required.",
            )
        }

        val module = su("test -f $MODULE_DIR/module.prop && echo yes || echo no")
        val installed = module.output.contains("yes")
        val config = su(
            "for f in /vendor/etc/audio_effects.xml /odm/etc/audio_effects.xml /system/etc/audio_effects.xml; " +
                "do [ -f \"\$f\" ] && echo \"\$f\" && break; done"
        ).output.lines().firstOrNull().orEmpty()

        val livePatched = if (config.isNotEmpty()) {
            su("grep -c 'name=\"micup\"' \"$config\" 2>/dev/null || true").output.trim().toIntOrNull()?.let { it > 0 } ?: false
        } else false

        val loaded = su("logcat -d -s MicUpEffect 2>/dev/null | grep -c 'instance created' || true")
            .output.trim().toIntOrNull()?.let { it > 0 } ?: false

        EngineStatus(
            rootAvailable = true,
            moduleInstalled = installed,
            patched = livePatched,
            effectLoaded = loaded,
            configPath = config,
            detail = when {
                config.isEmpty() -> "No audio_effects.xml found; this ROM uses a different effect config."
                !installed -> "Root ready. Engine not installed."
                !livePatched -> "Module installed. Reboot to activate."
                loaded -> "Engine active on the capture path."
                else -> "Config patched. Open a mic app; the effect loads on first capture."
            },
        )
    }

    // ─── Install ──────────────────────────────────────────────────────────────

    suspend fun install(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val id = su("id -u")
            if (!id.ok || id.output.trim() != "0") error("Root not granted")

            val configPath = su(
                "for f in /vendor/etc/audio_effects.xml /odm/etc/audio_effects.xml /system/etc/audio_effects.xml; " +
                    "do [ -f \"\$f\" ] && echo \"\$f\" && break; done"
            ).output.lines().firstOrNull().orEmpty()
            if (configPath.isEmpty()) error("No audio_effects.xml on this device")

            val original = su("cat \"$configPath\"")
            if (!original.ok) error("Cannot read $configPath")
            val patched = patchConfig(original.output)
            if (patched == null) error("audio_effects.xml has no <libraries> or <effects> section")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val lib64 = stageEffectLib()
            if (!lib64.exists()) error("libmicup_effect.so missing from the APK")

            // stage everything in app cache, then let root copy it into place
            val cache = context.cacheDir
            val xmlCache = File(cache, "audio_effects.xml").apply { writeText(patched) }
            val propCache = File(cache, "module.prop").apply { writeText(moduleProp()) }
            val sepolicyCache = File(cache, "sepolicy.rule").apply { writeText(sepolicyRule()) }
            val postCache = File(cache, "post-fs-data.sh").apply {
                writeText(
                    """
                    #!/system/bin/sh
                    # Parameters live in shell_data_file; audioserver reads them read-only.
                    touch /data/local/tmp/micup_params.bin 2>/dev/null
                    chmod 0644 /data/local/tmp/micup_params.bin 2>/dev/null
                    chown 2000:2000 /data/local/tmp/micup_params.bin 2>/dev/null
                    """.trimIndent()
                )
            }
            val uninstallCache = File(cache, "uninstall.sh").apply {
                writeText("#!/system/bin/sh\nrm -f /data/local/tmp/micup_params.bin\n")
            }

            val overlayPath = when {
                configPath.startsWith("/vendor/") -> "$MODULE_DIR/system/vendor/etc/audio_effects.xml"
                configPath.startsWith("/odm/") -> "$MODULE_DIR/system/odm/etc/audio_effects.xml"
                else -> "$MODULE_DIR/system/etc/audio_effects.xml"
            }

            val script = """
                set -e
                rm -rf $MODULE_DIR
                mkdir -p $MODULE_DIR/system/lib64/soundfx
                mkdir -p $MODULE_DIR/system/lib/soundfx
                mkdir -p "$(dirname "$overlayPath")"
                cp "${lib64.absolutePath}" $MODULE_DIR/system/lib64/soundfx/libmicup_effect.so
                cp "${lib64.absolutePath}" $MODULE_DIR/system/lib/soundfx/libmicup_effect.so
                cp "${xmlCache.absolutePath}" "$overlayPath"
                cp "${propCache.absolutePath}" $MODULE_DIR/module.prop
                cp "${sepolicyCache.absolutePath}" $MODULE_DIR/sepolicy.rule
                cp "${postCache.absolutePath}" $MODULE_DIR/post-fs-data.sh
                cp "${uninstallCache.absolutePath}" $MODULE_DIR/uninstall.sh
                chmod 0755 $MODULE_DIR/post-fs-data.sh $MODULE_DIR/uninstall.sh
                chmod 0644 $MODULE_DIR/system/lib64/soundfx/libmicup_effect.so
                chmod 0644 $MODULE_DIR/system/lib/soundfx/libmicup_effect.so
                chmod 0644 "$overlayPath"
                echo installed
            """.trimIndent()

            val res = su(script, timeoutSec = 60)
            if (!res.ok || !res.output.contains("installed")) {
                error("Install failed: ${res.output.takeLast(400)}")
            }
            "Installed. Reboot to load the effect into audioserver."
        }
    }

    suspend fun uninstall(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val res = su("rm -rf $MODULE_DIR && rm -f $PARAM_PATH && echo removed")
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
                    "chmod 0644 $PARAM_PATH && chown 2000:2000 $PARAM_PATH && echo ok"
            )
            if (!res.ok || !res.output.contains("ok")) {
                error("Could not write parameters: ${res.output.takeLast(200)}")
            }
        }
    }

    // ─── Config patch ─────────────────────────────────────────────────────────

    /**
     * Returns a filesystem copy of libmicup_effect.so. With extractNativeLibs=false
     * the APK keeps the library compressed inside the archive and nativeLibraryDir
     * has no readable file, so fall back to pulling the entry out of the APK.
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

    /**
     * Adds the library, the effect and the preprocess entries without touching
     * anything the device already declares (AEC, NS, AGC keep working).
     * Returns null when the file does not look like audio_effects.xml.
     */
    private fun patchConfig(xml: String): String? {
        if (xml.contains("name=\"micup\"")) return xml
        if (!xml.contains("</libraries>") || !xml.contains("</effects>")) return null

        var out = xml.replaceFirst(
            "</libraries>",
            "    <library name=\"micup\" path=\"libmicup_effect.so\"/>\n    </libraries>",
        )
        out = out.replaceFirst(
            "</effects>",
            "        <effect name=\"micup\" library=\"micup\" uuid=\"$EFFECT_UUID\"/>\n    </effects>",
        )

        val streams = buildString {
            append("        <stream type=\"voice_communication\">\n")
            append("            <apply effect=\"micup\"/>\n")
            append("        </stream>\n")
            append("        <stream type=\"mic\">\n")
            append("            <apply effect=\"micup\"/>\n")
            append("        </stream>\n")
        }

        out = if (out.contains("<preprocess")) {
            val re = Regex("<preprocess[^>]*>")
            val m = re.find(out)
            if (m == null) out
            else out.substring(0, m.range.first) + m.value + "\n" + streams +
                out.substring(m.range.last + 1)
        } else {
            out.replaceFirst(
                "</audio_effects_conf>",
                "    <preprocess>\n$streams    </preprocess>\n</audio_effects_conf>",
            )
        }
        return out
    }

    private fun moduleProp(): String = """
        id=$MODULE_ID
        name=MicUp++ System Voice Engine
        version=2.0.0
        versionCode=20000
        author=MicUp++
        description=Capture-path voice changer for MicUp++. Loads libmicup_effect.so into audioserver through audio_effects.xml.
    """.trimIndent() + "\n"

    private fun sepolicyRule(): String = """
        allow audioserver shell_data_file file { open read getattr };
        allow audioserver shell_data_file dir { search open read };
    """.trimIndent() + "\n"
}
