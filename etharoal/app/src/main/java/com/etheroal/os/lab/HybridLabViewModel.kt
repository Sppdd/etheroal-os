package com.etheroal.os.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.etheroal.os.AppGraph
import com.etheroal.os.EngineChoice
import com.etheroal.os.ai.ActionOutcome
import com.etheroal.os.ai.PhoneAction
import com.etheroal.os.ai.DownloadProgress
import com.etheroal.os.ai.LocalModelState
import com.etheroal.os.ai.Tasks
import com.etheroal.os.core.ai.AiOutcome
import com.etheroal.os.core.ai.AiTask
import com.etheroal.os.core.ai.DeviceState
import com.etheroal.os.core.ai.LedgerEntry
import com.etheroal.os.voice.Voice
import com.etheroal.os.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LabState(
    val device: DeviceState? = null,
    val nanoStatus: com.etheroal.os.core.ai.ModelStatus? = null,
    val localModels: List<String> = emptyList(),
    val localSelected: String? = null,
    val localDir: String = "",
    val onDeviceModelName: String? = null,
    val onDeviceOption: String = "",
    val cloudModel: String = "",
    val download: DownloadProgress? = null,
    val task: AiTask = Tasks.Triage,
    val input: String = SAMPLES.getValue(Tasks.Triage.id),
    val running: Boolean = false,
    val outcome: AiOutcome? = null,
    val voice: Voice = Voice.F2,
    val actionRequest: String = "Turn on the flashlight",
    val acting: Boolean = false,
    val action: ActionOutcome? = null,
    val actionNote: String? = null,
)

val SAMPLES = mapOf(
    Tasks.Triage.id to "Messages - Dad: Are you still coming at 6? Call me on 020 7946 0958 if you're late.",
    Tasks.QuickReply.id to "Kyle: Fence quote is 2,400 with the gate. Send the deposit to kyle@fences.example and I can start Monday.",
    Tasks.VoiceReply.id to "Priya: Thanks for covering my shift last week. Dinner on me? I'm free Thursday or Friday.",
    Tasks.Ask.id to "In two sentences, why is the sky blue?",
)

class HybridLabViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(LabState())
    val state: StateFlow<LabState> = _state.asStateFlow()
    val ledger: StateFlow<List<LedgerEntry>> = graph.ledger.entries
    val localState: StateFlow<LocalModelState> = graph.local.state
    val engineChoice: StateFlow<EngineChoice> = graph.engineChoice
    val voiceState: StateFlow<VoiceState> = graph.voice.state
    private var speaking: Job? = null

    fun setActionRequest(text: String) = _state.update { it.copy(actionRequest = text) }

    /** FunctionGemma on the phone turns the request into one checked phone action. */
    fun proposeAction() {
        val request = _state.value.actionRequest
        if (_state.value.acting || request.isBlank()) return
        _state.update { it.copy(acting = true, action = null, actionNote = null) }
        viewModelScope.launch {
            val outcome = graph.actions.propose(request)
            _state.update { it.copy(acting = false, action = outcome) }
        }
    }

    fun runAction(action: PhoneAction) {
        val error = graph.actions.run(action)
        _state.update { it.copy(actionNote = error ?: "Done: ${action.title.lowercase()}") }
    }

    fun chooseVoice(voice: Voice) = _state.update { it.copy(voice = voice) }

    /** Reads the answer aloud on the phone. For triage it reads the one-line summary. */
    fun speak(text: String) {
        speaking?.cancel()
        val spoken = runCatching {
            kotlinx.serialization.json.Json.decodeFromString(com.etheroal.os.ai.TriageResult.serializer(), text).let {
                "${it.urgency.name.lowercase()}. ${it.summary}"
            }
        }.getOrDefault(text)
        speaking = viewModelScope.launch { graph.voice.speak(spoken, _state.value.voice) }
    }

    fun stopSpeaking() {
        speaking?.cancel()
        graph.voice.stop()
    }

    fun chooseEngine(choice: EngineChoice) {
        graph.engineChoice.value = choice
        graph.local.enabled = choice == EngineChoice.ON_DEVICE
        refreshDevice()
    }

    init {
        viewModelScope.launch {
            graph.config.refresh()
            refreshDevice()
            graph.models.warmUp()           // Gemini Nano only; local models load when chosen
        }
    }

    fun refreshDevice() = viewModelScope.launch {
        val device = graph.deviceState.current()
        val nano = graph.models.status()
        _state.update {
            it.copy(
                device = device,
                nanoStatus = nano,
                localModels = graph.local.available(),
                localSelected = graph.local.selected(),
                localDir = graph.local.modelsDir.absolutePath,
                onDeviceModelName = graph.models.modelName(),
                onDeviceOption = graph.config.onDeviceModelOptionName,
                cloudModel = graph.config.cloudModelFast,
            )
        }
    }

    fun selectLocal(name: String) = viewModelScope.launch {
        graph.local.select(name)
        refreshDevice()
        if (graph.local.enabled) runCatching { graph.local.ensureLoaded() }
    }

    fun selectTask(task: AiTask) =
        _state.update { it.copy(task = task, input = SAMPLES[task.id].orEmpty(), outcome = null) }

    fun setInput(text: String) = _state.update { it.copy(input = text) }

    fun run() {
        val snapshot = _state.value
        if (snapshot.running || snapshot.input.isBlank()) return
        _state.update { it.copy(running = true, outcome = null) }
        viewModelScope.launch {
            val outcome = graph.router.run(snapshot.task, snapshot.input)
            _state.update { it.copy(running = false, outcome = outcome) }
            refreshDevice()
        }
    }

    fun downloadModel() = viewModelScope.launch {
        runCatching {
            graph.models.download().collect { progress -> _state.update { it.copy(download = progress) } }
        }.onFailure { e -> _state.update { it.copy(download = DownloadProgress.Failed(e.message ?: "Download failed")) } }
        refreshDevice()
    }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { HybridLabViewModel(graph) }
        }
    }
}
