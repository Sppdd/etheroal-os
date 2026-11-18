package com.etheroal.os.agent

import android.graphics.Bitmap
import android.util.Log
import com.etheroal.os.MemoryLedger
import com.etheroal.os.ai.CloudHealth
import com.etheroal.os.ai.FirebaseInferenceEngine
import com.etheroal.os.core.agent.AgentModel
import com.etheroal.os.core.agent.ModelFailure
import com.etheroal.os.core.agent.ModelReply
import com.etheroal.os.core.agent.Observation
import com.etheroal.os.core.agent.ParamType
import com.etheroal.os.core.agent.TextModel
import com.etheroal.os.core.agent.ToolCall
import com.etheroal.os.core.agent.ToolSpec
import com.etheroal.os.core.agent.Turn
import com.etheroal.os.core.ai.EngineFailure
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.InferenceSource
import com.etheroal.os.core.ai.LedgerEntry
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionCallingConfig
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.ToolConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An agent's model: Gemini through Firebase AI Logic with function calling. Each agent gets its own
 * instance with its own system instruction and tool list, so the model only ever sees the tools
 * that agent is allowed to use.
 *
 * Agents always run in the cloud: on-device inference through AI Logic supports neither tools nor
 * multi-turn conversations. Everything sent is already masked by the session's redactor.
 */
class GeminiAgentModel(
    private val engine: FirebaseInferenceEngine,
    private val modelName: String,
    private val agent: String,
    system: String,
    specs: List<ToolSpec>,
    /** Operators must act every turn; the orchestrator may also just answer. */
    forceToolCall: Boolean,
    private val ledger: MemoryLedger,
) : AgentModel {

    private val model by lazy {
        engine.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                temperature = 0.2f
                maxOutputTokens = 2048
                // Thought summaries are shown to the owner as the agent's reasoning, step by step.
                thinkingConfig = com.google.firebase.ai.type.thinkingConfig { includeThoughts = true }
            },
            tools = listOf(Tool.functionDeclarations(specs.map(::declaration))),
            toolConfig = ToolConfig(if (forceToolCall) FunctionCallingConfig.any() else FunctionCallingConfig.auto()),
            systemInstruction = content { text(system) },
        )
    }

    override suspend fun next(turns: List<Turn>, observation: Observation?): ModelReply {
        val contents = turns.map(::toContent).toMutableList()
        observation?.let { obs ->
            contents += content("user") {
                (obs.image as? Bitmap)?.let { image(it) }
                text(obs.text)
            }
        }
        val started = System.currentTimeMillis()
        val response = try {
            model.generateContent(contents)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw failure(engine.classify(e).failure, e)
        }
        val raw = response.candidates.firstOrNull()?.content
        val calls = response.functionCalls.map { ToolCall(it.id, it.name, it.args.mapValues { (_, v) -> plain(v) }) }
        val text = runCatching { response.text }.getOrNull().orEmpty()
        val thoughts = runCatching { response.thoughtSummary }.getOrNull().orEmpty()
        if (calls.isEmpty() && text.isBlank()) throw ModelFailure("Gemini returned an empty answer", retryable = true)
        ledger.record(
            LedgerEntry(
                timestampMs = System.currentTimeMillis(), taskId = "agent:$agent", mode = HybridMode.ONLY_IN_CLOUD,
                source = InferenceSource.IN_CLOUD, outcome = if (calls.isEmpty()) "answer" else calls.joinToString { it.name },
                reason = "Agents need tools and multi-turn: cloud only", latencyMs = System.currentTimeMillis() - started,
                redactions = 0, sentToCloud = lastSent(turns, observation), engine = "Gemini cloud ($modelName)",
            ),
        )
        return ModelReply(text, calls, raw, thoughts)
    }

    private fun toContent(turn: Turn): Content = when (turn) {
        is Turn.User -> content("user") {
            (turn.image as? Bitmap)?.let { image(it) }
            text(turn.text)
        }
        is Turn.Model -> (turn.raw as? Content) ?: Content(
            "model",
            buildList {
                if (turn.text.isNotBlank()) add(TextPart(turn.text))
                turn.calls.forEach { c -> add(FunctionCallPart(c.name, c.args.mapValues { (_, v) -> json(v) }, c.id)) }
            },
        )
        is Turn.Results -> Content(
            "function",
            turn.results.map { (call, result) ->
                FunctionResponsePart(
                    call.name,
                    buildJsonObject {
                        put("ok", result.ok)
                        put("result", result.text)
                    },
                    call.id,
                )
            },
        )
    }

    private fun lastSent(turns: List<Turn>, observation: Observation?): String =
        (observation?.let { "[screen]\n" + it.text + if (it.image != null) "\n[+ screenshot]" else "" }
            ?: when (val t = turns.lastOrNull()) {
                is Turn.User -> t.text
                is Turn.Results -> t.results.joinToString("\n") { it.second.text }
                else -> ""
            }).take(4000)

    private companion object {
        fun declaration(spec: ToolSpec): FunctionDeclaration = FunctionDeclaration(
            name = spec.name,
            description = spec.description,
            parameters = spec.params.associate { p ->
                p.name to when {
                    p.options.isNotEmpty() -> Schema.enumeration(p.options, p.description)
                    p.type == ParamType.INTEGER -> Schema.integer(p.description)
                    p.type == ParamType.BOOLEAN -> Schema.boolean(p.description)
                    else -> Schema.string(p.description)
                }
            },
            optionalParameters = spec.params.filter { !it.required }.map { it.name },
        )

        fun plain(v: JsonElement): Any? = when (v) {
            is JsonNull -> null
            is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
            else -> v.toString()
        }

        fun json(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}

/** A plain text call to a fast model, used by the verifier and the memory agent. */
class GeminiTextModel(private val engine: FirebaseInferenceEngine, private val modelName: () -> String) : TextModel {
    override suspend fun complete(system: String, user: String): String = try {
        engine.ai.generativeModel(
            modelName = modelName(),
            generationConfig = generationConfig { temperature = 0f; maxOutputTokens = 400 },
            systemInstruction = content { text(system) },
        ).generateContent(user).text.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw failure(engine.classify(e).failure, e)
    }
}

/** Grounded web answers through AI Logic's Google Search tool. */
class GeminiSearch(private val engine: FirebaseInferenceEngine, private val modelName: () -> String) {
    suspend fun search(query: String): String = try {
        val response = engine.ai.generativeModel(
            modelName = modelName(),
            tools = listOf(Tool.googleSearch()),
        ).generateContent("Answer briefly and factually, using Google Search: $query")
        val sources = response.candidates.firstOrNull()?.groundingMetadata?.groundingChunks
            ?.mapNotNull { it.web?.title }?.distinct()?.take(3).orEmpty()
        response.text.orEmpty() + if (sources.isNotEmpty()) "\nSources: ${sources.joinToString()}" else ""
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw failure(engine.classify(e).failure, e)
    }
}

private val RETRYABLE = setOf(EngineFailure.BUSY, EngineFailure.OFFLINE, EngineFailure.UNKNOWN)

private fun failure(f: EngineFailure, cause: Throwable): ModelFailure {
    val down = CloudHealth.explain(f)
    Log.w("EtheroalAgent", "model call failed: ${f.name}")             // never the prompt
    return ModelFailure("${down.title}. ${down.fix}", retryable = f in RETRYABLE, cause = cause)
}
