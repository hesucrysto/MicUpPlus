package com.micplugin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.micplugin.service.RootEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoicePreset(
    val name: String,
    val pitch: Float,
    val body: Float,
    val low: Float,
    val mid: Float,
    val high: Float,
    val drive: Float,
    val mix: Float,
    val reverb: Float,
)

private val PRESETS = listOf(
    VoicePreset("Natural",   0f,   0f,  0f,  0f,  0f, 0.00f, 1.00f, 0.00f),
    VoicePreset("Deep",     -7f,  -4f,  4f, -1f, -3f, 0.15f, 1.00f, 0.04f),
    VoicePreset("Monster", -12f,  -7f,  7f, -2f, -5f, 0.60f, 1.00f, 0.10f),
    VoicePreset("Chipmunk",  9f,   6f, -3f,  2f,  5f, 0.00f, 1.00f, 0.00f),
    VoicePreset("Radio",     0f,   2f, -6f,  5f, -4f, 0.45f, 1.00f, 0.00f),
    VoicePreset("Ghost",    -5f,  -2f, -2f,  1f,  3f, 0.05f, 0.85f, 0.45f),
    VoicePreset("Robot",     0f,   0f, -4f,  6f, -2f, 0.85f, 1.00f, 0.05f),
)

@HiltViewModel
class VoiceChangerViewModel @Inject constructor(
    private val rootEngine: RootEngine,
) : ViewModel() {

    var status by mutableStateOf(RootEngine.EngineStatus())
        private set
    var params by mutableStateOf(RootEngine.VoiceParams())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            status = rootEngine.status()
        }
    }

    fun update(block: (RootEngine.VoiceParams) -> RootEngine.VoiceParams) {
        params = block(params)
    }

    fun pushParams() {
        viewModelScope.launch {
            rootEngine.writeParams(params).onFailure { message = it.message ?: "write failed" }
        }
    }

    fun selectPreset(p: VoicePreset, index: Int) {
        params = params.copy(
            preset = index,
            pitch = p.pitch,
            body = p.body,
            lowDb = p.low,
            midDb = p.mid,
            highDb = p.high,
            drive = p.drive,
            mix = p.mix,
            reverb = p.reverb,
        )
        pushParams()
    }

    fun install() = runBlockingAction { rootEngine.install().getOrThrow() }
    fun uninstall() = runBlockingAction { rootEngine.uninstall().getOrThrow() }
    fun reboot() = runBlockingAction { if (rootEngine.reboot()) "Rebooting" else error("reboot failed") }

    private fun runBlockingAction(block: suspend () -> String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                message = block()
            } catch (t: Throwable) {
                message = t.message ?: "failed"
            } finally {
                busy = false
                refresh()
            }
        }
    }
}

@Composable
fun VoiceChangerScreen(navController: NavController, vm: VoiceChangerViewModel = hiltViewModel()) {
    val status = vm.status
    val params = vm.params

    Column(
        Modifier
            .fillMaxSize()
            .background(StudioColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        Surface(color = StudioColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = StudioColors.TextPrimary)
                }
                Text(
                    "SYSTEM VOICE ENGINE",
                    style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary,
                    letterSpacing = 2.sp,
                )
            }
        }

        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = StudioColors.Card,
                border = BorderStroke(0.5.dp, StudioColors.Border),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusLine("Root", status.rootAvailable)
                    StatusLine("Module", status.moduleInstalled)
                    StatusLine("Config patched", status.patched)
                    StatusLine("Effect loaded", status.effectLoaded)
                    if (status.configPath.isNotEmpty()) {
                        Text(
                            status.configPath,
                            fontSize = 9.sp,
                            color = StudioColors.TextMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (status.detail.isNotEmpty()) {
                        Text(status.detail, fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.install() },
                    enabled = !vm.busy && status.rootAvailable,
                    modifier = Modifier.weight(1f),
                ) { Text("Install engine", fontSize = 11.sp) }
                OutlinedButton(
                    onClick = { vm.reboot() },
                    enabled = !vm.busy && status.rootAvailable,
                    modifier = Modifier.weight(1f),
                ) { Text("Reboot", fontSize = 11.sp) }
            }
            if (status.moduleInstalled) {
                TextButton(onClick = { vm.uninstall() }, enabled = !vm.busy) {
                    Text("Uninstall engine", color = StudioColors.MeterRed, fontSize = 11.sp)
                }
            }
            if (vm.message.isNotEmpty()) {
                Text(vm.message, fontSize = 10.sp, color = StudioColors.MeterAmber, lineHeight = 14.sp)
            }

            Text("PRESETS", fontSize = 9.sp, color = StudioColors.TextMuted, letterSpacing = 1.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.chunked(2).forEachIndexed { rowIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { colIdx, p ->
                            val index = rowIdx * 2 + colIdx
                            val selected = params.preset == index
                            OutlinedButton(
                                onClick = { vm.selectPreset(p, index) },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(
                                    0.5.dp,
                                    if (selected) StudioColors.Accent else StudioColors.Border,
                                ),
                                contentPadding = PaddingValues(vertical = 4.dp),
                            ) {
                                Text(
                                    p.name,
                                    fontSize = 10.sp,
                                    color = if (selected) StudioColors.Accent else StudioColors.TextPrimary,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text("SHAPE", fontSize = 9.sp, color = StudioColors.TextMuted, letterSpacing = 1.sp)
            VoiceSlider("Pitch", params.pitch, -24f, 24f, "%.1f st", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(pitch = it) }
            }
            VoiceSlider("Body", params.body, -12f, 12f, "%.1f", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(body = it) }
            }
            VoiceSlider("Low", params.lowDb, -18f, 18f, "%.1f dB", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(lowDb = it) }
            }
            VoiceSlider("Mid", params.midDb, -18f, 18f, "%.1f dB", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(midDb = it) }
            }
            VoiceSlider("High", params.highDb, -18f, 18f, "%.1f dB", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(highDb = it) }
            }
            VoiceSlider("Drive", params.drive, 0f, 1f, "%.2f", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(drive = it) }
            }
            VoiceSlider("Reverb", params.reverb, 0f, 1f, "%.2f", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(reverb = it) }
            }
            VoiceSlider("Mix", params.mix, 0f, 1f, "%.2f", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(mix = it) }
            }
            VoiceSlider("Gate", params.gateDb, -80f, 0f, "%.0f dB", onFinished = { vm.pushParams() }) {
                vm.update { p -> p.copy(gateDb = it) }
            }

            Button(
                onClick = { vm.pushParams() },
                enabled = status.rootAvailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Push to engine", fontSize = 12.sp) }

            Text(
                "The effect runs inside audioserver as a preprocess stage, so every app that " +
                    "opens the microphone captures the processed voice. Changes are written to " +
                    "/data/local/tmp/micup_params.bin and picked up within ~200 ms.",
                fontSize = 9.sp,
                color = StudioColors.TextMuted,
                lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (on) StudioColors.Accent else StudioColors.TextMuted,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, color = StudioColors.TextPrimary)
    }
}

@Composable
private fun VoiceSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onFinished: () -> Unit = {},
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 10.sp, color = StudioColors.TextMuted)
            Text(format.format(value), fontSize = 10.sp, color = StudioColors.TextPrimary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = StudioColors.Accent,
                activeTrackColor = StudioColors.Accent,
                inactiveTrackColor = StudioColors.Border,
            ),
            modifier = Modifier.height(26.dp),
        )
    }
}
