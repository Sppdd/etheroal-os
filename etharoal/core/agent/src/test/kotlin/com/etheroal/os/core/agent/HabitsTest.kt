package com.etheroal.os.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitsTest {
    private fun launch(key: String, hour: Int, weekday: Boolean = true) = HabitEvent(HabitEvent.Kind.APP_LAUNCH, key, key.substringAfterLast('.'), 0, hour, weekday)
    private fun notif(kind: HabitEvent.Kind, key: String, label: String) = HabitEvent(kind, key, label, 0, 10, true)

    @Test fun `suggests apps used around this hour, most used first`() {
        val events = List(5) { launch("com.mail", 8) } + List(3) { launch("com.news", 9) } + List(9) { launch("com.games", 22) } + List(2) { launch("com.maps", 8) }
        assertEquals(listOf("com.mail", "com.news"), Habits.suggestApps(events, hour = 8, weekday = true))
        assertTrue(Habits.suggestApps(events, hour = 8, weekday = false).isEmpty())
    }

    @Test fun `summarizes routines and who gets answered`() {
        val events = List(6) { launch("com.mail", 8) } +
            List(3) { notif(HabitEvent.Kind.NOTIF_REPLIED, "wa|Sam", "Sam (WhatsApp)") } +
            List(6) { notif(HabitEvent.Kind.NOTIF_DONE, "shop|Deals", "Deals (Shop)") }
        val text = Habits.summarize(events)
        assertTrue(text, text.contains("Often opens mail around 8:00"))
        assertTrue(text, text.contains("Usually replies to Sam (WhatsApp)"))
        assertTrue(text, text.contains("Always clears Deals (Shop)"))
        assertEquals(setOf("shop|Deals"), Habits.ignored(events))
        assertEquals(setOf("wa|Sam"), Habits.answered(events))
    }

    @Test fun `too little data says nothing`() {
        assertEquals("", Habits.summarize(List(2) { launch("com.mail", 8) }))
    }
}
