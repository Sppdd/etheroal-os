package com.etheroal.os.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Everything an agent does, as it happens, for the owner's step-by-step transcript (like a coding
 * agent's log): what it saw, what it thought, what it did, how long each took. Never contains
 * secrets: tool arguments are shown as the tool describes them, and vault values never pass here.
 */
sealed interface AgentEvent {
    val agent: String
    data class Thinking(override val agent: String) : AgentEvent
    /** A screen (or other state) the agent was shown. [image] is the screenshot, when one was taken. */
    data class Observed(override val agent: String, val summary: String, val image: Any?, val ms: Long) : AgentEvent
    /** The model's reasoning summary and any text it wrote alongside its tool calls. */
    data class Thought(override val agent: String, val text: String, val ms: Long) : AgentEvent
    data class Acting(override val agent: String, val what: String) : AgentEvent
    data class Acted(override val agent: String, val what: String, val ok: Boolean, val note: String, val ms: Long) : AgentEvent
    data class Blocked(override val agent: String, val what: String, val why: String) : AgentEvent
}

sealed interface AgentResult {
    /** The model answered without calling a tool, or a final tool (finish) ended the run. */
    data class Answer(val text: String) : AgentResult
    data class OutOfSteps(val steps: Int) : AgentResult
    data class Failed(val reason: String) : AgentResult
}

/** Asks the owner. Returns true only on an explicit yes; a timeout or a stop is a no. */
fun interface OwnerGate {
    suspend fun confirm(question: String, secret: Boolean): Boolean
}

/**
 * One agent: a model, a set of tools and a budget. Runs model → tool calls → checks → results →
 * model … until the model answers in plain text, a final tool ends it, or the budget runs out.
 *
 * Every tool call passes [Tool.check] before it runs, every text that goes to the model passes
 * the session's redactor, and every argument coming back is rehydrated only on its way into a tool.
 */
class AgentLoop(
    val name: String,
    private val model: AgentModel,
    tools: List<Tool>,
    private val redactor: SessionRedactor,
    private val gate: OwnerGate,
    private val maxSteps: Int,
    private val observe: (suspend () -> Observation?)? = null,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val retries: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tools = tools.associateBy { it.spec.name }
    val specs: List<ToolSpec> = tools.map { it.spec }

    suspend fun run(turns: MutableList<Turn>): AgentResult {
        for (step in 1..maxSteps) {
            val seenAt = clock()
            val observation = observe?.invoke()?.let { it.copy(text = redactor.redact(it.text)) }
            observation?.let { onEvent(AgentEvent.Observed(name, summarize(it.text), it.image, clock() - seenAt)) }
            onEvent(AgentEvent.Thinking(name))
            val askedAt = clock()
            val reply = try {
                callModel(turns, observation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelFailure) {
                return AgentResult.Failed(e.message ?: "The model failed")
            }
            turns += Turn.Model(reply.text, reply.calls, reply.raw)
            val thought = listOf(reply.thoughts, if (reply.calls.isNotEmpty()) reply.text else "")
                .filter { it.isNotBlank() }.joinToString("\n").trim()
            onEvent(AgentEvent.Thought(name, redactor.rehydrate(thought), clock() - askedAt))
            if (reply.calls.isEmpty()) return AgentResult.Answer(redactor.rehydrate(reply.text).trim())

            val results = ArrayList<Pair<ToolCall, ToolResult>>()
            var final: ToolResult? = null
            for (call in reply.calls) {
                val result = execute(call)
                results += call to result.copy(text = redactor.redact(result.text))
                if (result.final && final == null) final = result
            }
            turns += Turn.Results(results)
            final?.let { return AgentResult.Answer(redactor.rehydrate(it.text).trim()) }
        }
        return AgentResult.OutOfSteps(maxSteps)
    }

    /** "Clock · 14 elements" for the transcript, not the whole element list. */
    private fun summarize(text: String): String {
        val app = text.lineSequence().firstOrNull { it.startsWith("Current app:") }?.removePrefix("Current app:")?.trim()
        val count = text.lineSequence().count { it.startsWith("[") }
        return listOfNotNull(app, if (count > 0) "$count elements" else null).joinToString(" · ").ifEmpty { text.take(80) }
    }

    private suspend fun callModel(turns: List<Turn>, observation: Observation?): ModelReply {
        var attempt = 0
        while (true) {
            try {
                return model.next(turns, observation)
            } catch (e: ModelFailure) {
                if (!e.retryable || attempt >= retries) throw e
                delay(800L shl attempt)
                attempt++
            }
        }
    }

    private suspend fun execute(raw: ToolCall): ToolResult {
        val tool = tools[raw.name] ?: return ToolResult("There is no tool called ${raw.name}. Use one of: ${tools.keys.joinToString()}.", ok = false)
        val call = redactor.rehydrate(raw)
        val what = tool.describe(call)
        when (val verdict = runCatching { tool.check(call) }.getOrElse { Verdict.Refused("The request could not be checked") }) {
            is Verdict.Refused -> {
                onEvent(AgentEvent.Blocked(name, what, verdict.why))
                return ToolResult("Not allowed: ${verdict.why}", ok = false)
            }
            is Verdict.NeedsOwner -> {
                if (!gate.confirm(verdict.question, verdict.secret)) {
                    onEvent(AgentEvent.Blocked(name, what, "The owner said no"))
                    return ToolResult(
                        "The owner declined: ${verdict.question} Do not try this again or work around it; stop and tell the owner what is left.",
                        ok = false,
                    )
                }
            }
            Verdict.Allowed -> Unit
        }
        onEvent(AgentEvent.Acting(name, what))
        val startedAt = clock()
        val result = try {
            tool.run(call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("${raw.name} failed: ${e.message ?: e.javaClass.simpleName}", ok = false)
        }
        onEvent(AgentEvent.Acted(name, what, result.ok, redactor.rehydrate(result.ownerText ?: result.text).take(300), clock() - startedAt))
        return result
    }
}
