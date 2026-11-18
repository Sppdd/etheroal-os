package com.etheroal.os.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionRedactorTest {
    @Test fun `the same value keeps its placeholder across turns`() {
        val r = SessionRedactor()
        assertEquals("mail [[EMAIL_1]]", r.redact("mail a@b.co"))
        assertEquals("mail [[EMAIL_2]] and [[EMAIL_1]]", r.redact("mail c@d.co and a@b.co"))
        assertEquals("to a@b.co, c@d.co", r.rehydrate("to [[EMAIL_1]], [[EMAIL_2]]"))
    }

    @Test fun `tool arguments are rehydrated, other values untouched`() {
        val r = SessionRedactor()
        r.redact("call +1 415 555 0100")
        val call = r.rehydrate(ToolCall(null, "dial", mapOf("number" to "[[PHONE_1]]", "n" to 3)))
        assertEquals("+1 415 555 0100", call.args["number"])
        assertEquals(3, call.args["n"])
    }

    @Test fun `unknown placeholders stay as they are`() {
        assertEquals("[[EMAIL_9]]", SessionRedactor().rehydrate("[[EMAIL_9]]"))
        assertFalse(SessionRedactor().redact("hello").contains("[["))
    }
}
