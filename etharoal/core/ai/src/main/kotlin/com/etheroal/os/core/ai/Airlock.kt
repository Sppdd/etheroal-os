package com.etheroal.os.core.ai

/**
 * Replaces personal details with placeholders before text may leave the phone, and puts
 * them back into the model's answer afterwards. The cloud model reasons about
 * "[[EMAIL_1]]" and never sees the address itself.
 *
 * This layer is deterministic on purpose: it must work with no model, in the background,
 * and must be testable. An on-device model pass for names is layered on top later.
 */
class Airlock {

    data class Redacted(val text: String, val vault: Map<String, String>) {
        val count: Int get() = vault.size
    }

    fun redact(input: String): Redacted {
        val byValue = LinkedHashMap<String, String>()
        val counters = HashMap<String, Int>()
        var text = input
        for ((label, regex) in PATTERNS) {
            text = regex.replace(text) { match ->
                val value = match.value
                if (value.startsWith("[[")) return@replace value
                byValue.getOrPut(value) {
                    val n = (counters[label] ?: 0) + 1
                    counters[label] = n
                    "[[${label}_$n]]"
                }
            }
        }
        return Redacted(text, byValue.entries.associate { (value, placeholder) -> placeholder to value })
    }

    fun rehydrate(text: String, vault: Map<String, String>): String {
        if (vault.isEmpty()) return text
        return PLACEHOLDER.replace(text) { vault[it.value] ?: it.value }
    }

    private companion object {
        val PLACEHOLDER = Regex("""\[\[[A-Z]+_\d+]]""")

        // Order matters: the most specific shapes are replaced first.
        val PATTERNS: List<Pair<String, Regex>> = listOf(
            "EMAIL" to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
            "LINK" to Regex("""https?://[^\s<>"')\]]+"""),
            "CARD" to Regex("""\b(?:\d[ -]?){12,18}\d\b"""),
            "PHONE" to Regex("""(?<![\w\[])\+?\d[\d ().-]{7,}\d(?!\w)"""),
            "CODE" to Regex("""(?i)(?<=\b(?:code|otp|pin|passcode|password)\b.{0,12})\b\d{4,8}\b"""),
            "NUMBER" to Regex("""\b\d{6,}\b"""),
        )
    }
}
