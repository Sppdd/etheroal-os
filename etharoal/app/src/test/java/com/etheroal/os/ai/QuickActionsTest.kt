package com.etheroal.os.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class QuickActionsTest {

    @Test fun `known functions become typed actions`() {
        assertEquals(PhoneAction.Flashlight(true), PhoneActions.build("turn_on_flashlight", emptyMap()))
        assertEquals(PhoneAction.WifiSettings, PhoneActions.build("open_wifi_settings", emptyMap()))
        val event = PhoneActions.build("create_calendar_event", mapOf("title" to "Lunch", "datetime" to "2026-11-20T13:00:00"))
        assertEquals(PhoneAction.CalendarEvent("Lunch", LocalDateTime.of(2026, 11, 20, 13, 0)), event)
    }

    @Test fun `unknown functions and bad arguments are refused`() {
        assertNull(PhoneActions.build("delete_all_contacts", emptyMap()))
        assertNull(PhoneActions.build("create_calendar_event", mapOf("title" to "Lunch", "datetime" to "next friday")))
        assertNull(PhoneActions.build("create_calendar_event", mapOf("datetime" to "2026-11-20T13:00:00")))
        assertNull(PhoneActions.build("send_email", mapOf("to" to "not an address", "subject" to "hi")))
        assertNull(PhoneActions.build("send_email", mapOf("to" to "a@b.co\nBcc: x@y.zz", "subject" to "hi")))
        assertNull(PhoneActions.build("show_map", mapOf("query" to "   ")))
        assertNull(PhoneActions.build("create_contact", mapOf("first_name" to "", "last_name" to "")))
    }

    @Test fun `arguments are trimmed, capped and cleaned`() {
        val map = PhoneActions.build("show_map", mapOf("query" to "x".repeat(500))) as PhoneAction.Map
        assertEquals(200, map.query.length)
        val c = PhoneActions.build("create_contact", mapOf("first_name" to "Sam", "last_name" to "Lee", "phone_number" to "+1 (415) 555-0134; rm -rf"))
            as PhoneAction.Contact
        assertTrue(c.phone.all { it.isDigit() || it in "+ -()" })
    }

    @Test fun `only FunctionGemma files are treated as the action model`() {
        assertTrue(PhoneActions.isActionModel("mobile_actions_q8_ekv1024.litertlm"))
        assertTrue(PhoneActions.isActionModel("functiongemma-270m-ft-mobile-actions.litertlm"))
        assertEquals(false, PhoneActions.isActionModel("gemma-4-E2B-it.litertlm"))
    }
}
