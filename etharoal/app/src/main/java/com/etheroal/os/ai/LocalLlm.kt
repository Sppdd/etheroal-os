package com.etheroal.os.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.etheroal.os.core.ai.EngineException
import com.etheroal.os.core.ai.EngineFailure
import com.etheroal.os.core.ai.EngineRequest
import com.etheroal.os.core.ai.EngineResult
import com.etheroal.os.core.ai.InferenceSource
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

sealed interface LocalModelState {
    data object None : LocalModelState
    data class Loading(val name: String) : LocalModelState
    data class Ready(val name: String, val loadMs: Long) : LocalModelState
    data class Error(val name: String, val message: String) : LocalModelState
}

/**
 * Development fallback for phones that have no Gemini Nano (no AICore).
 *
 * Runs an open model file (.litertlm) with Google's LiteRT-LM runtime, fully on the phone.
 * It is only consulted when Firebase AI Logic reports the on-device model as UNAVAILABLE; on a
 * Nano-capable phone every on-device request goes through Firebase AI Logic instead.
 *
 * Model files are copied by the developer into the app's external files folder:
 *   /sdcard/Android/data/com.etheroal.os/files/models/<name>.litertlm
 * Nothing is downloaded by the app itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalLlm(private val context: Context) {

    private val prefs = context.getSharedPreferences("local_llm", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // One native engine at a time: a 2.6 GB model plus a second one would not fit in RAM.
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private var engine: Engine? = null
    private var loadedName: String? = null

    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.None)
    val state: StateFlow<LocalModelState> = _state

    val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun available(): List<String> =
        modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 0 && !QuickActions.isActionModel(f.name) }
            ?.map { it.name.removeSuffix(".litertlm") }
            ?.sorted()
            .orEmpty()

    /** The model the user picked, or the only one present. */
    fun selected(): String? {
        val all = available()
        return prefs.getString(KEY_SELECTED, null)?.takeIf { it in all } ?: all.firstOrNull()
    }

    fun select(name: String) {
        prefs.edit().putString(KEY_SELECTED, name).apply()
    }

    fun hasModel(): Boolean = selected() != null

    /**
     * A local model is a developer fallback for phones without Gemini Nano. It is used only while
     * the Lab's "On device" engine is chosen, so it never sits in memory on a slow phone otherwise.
     */
    @Volatile var enabled: Boolean = false

    fun active(): Boolean = enabled && hasModel()

    /** Loads the selected model if it is not already loaded. Safe to call repeatedly. */
    suspend fun ensureLoaded(): Engine = mutex.withLock {
        val name = selected() ?: throw EngineException(EngineFailure.ON_DEVICE_UNAVAILABLE, "No local model installed")
        engine?.takeIf { loadedName == name && it.isInitialized() }?.let { return it }

        withContext(dispatcher) {
            engine?.close()
            engine = null
            loadedName = null
            _state.value = LocalModelState.Loading(name)
            val started = System.currentTimeMillis()
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = File(modelsDir, "$name.litertlm").absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.path,
                    ),
                )
                e.initialize()
                engine = e
                loadedName = name
                _state.value = LocalModelState.Ready(name, System.currentTimeMillis() - started)
                e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _state.value = LocalModelState.Error(name, t.message ?: t.javaClass.simpleName)
                throw EngineException(EngineFailure.ON_DEVICE_UNAVAILABLE, "Local model failed to load: ${t.message}", t)
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) {
            engine?.close()
            engine = null
            loadedName = null
            _state.value = LocalModelState.None
        }
    }

    suspend fun generate(request: EngineRequest): EngineResult {
        if (request.image != null) {
            throw EngineException(EngineFailure.ON_DEVICE_UNAVAILABLE, "Local model is text-only")
        }
        val engine = ensureLoaded()
        val name = loadedName ?: "local"
        val raw = mutex.withLock {
            withContext(dispatcher) {
                try {
                    // A fresh conversation per request: single-turn, no history kept on the phone either.
                    engine.createConversation(
                        ConversationConfig(
                            // Instructions as the system turn, the message as the user turn: small models
                            // keep the two apart far better than in one block of text.
                            systemInstruction = request.system.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = TEMPERATURE),
                            // Same output cap the router gives Nano. Without it a small model can ramble
                            // for thousands of tokens on a phone CPU.
                            maxOutputToken = request.maxOutputTokens,
                            // Reasoning models think silently before answering; for triage and short
                            // replies that is pure latency, so it is switched off where supported.
                            thinkingConfig = if (supportsThinking(name)) ThinkingConfig(false, 0) else null,
                        ),
                    ).use { conversation ->
                        coroutineScope {
                            // Generation runs in native code; a watchdog stops it if it runs too long.
                            val watchdog = launch(Dispatchers.IO) {
                                delay(TIMEOUT_MS)
                                conversation.cancelProcess()
                            }
                            try {
                                // Streamed so we can measure time-to-first-token (prefill) and decode speed.
                                val started = SystemClock.elapsedRealtime()
                                var firstAt = 0L
                                var chunks = 0
                                val out = StringBuilder()
                                conversation.sendMessageAsync(promptFor(request)).collect { msg ->
                                    if (chunks == 0) firstAt = SystemClock.elapsedRealtime()
                                    chunks++
                                    out.append(msg.toString())
                                }
                                val done = SystemClock.elapsedRealtime()
                                val stats = Stats(
                                    firstTokenMs = (if (chunks > 0) firstAt else done) - started,
                                    chunks = chunks,
                                    decodeMs = if (chunks > 0) done - firstAt else 0,
                                )
                                // Timing only; never log prompt or output text.
                                Log.i(TAG, "model=$name prompt≈${request.prompt.length / 4}tok firstToken=${stats.firstTokenMs}ms chunks=$chunks decode=${stats.decodeMs}ms")
                                out.toString() to stats
                            } finally {
                                watchdog.cancel()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    throw EngineException(EngineFailure.UNKNOWN, "Local model error: ${t.message}", t)
                }
            }
        }
        val text = clean(raw.first)
        val output = if (request.schemaKey == Tasks.SCHEMA_TRIAGE) parseTriage(text) else text
        if (output.isBlank()) throw EngineException(EngineFailure.BLOCKED, "The local model returned an empty answer")
        return EngineResult(output, InferenceSource.ON_DEVICE, "Local: $name, ${raw.second}")
    }

    private data class Stats(val firstTokenMs: Long, val chunks: Int, val decodeMs: Long) {
        override fun toString(): String {
            val rate = if (decodeMs > 0) "%.1f".format(chunks * 1000.0 / decodeMs) else "–"
            return "first token %.1f s, %s tok/s".format(firstTokenMs / 1000.0, rate)
        }
    }

    private fun supportsThinking(name: String): Boolean =
        name.contains("qwen3", ignoreCase = true) || name.contains("thinking", ignoreCase = true)

    private fun promptFor(request: EngineRequest): String {
        val body = if (request.system.isNotBlank() && request.content.isNotBlank()) request.content else request.prompt
        return if (request.schemaKey == Tasks.SCHEMA_TRIAGE) body + TRIAGE_FORMAT else body
    }

    /** Reasoning models (Qwen3) emit a think block first; only the answer is kept. */
    private fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "")
            .replace("\"\"\"", "").removePrefix("Reply:").trim()

    /**
     * Firebase AI Logic constrains Nano to the @Generable schema. Here we ask for JSON and then
     * validate it into the same [TriageResult] type, so callers see an identical shape.
     */
    private fun parseTriage(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw EngineException(EngineFailure.UNKNOWN, "Local model did not return JSON")
        }
        val result = try {
            LENIENT.decodeFromString(TriageResult.serializer(), text.substring(start, end + 1))
        } catch (e: Exception) {
            throw EngineException(EngineFailure.UNKNOWN, "Local model returned JSON that does not match the triage schema", e)
        }
        return Json.encodeToString(TriageResult.serializer(), result)
    }

    private companion object {
        const val KEY_SELECTED = "selected"
        const val TAG = "EtheroalLocal"
        const val TEMPERATURE = 0.3
        const val TIMEOUT_MS = 90_000L

        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        const val TRIAGE_FORMAT = """

Answer with one JSON object and nothing else, exactly in this form:
{"urgency": "URGENT" | "NORMAL" | "LOW", "category": "PERSON" | "WORK" | "MONEY" | "DELIVERY" | "TRAVEL" | "SECURITY" | "PROMOTION" | "SYSTEM" | "OTHER", "needsReply": true | false, "summary": "one sentence, at most 15 words"}"""
    }
}
