package com.etheroal.os.core.agent

import com.etheroal.os.core.ai.Airlock

/**
 * [Airlock] for a whole session: the same email or number keeps the same placeholder across every
 * turn, so the model can refer back to "[[PHONE_1]]" ten steps later and the tool still receives
 * the real number. Values never leave this object except to tools on the phone.
 */
class SessionRedactor(private val airlock: Airlock = Airlock()) {
    private val byValue = HashMap<String, String>()
    private val byPlaceholder = HashMap<String, String>()

    @Synchronized
    fun redact(text: String): String {
        val r = airlock.redact(text)
        if (r.vault.isEmpty()) return r.text
        // Airlock numbers from 1 on every call; map its placeholders onto this session's.
        return PLACEHOLDER.replace(r.text) { m ->
            val value = r.vault[m.value] ?: return@replace m.value
            byValue.getOrPut(value) {
                val label = m.value.removePrefix("[[").substringBefore('_')
                var n = 1
                while ("[[${label}_$n]]" in byPlaceholder) n++
                "[[${label}_$n]]".also { byPlaceholder[it] = value }
            }
        }
    }

    @Synchronized
    fun rehydrate(text: String): String =
        if (byPlaceholder.isEmpty()) text else PLACEHOLDER.replace(text) { byPlaceholder[it.value] ?: it.value }

    fun rehydrate(call: ToolCall): ToolCall =
        call.copy(args = call.args.mapValues { (_, v) -> if (v is String) rehydrate(v) else v })

    val count: Int @Synchronized get() = byPlaceholder.size

    private companion object {
        val PLACEHOLDER = Regex("""\[\[[A-Z]+_\d+]]""")
    }
}
