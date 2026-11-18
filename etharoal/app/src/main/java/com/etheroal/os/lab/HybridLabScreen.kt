package com.etheroal.os.lab

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.EngineChoice
import com.etheroal.os.ai.ActionOutcome
import com.etheroal.os.ai.DownloadProgress
import com.etheroal.os.voice.Voice
import com.etheroal.os.voice.VoiceState
import com.etheroal.os.ai.LocalModelState
import com.etheroal.os.ai.Tasks
import com.etheroal.os.core.ai.AiOutcome
import com.etheroal.os.core.ai.InferenceSource
import com.etheroal.os.core.ai.LedgerEntry
import com.etheroal.os.core.ai.ModelStatus
import com.etheroal.os.core.ai.Sensitivity

private val TASK_LABELS = mapOf(
    Tasks.Triage.id to "Triage",
    Tasks.QuickReply.id to "Quick reply",
    Tasks.VoiceReply.id to "Reply in my voice",
    Tasks.Ask.id to "Ask",
)

@Composable
fun HybridLabScreen(vm: HybridLabViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ledger by vm.ledger.collectAsStateWithLifecycle()
    val local by vm.localState.collectAsStateWithLifecycle()
    val choice by vm.engineChoice.collectAsStateWithLifecycle()
    val voiceState by vm.voiceState.collectAsStateWithLifecycle()

    // Foreground state and model status can change while we were away.
    LifecycleResumeEffect(Unit) {
        vm.refreshDevice()
        onPauseOrDispose { }
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Hybrid Lab", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Every request goes through the router. It decides where the model runs and what may leave the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { DeviceCard(state, onDownload = vm::downloadModel, onRefresh = vm::refreshDevice) }
            if (state.nanoStatus == ModelStatus.UNAVAILABLE) {
                item { LocalModelCard(state, local, vm::selectLocal) }
            }
            item { EnginePicker(choice, state, vm::chooseEngine) }
            item { TaskPicker(state, vm) }
            item {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::setInput,
                    label = { Text("Input") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::run, enabled = !state.running && state.input.isNotBlank()) {
                    if (state.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Running")
                    } else {
                        Text("Run")
                    }
                }
            }
            state.outcome?.let { outcome ->
                item { OutcomeCard(outcome) }
                if (outcome is AiOutcome.Success) item { SpeakRow(state, voiceState, { vm.speak(outcome.text) }, vm::stopSpeaking, vm::chooseVoice) }
            }
            item { ActionsPanel(state, vm) }
            if (ledger.isNotEmpty()) {
                item { Text("Privacy ledger", style = MaterialTheme.typography.titleMedium) }
                items(ledger.take(20)) { LedgerRow(it) }
            }
        }
    }
}

@Composable
private fun DeviceCard(state: LabState, onDownload: () -> Unit, onRefresh: () -> Unit) {
    val device = state.device
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This phone", style = MaterialTheme.typography.titleMedium)
            if (device == null) {
                Text("Checking")
            } else {
                val model = when (state.nanoStatus ?: device.modelStatus) {
                    ModelStatus.AVAILABLE -> "Ready" + (state.onDeviceModelName?.let { " ($it)" } ?: "")
                    ModelStatus.DOWNLOADABLE -> "Supported, not downloaded yet"
                    ModelStatus.DOWNLOADING -> "Downloading"
                    ModelStatus.UNAVAILABLE -> "Not available on this device"
                }
                Fact("Gemini Nano", model)
                Fact("Requested category", state.onDeviceOption)
                Fact("Cloud model", state.cloudModel)
                Fact("Network", if (device.online) "Online" else "Offline")
                Fact("App in foreground", if (device.appInForeground) "Yes" else "No")
                if (device.onDeviceCoolingDown) Fact("On-device", "Cooling down after battery quota")
            }
            when (val d = state.download) {
                is DownloadProgress.Started -> Text("Download started: ${d.totalBytes / 1_000_000} MB")
                is DownloadProgress.Running -> Text("Downloaded ${d.downloadedBytes / 1_000_000} MB")
                is DownloadProgress.Done -> Text("Download complete")
                is DownloadProgress.Failed -> Text("Download failed: ${d.message}", color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (device?.modelStatus == ModelStatus.DOWNLOADABLE) {
                    Button(onClick = onDownload) { Text("Download model") }
                }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TaskPicker(state: LabState, vm: HybridLabViewModel) {
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tasks.all.forEach { task ->
                FilterChip(
                    selected = task.id == state.task.id,
                    onClick = { vm.selectTask(task) },
                    label = { Text(TASK_LABELS[task.id] ?: task.id) },
                )
            }
        }
        val rule = when (state.task.sensitivity) {
            Sensitivity.PRIVATE -> "Private: on-device only, and it waits if the on-device model can't run. It goes to the cloud only if you choose Fast on the home screen, and then masked."
            Sensitivity.REDACTABLE -> "Redactable: may use the cloud, but emails, phone numbers, links and codes are replaced with placeholders first."
            Sensitivity.PUBLIC -> "Public: nothing personal, may go to the cloud as written."
        }
        Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutcomeCard(outcome: AiOutcome) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (outcome) {
                is AiOutcome.Success -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceChip(outcome.source, outcome.modelName)
                        Text("${outcome.latencyMs} ms", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(outcome.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Mode ${outcome.mode}. ${outcome.reason}." +
                            (if (outcome.redactions > 0) " ${outcome.redactions} detail(s) hidden from the cloud." else "") +
                            (outcome.modelName?.let { " Answered by $it." } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AiOutcome.Deferred -> {
                    Text("Waiting", style = MaterialTheme.typography.titleMedium)
                    Text(outcome.reason)
                }
                is AiOutcome.Failed -> {
                    Text("Couldn't run", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(outcome.reason)
                }
            }
        }
    }
}

@Composable
private fun SourceChip(source: InferenceSource, engine: String?) {
    val onDevice = source == InferenceSource.ON_DEVICE
    val isLocal = engine?.startsWith("Local") == true
    AssistChip(
        onClick = { },
        label = { Text(if (isLocal) "On device (local model)" else if (onDevice) "On device (Nano)" else "Cloud") },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (onDevice) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    )
}

@Composable
private fun LedgerRow(entry: LedgerEntry) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val where = when (entry.source) {
                InferenceSource.ON_DEVICE -> "on device"
                InferenceSource.IN_CLOUD -> "cloud"
                null -> entry.outcome
            }
            Text(
                "${TASK_LABELS[entry.taskId] ?: entry.taskId}: $where, ${entry.latencyMs} ms" +
                    (entry.engine?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            if (entry.sentToCloud != null) {
                Text("Left the phone:", style = MaterialTheme.typography.labelSmall)
                Text(entry.sentToCloud!!, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                Text("Nothing left the phone.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LocalModelCard(state: LabState, local: LocalModelState, onSelect: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local fallback model", style = MaterialTheme.typography.titleMedium)
            Text(
                "This phone has no Gemini Nano. A local model (LiteRT-LM) stands in for the on-device side. " +
                    "Phones with Nano always use Firebase AI Logic instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.localModels.isEmpty()) {
                Text("No model installed. Copy a .litertlm file to:", style = MaterialTheme.typography.bodyMedium)
                Text(state.localDir, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.localModels.forEach { name ->
                        FilterChip(
                            selected = name == state.localSelected,
                            onClick = { onSelect(name) },
                            label = { Text(name) },
                        )
                    }
                }
                val status = when (local) {
                    LocalModelState.None -> "Not loaded"
                    is LocalModelState.Loading -> "Loading ${local.name}…"
                    is LocalModelState.Ready -> "Loaded ${local.name} in ${local.loadMs / 1000.0} s"
                    is LocalModelState.Error -> "Failed to load ${local.name}: ${local.message}"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (local is LocalModelState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun EnginePicker(choice: EngineChoice, state: LabState, onChoose: (EngineChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Run on", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineChoice.entries.forEach { option ->
                FilterChip(
                    selected = option == choice,
                    onClick = { onChoose(option) },
                    label = {
                        Text(
                            when (option) {
                                EngineChoice.AUTO -> "Auto"
                                EngineChoice.ON_DEVICE -> "On device"
                                EngineChoice.CLOUD -> "Cloud"
                            },
                        )
                    },
                )
            }
        }
        val onDeviceName = if (state.nanoStatus == ModelStatus.UNAVAILABLE) {
            state.localSelected?.let { "local model $it" } ?: "no on-device model installed"
        } else {
            "Gemini Nano"
        }
        Text(
            when (choice) {
                EngineChoice.AUTO -> "The router picks per task: private work stays on the phone, the rest uses whatever is best right now."
                EngineChoice.ON_DEVICE -> "Every task runs on the phone ($onDeviceName). If that can't run, the router falls back as usual."
                EngineChoice.CLOUD -> "Every task that may leave the phone runs in the cloud. Private tasks (Triage) still stay on the phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun SpeakRow(
    state: LabState,
    voiceState: VoiceState,
    onSpeak: () -> Unit,
    onStop: () -> Unit,
    onVoice: (Voice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = voiceState is VoiceState.Loading || voiceState is VoiceState.Speaking
            Button(onClick = if (busy) onStop else onSpeak) { Text(if (busy) "Stop" else "Speak") }
            Voice.entries.forEach { v ->
                FilterChip(selected = v == state.voice, onClick = { onVoice(v) }, label = { Text(v.label) })
            }
        }
        val line = when (voiceState) {
            VoiceState.Idle -> "Offline voice (tiny-voca): nothing leaves the phone."
            VoiceState.Loading -> "Loading the voice…"
            is VoiceState.Speaking -> voiceState.firstAudioMs?.let { "Speaking · first audio in $it ms" } ?: "Preparing speech…"
            is VoiceState.Done -> "First audio in ${voiceState.firstAudioMs} ms · ${"%.1f".format(voiceState.audioSeconds)} s of speech made in ${voiceState.synthMs} ms"
            is VoiceState.Error -> "Voice error: ${voiceState.message}"
        }
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (voiceState is VoiceState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionsPanel(state: LabState, vm: HybridLabViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phone actions (FunctionGemma, on device)", style = MaterialTheme.typography.titleMedium)
            Text(
                "A 270M model on the phone turns a request into one checked action: flashlight, Wi-Fi settings, " +
                    "calendar event, map, email, or new contact. Nothing runs until you tap Do it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.actionRequest,
                onValueChange = vm::setActionRequest,
                label = { Text("Request") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Turn on the flashlight",
                    "Lunch with Sam on Friday at 1pm",
                    "Show me coffee shops near Union Square",
                    "Email kyle@fences.example about the gate quote",
                ).forEach { sample -> AssistChip(onClick = { vm.setActionRequest(sample) }, label = { Text(sample) }) }
            }
            Button(onClick = vm::proposeAction, enabled = !state.acting && state.actionRequest.isNotBlank()) {
                Text(if (state.acting) "Thinking…" else "Propose action")
            }
            when (val a = state.action) {
                is ActionOutcome.Proposed -> {
                    Text(a.action.title, style = MaterialTheme.typography.titleSmall)
                    a.action.details.forEach { (k, v) -> Fact(k, v) }
                    Text(
                        "${a.latencyMs} ms on device · ${a.model} · ${a.call}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { vm.runAction(a.action) }) { Text("Do it") }
                }
                is ActionOutcome.NoAction -> Text("${a.reason} (${a.latencyMs} ms)")
                is ActionOutcome.Failed -> Text(a.reason, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            state.actionNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
