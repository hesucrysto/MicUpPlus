# 🎙️ MicUp++

A fork of [papergray/MicUp](https://github.com/papergray/MicUp) that fixes the open issues and rebuilds the hosting/plugin layer so the app actually does what the UI claims.

Package: `com.micplugin.plus` — installs alongside upstream MicUp. JNI symbols stay on `com.micplugin` so the native library keeps binding.

> Builds are produced **only** by GitHub Actions. Nothing is compiled on a local machine; the artifact on the Actions run is the APK.

---

## What's fixed

| Issue | Fix |
|---|---|
| **#2** — Shizuku/root routing reported failure | `ShizukuManager` now runs real commands through `Shizuku.newProcess` (public `Shizuku` API 12.2.0, the last release where `newProcess` is public). Root path probes `snd-aloop` through `su`. Routing verification reads `/proc/asound/cards` instead of trusting a hardcoded success. |
| **#5** — external input/output | `Input Device` and `Monitor Output Device` rows in Settings are wired to `OboeEngine` device selection. Monitor playback uses `USAGE_MEDIA`, not call mode. |
| **#16** — compressor knob barely moved | `BCKnob` / `EqBandFader` capture the base value on drag start (`dragBase = currentValue`) and accumulate deltas. The old code re-read a stale composition value each event. |
| **#18** — Discord/Instagram/Recorder routing | The app no longer claims a routed virtual mic that Android cannot provide. `VirtualMicService` reports the tier that is actually active and falls back honestly. Shizuku ADB and root tiers run real probes. |
| **#19** — pitch shifter clicks | Two-tap Hann-crossfade granular shifter with phase-offset read taps, true bypass at 0 semitones, state reset on enable. No more clicks on engage. |
| **#20** — plugin loading / params on Android 11+ storage | Plugin files are imported through the Storage Access Framework into `files/plugins/<format>/…`. `MANAGE_EXTERNAL_STORAGE` and `READ_PHONE_STATE` are gone from the manifest. CLAP parameter automation sends real `clap_event_param_value_t` events with `note_id/port_index/channel/key = -1`. |
| **#7 / #21** | Not actionable (feature request, spam). |

---

## Audio chain

```
mic → Oboe input stream → DSP chain → external plugin chain (CLAP / LV2 / VST3) → Oboe output stream
```

DSP: noise gate, 5-band EQ, compressor, reverb, granular pitch shifter. Every stage has a bypass and the master bypass skips DSP *and* hosted plugins.

### Hosted formats

- **CLAP 1.2.1** — full host struct, per-instance in/out storage, param events with the CLAP 1.2.1 field layout, `try_lock` on the audio thread so a UI edit never blocks the callback.
- **LV2 1.18.10** — URID map, options, TTL port metadata from `manifest.ttl` / `<plugin>.ttl`, control-port defaults.
- **VST3** — local minimal SDK headers, experimental. Parameter control is limited, the UI says so.
- **APK plugins** — the old `AUDIO_PLUGIN` broadcast path exists in the model but is **not** shown in the browser. Hosting an APK inside the audio callback was never going to work; it was removed from the UI rather than left broken.

Chain edits are built on the UI thread into a snapshot; the audio thread swaps it with `try_lock`. Removed plugins are retired, not destroyed under the callback.

---

## Building (GitHub Actions only)

Push to `main`, or run the workflow manually:

```
Actions → Build MicUp++ → Run workflow
```

The workflow:

1. JDK 17 + Android SDK + NDK 26.3.11579264 + CMake 3.22.1
2. `gradle assembleDebug` (Gradle 8.6 — there is no wrapper jar in the repo)
3. Uploads `MicUp++-<version>-debug.apk` as a workflow artifact
4. On a `v*` tag, publishes a GitHub Release with the APK

No keystore secrets required — the debug APK is signed with the standard debug key.

Debug builds are for testing and sideloading. For a Play Store build, add your own keystore via `signingConfigs` and secret-backed `key.properties`.

---

## Plugins on Android 11+

`/Android/data` and most SDK paths are unreadable to a normal app. Two ways in:

1. **Import (recommended)** — Settings → Plugins → Import Plugin Files. Pick `.so` / `.clap` / bundle folders through the SAF picker; they are copied into app-private storage. Imported files are capped (256 MiB per file, 600 files per import) and checked for `clap_entry` / `lv2_descriptor` / VST3 symbols before they are accepted.
2. **Extra scan paths** — Settings → Plugins → Manage Plugin Paths, for directories your device actually exposes.

Default scan root: `filesDir/plugins/{clap,lv2,vst3}`.

---

## Virtual microphone — what Android can and cannot do

The tiers below are the no-root path. For a real system-wide voice changer see **System-wide voice changer (root)** above — that is the only route that makes other apps capture processed audio.

Android does not expose a system-wide virtual input device to third-party apps. There is no public API that makes Discord hear MicUp++. The tiers in Settings mean:

- **VoIP Compatible** — DSP + monitor output. You hear the processed voice; other apps use the raw mic.
- **Software Loopback** — monitor through a selected output device with media usage, no call-mode fiddling.
- **Shizuku (ADB)** — shell-level commands: ALSA loopback probe, appops changes. Works only where the device kernel ships `snd-aloop` and the ROM allows it.
- **Root (Magisk)** — `modprobe snd-aloop`, /proc probe, and appops routing where the kernel supports it.

If the probe fails, the tier is shown as unavailable with the reason. Nothing claims a route it does not have.

---

## System-wide voice changer (root)

Android has no public API that lets an app become another app's microphone. The only real route is a capture-path effect installed into the audio framework — the same mechanism Equalizer APO uses on Windows, translated to Android's effect HAL.

**Settings → System Voice Engine (root) → Install engine.** The app writes a Magisk module and patches the device's own `audio_effects.xml`; reboot once.

What gets installed:

```
/data/adb/modules/micup_plus/
  module.prop
  sepolicy.rule                    # audioserver may read the params file
  post-fs-data.sh                  # creates /data/local/tmp/micup_params.bin
  system/lib64/soundfx/libmicup_effect.so
  system/lib/soundfx/libmicup_effect.so
  system/vendor/etc/audio_effects.xml   # patched copy of the live config
```

Patched config, additive only — existing AEC/NS/AGC entries are untouched:

```xml
<library name="micup" path="libmicup_effect.so"/>
<effect name="micup" library="micup" uuid="6d696375-7000-4d69-6355-700000000001"/>
<preprocess>
  <stream type="voice_communication"><apply effect="micup"/></stream>
  <stream type="mic"><apply effect="micup"/></stream>
</preprocess>
```

Why this works where an app cannot: `audio_effects.xml` is read by `audioserver`, and a `<preprocess>` entry binds the effect to the capture path *below* every app. Discord opens `AUDIO_SOURCE_VOICE_COMMUNICATION`, the framework inserts the effect, and the recorded stream is already transformed.

### Controls

The effect reads `/data/local/tmp/micup_params.bin` — 64 bytes, little-endian, format in `RootEngine.VoiceParams`. The engine polls it ~6 times a second inside `process()`, so the UI can change pitch, body, EQ, drive, reverb, gate and mix live without restarting capture.

Presets: Natural, Deep, Monster, Chipmunk, Radio, Ghost, Robot. Every slider is live; pull the mix down to A/B against the dry mic.

### Limits, stated

- The effect runs on the capture path, so it applies to recordings too, not only calls. That is the point of a system processor; uninstall the module when you do not want it.
- `voice_recognition` is deliberately not patched — Android's compatibility rules forbid preprocessing there.
- Devices whose audio stack ignores `<preprocess>` (some OEM voice paths) will load the library but not insert it. The status panel reports "Config patched" vs "Effect loaded" separately so the difference is visible.
- Uninstall from the same screen and reboot to return to the stock capture path.

---

## Requirements

- Android 8.0+ (API 26), target SDK 34
- ARM64 or ARMv7 device
- Shizuku installed and started (for the ADB tier), Magisk (for the root tier)

---

## Credits

Upstream: [papergray/MicUp](https://github.com/papergray/MicUp).
Libraries: Oboe, CLAP, LV2, Hilt, Compose, kotlinx.serialization, Shizuku API 12.2.0.

Licensed under the same terms as upstream.
