package com.etheroal.os.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirlockTest {

    private val airlock = Airlock()

    @Test fun `redacts emails, phones, links, cards and codes`() {
        val input = "Mail sam@example.com or call +1 415 555 0134. Card 4111 1111 1111 1111. " +
            "Your code is 482913. See https://pay.example.com/i/9f3?t=abc"
        val r = airlock.redact(input)
        for (secret in listOf("sam@example.com", "415 555 0134", "4111 1111 1111 1111", "482913", "pay.example.com")) {
            assertFalse("leaked: $secret in ${r.text}", r.text.contains(secret))
        }
        assertTrue(r.text.contains("[[EMAIL_1]]"))
        assertTrue(r.text.contains("[[LINK_1]]"))
        assertEquals(5, r.count)
    }

    @Test fun `round trip restores the original text`() {
        val input = "Send the quote to kyle@fences.example and text 020 7946 0958 when done."
        val r = airlock.redact(input)
        assertEquals(input, airlock.rehydrate(r.text, r.vault))
    }

    @Test fun `same value gets the same placeholder`() {
        val r = airlock.redact("a@b.co wrote, then a@b.co wrote again, then c@d.co")
        assertEquals("[[EMAIL_1]] wrote, then [[EMAIL_1]] wrote again, then [[EMAIL_2]]", r.text)
    }

    @Test fun `ordinary text, times and small amounts are left alone`() {
        val input = "Lunch at 12:30 on Thursday, table for 4, budget 250 dollars."
        val r = airlock.redact(input)
        assertEquals(input, r.text)
        assertEquals(0, r.count)
    }

    @Test fun `rehydrate fills placeholders the model reused in its answer`() {
        val r = airlock.redact("Reply to dana@example.org")
        assertEquals("Sure, emailing dana@example.org now.", airlock.rehydrate("Sure, emailing [[EMAIL_1]] now.", r.vault))
    }

    @Test fun `unknown placeholders are left untouched`() {
        assertEquals("[[EMAIL_9]]", airlock.rehydrate("[[EMAIL_9]]", mapOf("[[EMAIL_1]]" to "x@y.zz")))
    }
}
