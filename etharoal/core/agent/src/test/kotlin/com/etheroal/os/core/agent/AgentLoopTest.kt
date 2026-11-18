package com.etheroal.os.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    /** Plays back scripted replies and records what the model was shown. */
    private class Script(vararg replies: ModelReply) : AgentModel {
        val queue = ArrayDeque(replies.toList())
        val seen = ArrayList<String>()
        override suspend fun next(turns: List<Turn>, observation: Observation?): ModelReply {
            seen += turns.joinToString("\n") { t ->
                when (t) {
                    is Turn.User -> "U:" + t.text
                    is Turn.Model -> "M:" + t.text + t.calls.joinToString { it.name + it.args }
                    is Turn.Results -> "R:" + t.results.joinToString { it.second.text }
                }
            } + (observation?.let { "\nO:" + it.text } ?: "")
            return queue.removeFirstOrNull() ?: ModelReply("out of script", emptyList())
        }
    }

    private fun call(name: String, vararg args: Pair<String, Any?>) = ToolCall(null, name, mapOf(*args))
    private fun reply(vararg calls: ToolCall) = ModelReply("", calls.toList())

    private val ran = ArrayList<ToolCall>()
    private val echo = tool(ToolSpec("echo", "echo", listOf(Param("text", ParamType.STRING, "t")))) { ran += it; ToolResult("echoed ${it.str("text")}") }
    private val send = tool(ToolSpec("send", "send", risk = Risk.CONFIRM)) { ran += it; ToolResult("sent") }
    private val finish = tool(ToolSpec("finish", "finish")) { ToolResult(it.str("result"), final = true) }

    private fun loop(model: AgentModel, gateAnswer: Boolean = true, steps: Int = 5, observe: (suspend () -> Observation?)? = null, redactor: SessionRedactor = SessionRedactor()) =
        AgentLoop("test", model, listOf(echo, send, finish), redactor, { _, _ -> gateAnswer }, steps, observe)

    @Test fun `runs tools in order and ends on a plain answer`() = runTest {
        val model = Script(reply(call("echo", "text" to "a")), reply(call("echo", "text" to "b")), ModelReply("All done", emptyList()))
        val result = loop(model).run(mutableListOf(Turn.User("go")))
        assertEquals(AgentResult.Answer("All done"), result)
        assertEquals(listOf("a", "b"), ran.map { it.str("text") })
        assertTrue(model.seen.last().contains("R:echoed b"))
    }

    @Test fun `a final tool ends the run with its result`() = runTest {
        val result = loop(Script(reply(call("finish", "result" to "Timer started")))).run(mutableListOf(Turn.User("go")))
        assertEquals(AgentResult.Answer("Timer started"), result)
    }

    @Test fun `stops at the step budget`() = runTest {
        val model = Script(*Array(10) { reply(call("echo", "text" to "x")) })
        assertEquals(AgentResult.OutOfSteps(3), loop(model, steps = 3).run(mutableListOf(Turn.User("go"))))
        assertEquals(3, ran.size)
    }

    @Test fun `a declined confirmation never runs the tool and tells the model not to retry`() = runTest {
        val model = Script(reply(call("send")), ModelReply("I stopped", emptyList()))
        loop(model, gateAnswer = false).run(mutableListOf(Turn.User("go")))
        assertTrue(ran.isEmpty())
        assertTrue(model.seen.last().contains("The owner declined"))
    }

    @Test fun `an approved confirmation runs the tool`() = runTest {
        loop(Script(reply(call("send")), ModelReply("ok", emptyList()))).run(mutableListOf(Turn.User("go")))
        assertEquals(listOf("send"), ran.map { it.name })
    }

    @Test fun `unknown tools are reported back, not run`() = runTest {
        val model = Script(reply(call("rm_rf")), ModelReply("ok", emptyList()))
        loop(model).run(mutableListOf(Turn.User("go")))
        assertTrue(model.seen.last().contains("There is no tool called rm_rf"))
    }

    @Test fun `private details are masked for the model and real for the tool`() = runTest {
        val redactor = SessionRedactor()
        val masked = redactor.redact("Email sam@example.com")
        val model = Script(reply(call("echo", "text" to "[[EMAIL_1]]")), ModelReply("Sent to [[EMAIL_1]]", emptyList()))
        val result = loop(model, redactor = redactor).run(mutableListOf(Turn.User(masked)))
        assertEquals("sam@example.com", ran.single().str("text"))
        assertFalse(model.seen.joinToString().contains("sam@example.com"))     // tool result was masked again
        assertEquals(AgentResult.Answer("Sent to sam@example.com"), result)     // the owner sees the real value
    }

    @Test fun `observations are masked and shown only for the current turn`() = runTest {
        val model = Script(reply(call("echo", "text" to "x")), ModelReply("done", emptyList()))
        var n = 0
        loop(model, observe = { Observation("screen ${++n} call +44 7700 900123") }).run(mutableListOf(Turn.User("go")))
        assertTrue(model.seen[1].contains("O:screen 2"))
        assertFalse(model.seen[1].contains("screen 1"))
        assertFalse(model.seen.joinToString().contains("7700 900123"))
    }

    @Test fun `retryable model failures are retried, others end the run`() = runTest {
        var calls = 0
        val flaky = AgentModel { _, _ -> if (++calls < 2) throw ModelFailure("busy", retryable = true) else ModelReply("fine", emptyList()) }
        assertEquals(AgentResult.Answer("fine"), loop(flaky).run(mutableListOf(Turn.User("go"))))
        val broken = AgentModel { _, _ -> throw ModelFailure("Gemini credits used up") }
        assertEquals(AgentResult.Failed("Gemini credits used up"), loop(broken).run(mutableListOf(Turn.User("go"))))
    }

    @Test fun `every step is reported in order with thoughts and timings`() = runTest {
        val events = ArrayList<AgentEvent>()
        val model = Script(ModelReply("I'll echo first", listOf(call("echo", "text" to "a")), thoughts = "Plan: echo then stop"), ModelReply("done", emptyList()))
        AgentLoop("op", model, listOf(echo), SessionRedactor(), { _, _ -> true }, 3, observe = { Observation("Current app: com.x\n[1] Button") }, onEvent = { events += it })
            .run(mutableListOf(Turn.User("go")))
        val kinds = events.map { it::class.simpleName }
        assertEquals(listOf("Observed", "Thinking", "Thought", "Acting", "Acted", "Observed", "Thinking", "Thought"), kinds)
        assertEquals("Plan: echo then stop\nI'll echo first", (events[2] as AgentEvent.Thought).text)
        assertEquals("com.x · 1 elements", (events[0] as AgentEvent.Observed).summary)
    }

    @Test fun `a crashing tool becomes an error result`() = runTest {
        val boom = tool(ToolSpec("boom", "b")) { error("kaput") }
        val model = Script(reply(call("boom")), ModelReply("ok", emptyList()))
        AgentLoop("t", model, listOf(boom), SessionRedactor(), { _, _ -> true }, 3).run(mutableListOf(Turn.User("go")))
        assertTrue(model.seen.last().contains("boom failed: kaput"))
    }
}
