package com.etheroal.os.core.agent

/** Something the owner did, with no content: which app or sender, and when. */
data class HabitEvent(val kind: Kind, val key: String, val label: String, val atMs: Long, val hour: Int, val weekday: Boolean) {
    enum class Kind { APP_LAUNCH, NOTIF_OPENED, NOTIF_REPLIED, NOTIF_DONE }
}

/**
 * Turns the owner's recent behaviour into a few plain sentences the orchestrator reads, and into
 * app suggestions for the home screen. Computed on the phone from counts only; no model involved
 * and nothing about message content.
 */
object Habits {
    /** At least this many events before a pattern is believed. */
    private const val MIN = 3

    /** Apps the owner usually opens around [hour] (±1), most likely first. */
    fun suggestApps(events: List<HabitEvent>, hour: Int, weekday: Boolean, limit: Int = 4): List<String> =
        events.asSequence()
            .filter { it.kind == HabitEvent.Kind.APP_LAUNCH && near(it.hour, hour) && it.weekday == weekday }
            .groupingBy { it.key }.eachCount()
            .filter { it.value >= MIN }
            .entries.sortedByDescending { it.value }.take(limit).map { it.key }

    fun summarize(events: List<HabitEvent>): String {
        val lines = ArrayList<String>()
        val labels = events.associate { it.key to it.label }

        // When the owner habitually opens each app.
        events.filter { it.kind == HabitEvent.Kind.APP_LAUNCH }.groupBy { it.key }
            .mapValues { (_, list) -> list }
            .filter { it.value.size >= MIN * 2 }
            .entries.sortedByDescending { it.value.size }.take(5)
            .forEach { (key, list) ->
                val peak = list.groupingBy { it.hour }.eachCount().maxBy { it.value }
                if (peak.value >= MIN) lines += "Often opens ${labels[key]} around ${peak.key}:00 (${list.size} times recently)."
            }

        // Which senders or apps get answered, and which get cleared without a look.
        val outcomes = events.filter { it.kind != HabitEvent.Kind.APP_LAUNCH }.groupBy { it.key }
        outcomes.forEach { (key, list) ->
            val replied = list.count { it.kind == HabitEvent.Kind.NOTIF_REPLIED }
            val opened = list.count { it.kind == HabitEvent.Kind.NOTIF_OPENED }
            val done = list.count { it.kind == HabitEvent.Kind.NOTIF_DONE }
            when {
                replied >= MIN -> lines += "Usually replies to ${labels[key]}."
                done >= MIN * 2 && opened + replied == 0 -> lines += "Always clears ${labels[key]} notifications without opening them."
            }
        }
        return lines.take(12).joinToString("\n")
    }

    /** Keys whose notifications the owner has cleared unread at least [times] times and never engaged with. */
    fun ignored(events: List<HabitEvent>, times: Int = 6): Set<String> =
        events.filter { it.kind != HabitEvent.Kind.APP_LAUNCH }.groupBy { it.key }
            .filter { (_, list) -> list.count { it.kind == HabitEvent.Kind.NOTIF_DONE } >= times && list.none { it.kind != HabitEvent.Kind.NOTIF_DONE } }
            .keys

    /** Keys the owner has replied to at least [times] times: people who matter to them. */
    fun answered(events: List<HabitEvent>, times: Int = 3): Set<String> =
        events.filter { it.kind == HabitEvent.Kind.NOTIF_REPLIED }.groupingBy { it.key }.eachCount()
            .filter { it.value >= times }.keys

    private fun near(a: Int, b: Int) = minOf((a - b + 24) % 24, (b - a + 24) % 24) <= 1
}
