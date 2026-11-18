package com.etheroal.os.voice

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import com.etheroal.os.agent.SessionManager
import com.etheroal.os.ai.FirebaseInferenceEngine
import com.etheroal.os.ai.RemoteAiConfig
import com.etheroal.os.core.agent.Param
import com.etheroal.os.core.agent.ParamType
import com.etheroal.os.core.agent.Prompts
import com.etheroal.os.core.agent.Tool
import com.etheroal.os.core.agent.ToolCall
import com.etheroal.os.core.agent.ToolResult
import com.etheroal.os.core.agent.ToolSpec
import com.etheroal.os.core.agent.Verdict
import com.etheroal.os.core.agent.tool
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveAudioConversationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface LiveState {
    data object Idle : LiveState
    data object Connecting : LiveState
    /** Talking. [heard] and [said] are the lines being transcribed right now. */
    data class Live(val heard: String, val said: String) : LiveState
    data class Error(val message: String) : LiveState
}

/**
 * Talking to Etheroal: a real-time spoken conversation with Gemini through the Firebase AI Logic
 * Live API. It listens, can be interrupted, and answers aloud. It can use the direct phone tools
 * and memory itself; anything that needs apps operated becomes a background task that shows up in
 * the status bar, so the conversation never stalls waiting on it.
 *
 * Audio goes to Google to be understood. The transcript is stored on the phone as a conversation.
 */
@OptIn(PublicPreviewAPI::class)
class LiveVoice(
    private val engine: FirebaseInferenceEngine,
    private val config: RemoteAiConfig,
    private val sessions: SessionManager,
    private val ownerContext: suspend () -> Pair<String, String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    val state: StateFlow<LiveState> = _state

    private var session: LiveSession? = null
    private var conversationId: String? = null
    private val heard = StringBuilder()
    private val said = StringBuilder()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (_state.value is LiveState.Live || _state.value is LiveState.Connecting) return
        _state.value = LiveState.Connecting
        scope.launch {
            try {
                val tools = sessions.directTools() + backgroundTask()
                val byName = tools.associateBy { it.spec.name }
                val (habits, memories) = ownerContext()
                val now = LocalDateTime.now().withNano(0).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))
                val model = engine.ai.liveModel(
                    modelName = config.cloudModelLive,
                    generationConfig = liveGenerationConfig {
                        responseModality = ResponseModality.AUDIO
                        speechConfig = SpeechConfig(Voice("Aoede"))
                        inputAudioTranscription = AudioTranscriptionConfig()
                        outputAudioTranscription = AudioTranscriptionConfig()
                    },
                    tools = listOf(com.google.firebase.ai.type.Tool.functionDeclarations(tools.map { declaration(it.spec) })),
                    systemInstruction = content {
                        text(
                            Prompts.orchestrator(now, habits, memories) +
                                "\n\nYou are speaking aloud: answer in one or two short spoken sentences. " +
                                "For anything that needs apps operated, call start_task and tell the owner it is running.",
                        )
                    },
                )
                val live = model.connect().also { session = it }
                conversationId = sessions.openVoiceSession()
                _state.value = LiveState.Live("", "")
                live.startAudioConversation(
                    liveAudioConversationConfig {
                        functionCallHandler = { call -> handle(call, byName) }
                        transcriptHandler = { input, output -> transcript(input?.text, output?.text) }
                        enableInterruptions = true
                    },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val failure = runCatching { engine.classify(e).failure }.getOrNull()
                Log.w(TAG, "live voice failed: ${failure ?: e.javaClass.simpleName}")
                _state.value = LiveState.Error(
                    failure?.let { com.etheroal.os.ai.CloudHealth.explain(it).title } ?: "Voice couldn't start",
                )
                close()
            }
        }
    }

    fun stop() {
        scope.launch { close() }
        _state.value = LiveState.Idle
    }

    private suspend fun close() {
        flush()
        runCatching { session?.stopAudioConversation() }
        runCatching { session?.close() }
        session = null
        conversationId?.let { sessions.voiceEnded(it) }
        conversationId = null
    }

    /** Transcripts arrive a few words at a time; a line is stored when the other side starts speaking. */
    private fun transcript(input: String?, output: String?) {
        if (!input.isNullOrEmpty()) {
            if (said.isNotEmpty()) flushAsync()
            heard.append(input)
        }
        if (!output.isNullOrEmpty()) {
            if (heard.isNotEmpty()) flushAsync()
            said.append(output)
        }
        _state.value = LiveState.Live(heard.toString().trim(), said.toString().trim())
    }

    private fun flushAsync() { scope.launch { flush() } }

    @Synchronized
    private fun take(): Pair<String, String> = (heard.toString() to said.toString()).also { heard.clear(); said.clear() }

    private suspend fun flush() {
        val id = conversationId ?: return
        val (h, s) = take()
        sessions.voiceLine(id, owner = true, h)
        sessions.voiceLine(id, owner = false, s)
    }

    /** The Live API calls tools synchronously; each runs under the same checks as in text mode, with a time limit. */
    private fun handle(call: FunctionCallPart, tools: Map<String, Tool>): FunctionResponsePart {
        val args = call.args.mapValues { (_, v) ->
            when (v) {
                is JsonNull -> null
                is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.content
                else -> v.toString()
            }
        }
        val c = ToolCall(call.id, call.name, args)
        val result = runBlocking(Dispatchers.Default) {
            val tool = tools[call.name] ?: return@runBlocking ToolResult("No tool called ${call.name}.", ok = false)
            when (val v = tool.check(c)) {
                is Verdict.Refused -> ToolResult("Not allowed: ${v.why}", ok = false)
                is Verdict.NeedsOwner -> ToolResult("That needs the owner's OK on screen; use start_task instead.", ok = false)
                Verdict.Allowed -> withTimeoutOrNull(15_000) { runCatching { tool.run(c) }.getOrElse { ToolResult("Failed.", ok = false) } }
                    ?: ToolResult("That took too long.", ok = false)
            }
        }
        return FunctionResponsePart(call.name, buildJsonObject { put("ok", result.ok); put("result", result.text) }, call.id)
    }

    private fun backgroundTask(): Tool = tool(
        ToolSpec(
            "start_task",
            "Start a task that needs apps on the phone operated, or many steps. It runs in the background with its progress in the status bar.",
            listOf(Param("task", ParamType.STRING, "The complete task with every detail")),
        ),
        check = { c -> if (c.str("task").isBlank()) Verdict.Refused("start_task needs a task.") else Verdict.Allowed },
    ) { c -> sessions.start(c.str("task")); ToolResult("The task is running; progress shows in the status bar.") }

    private fun declaration(spec: ToolSpec) = FunctionDeclaration(
        spec.name, spec.description,
        spec.params.associate { p ->
            p.name to when {
                p.options.isNotEmpty() -> Schema.enumeration(p.options, p.description)
                p.type == ParamType.INTEGER -> Schema.integer(p.description)
                p.type == ParamType.BOOLEAN -> Schema.boolean(p.description)
                else -> Schema.string(p.description)
            }
        },
        spec.params.filter { !it.required }.map { it.name },
    )

    private companion object { const val TAG = "EtheroalVoice" }
}
