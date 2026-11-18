package com.etheroal.os.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPolicyTest {
    private val own = "com.etheroal.os"
    private val screen = listOf(
        ScreenElement(1, "TextView", "Sam"),
        ScreenElement(2, "EditText", "Message", editable = true, clickable = true),
        ScreenElement(3, "Button", "Send", clickable = true),
        ScreenElement(4, "EditText", "", editable = true, password = true),
        ScreenElement(5, "ImageButton", "", clickable = true),
        ScreenElement(6, "Button", "Search", clickable = true),
    )
    private fun el(i: Int) = screen.firstOrNull { it.index == i }

    @Test fun `ordinary taps and typing are allowed`() {
        assertEquals(Verdict.Allowed, ScreenPolicy.checkTap(el(6), 6, false))
        assertEquals(Verdict.Allowed, ScreenPolicy.checkType(el(2), 2, "On my way"))
    }

    @Test fun `committing buttons need the owner`() {
        assertTrue(ScreenPolicy.checkTap(el(3), 3, false) is Verdict.NeedsOwner)
        for (label in listOf("Pay now", "Place order", "DELETE", "Sign in", "Buy", "Allow", "Confirm transfer", "Install")) {
            assertTrue(label, ScreenPolicy.isSensitive(label))
        }
        for (label in listOf("Search", "Sam", "Chats", "Sender details", "Payment history", "Bookmarks")) {
            assertFalse(label, ScreenPolicy.isSensitive(label))
        }
    }

    @Test fun `an unlabelled button after typing needs the owner`() {
        assertEquals(Verdict.Allowed, ScreenPolicy.checkTap(el(5), 5, false))
        assertTrue(ScreenPolicy.checkTap(el(5), 5, true) is Verdict.NeedsOwner)
    }

    @Test fun `password fields are never touched or described`() {
        assertTrue(ScreenPolicy.checkType(el(4), 4, "hunter2") is Verdict.Refused)
        assertTrue(ScreenPolicy.checkTap(el(4), 4, false) is Verdict.Refused)
        val secret = ScreenElement(7, "EditText", "hunter2", editable = true, password = true)
        assertFalse(ScreenPolicy.describe(listOf(secret)).contains("hunter2"))
    }

    @Test fun `bad targets and bad text are refused`() {
        assertTrue(ScreenPolicy.checkTap(el(99), 99, false) is Verdict.Refused)
        assertTrue(ScreenPolicy.checkType(el(1), 1, "x") is Verdict.Refused)
        assertTrue(ScreenPolicy.checkType(el(2), 2, "  ") is Verdict.Refused)
        assertTrue(ScreenPolicy.checkType(el(2), 2, "x".repeat(501)) is Verdict.Refused)
    }

    @Test fun `settings, permission screens and Etheroal itself are off limits`() {
        for (pkg in listOf("com.android.settings", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.hihonor.systemmanager", own)) {
            assertTrue(pkg, ScreenPolicy.checkApp(pkg, own) is Verdict.Refused)
        }
        assertEquals(Verdict.Allowed, ScreenPolicy.checkApp("com.example.chat", own))
    }
}
