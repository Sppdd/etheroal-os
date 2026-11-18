package com.etheroal.os.voice

/**
 * Text -> words and punctuation marks for the phonemizer.
 *
 * A compact rewrite of the common cases of dizione/normalize.py, not a port of all of it:
 * numbers, decimals, ordinals, years, money, percentages, times, telephone numbers, the
 * Airlock's placeholders, links, emoji and initialisms. Uncommon formats (dates in numbers,
 * version strings, units, maths) are read digit by digit or skipped rather than guessed.
 */
class TextNormalizer(private val isWord: (String) -> Boolean) {

    sealed interface Token {
        data class Word(val text: String, val spell: Boolean = false) : Token
        data class Punct(val mark: String) : Token
    }

    fun tokens(input: String): List<Token> {
        var text = input
            .replace(Regex("\\[\\[([A-Z]+)_\\d+]]")) { PLACEHOLDERS[it.groupValues[1]] ?: "something" }
            .replace(Regex("https?://\\S+|www\\.\\S+"), " link ")
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), " email address ")
            .replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
            .replace("...", "…")
            .replace(" & ", " and ")
        text = dropEmoji(text)
        text = MONEY.replace(text) { money(it) }
        text = PERCENT.replace(text) { "${number(it.groupValues[1])} percent" }
        text = TIME.replace(text) { time(it) }
        text = ORDINAL.replace(text) { ordinal(it.groupValues[1].toLong()) }
        text = PHONE.replace(text) { m -> digits(m.value, oh = true) }
        text = LONG_DIGITS.replace(text) { m -> digits(m.value, oh = false) }
        text = YEAR.replace(text) { year(it.value.toInt()) }
        text = NUMBER.replace(text) { number(it.value) }

        val out = mutableListOf<Token>()
        for (m in TOKEN.findAll(text)) {
            val t = m.value
            val mark = MARKS[t]
            when {
                mark != null -> out += Token.Punct(mark)
                t[0].isLetter() -> t.split('-').filter { it.isNotEmpty() }.forEach { part -> out += word(part) }
            }
        }
        return out
    }

    private fun word(raw: String): Token.Word {
        val lower = raw.lowercase().trim('\'')
        // Initialisms: "NASA" stays a word if the dictionary knows it, "BTW" or "SMS" is spelled.
        val initialism = raw.length in 2..5 && raw.all { it.isUpperCase() || it == '\'' } && !isWord(lower)
        return Token.Word(lower, spell = initialism)
    }

    private fun dropEmoji(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val type = Character.getType(cp)
            val emoji = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt() ||
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D
            if (!emoji) b.appendCodePoint(cp) else b.append(' ')
            i += Character.charCount(cp)
        }
        return b.toString()
    }

    private fun money(m: MatchResult): String {
        val (symbol, whole, cents) = Triple(m.groupValues[1], m.groupValues[2].replace(",", ""), m.groupValues[3])
        val (one, many, sub) = CURRENCY.getValue(symbol)
        val amount = whole.toLong()
        val parts = mutableListOf("${spell(amount)} ${if (amount == 1L) one else many}")
        if (cents.isNotEmpty() && cents.toInt() > 0) {
            val c = cents.padEnd(2, '0').take(2).toInt()
            parts += "${spell(c.toLong())} ${if (c == 1) sub.removeSuffix("s") else sub}"
        }
        return " " + parts.joinToString(" and ") + " "
    }

    private fun time(m: MatchResult): String {
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt(); val ampm = m.groupValues[3].lowercase()
        val minutes = when {
            min == 0 && ampm.isNotEmpty() -> ""
            min == 0 -> "hundred"
            min < 10 -> "oh ${spell(min.toLong())}"
            else -> spell(min.toLong())
        }
        val suffix = if (ampm.isNotEmpty()) " ${ampm.filter { it.isLetter() }.uppercase().toList().joinToString(" ")}" else ""
        return " ${spell(h.toLong())} $minutes$suffix ".replace(Regex(" +"), " ")
    }

    private fun digits(s: String, oh: Boolean): String = " " + s.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }.joinToString(" , ") { group ->
        group.filter { it.isDigit() || it == '+' }.map { c ->
            when (c) { '+' -> "plus"; '0' -> if (oh) "oh" else "zero"; else -> ONES[c.digitToInt()] }
        }.joinToString(" ")
    } + " "

    private fun year(y: Int): String = when {
        y in 2000..2009 -> spell(y.toLong())
        y % 100 == 0 -> "${spell((y / 100).toLong())} hundred"
        y % 100 < 10 -> "${spell((y / 100).toLong())} oh ${spell((y % 100).toLong())}"
        else -> "${spell((y / 100).toLong())} ${spell((y % 100).toLong())}"
    }

    private fun number(raw: String): String {
        val clean = raw.replace(",", "")
        val negative = clean.startsWith("-")
        val body = clean.removePrefix("-")
        val (whole, frac) = body.split('.', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val words = buildString {
            if (negative) append("minus ")
            append(if (whole.isEmpty()) "zero" else spell(whole.toLong()))
            if (frac.isNotEmpty()) append(" point " + frac.map { ONES[it.digitToInt()].ifEmpty { "zero" } }.joinToString(" "))
        }
        return " $words "
    }

    private fun ordinal(n: Long): String {
        val words = spell(n).split(' ').toMutableList()
        val last = words.last()
        words[words.lastIndex] = ORDINAL_WORDS[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return " " + words.joinToString(" ") + " "
    }

    companion object {
        private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        private val SCALES = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        private val ORDINAL_WORDS = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
            "nine" to "ninth", "twelve" to "twelfth",
        )
        private val PLACEHOLDERS = mapOf(
            "EMAIL" to "email address", "PHONE" to "phone number", "LINK" to "link", "CARD" to "card number",
            "CODE" to "code", "NUMBER" to "number",
        )
        private val CURRENCY = mapOf(
            "$" to Triple("dollar", "dollars", "cents"), "£" to Triple("pound", "pounds", "pence"),
            "€" to Triple("euro", "euros", "cents"), "¥" to Triple("yen", "yen", "sen"),
        )
        private val MARKS = mapOf(
            "," to ",", "." to ".", "?" to "?", "!" to "!", ";" to ";", ":" to ":", "\"" to "\"",
            "(" to "(", ")" to ")", "—" to "—", "–" to "—", "…" to "…",
        )

        private val MONEY = Regex("([$£€¥])\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?")
        private val PERCENT = Regex("(-?\\d+(?:\\.\\d+)?)\\s?%")
        private val TIME = Regex("\\b(\\d{1,2}):(\\d{2})(?!:)\\s*((?:[aApP]\\.?[mM]\\.?)?)")
        private val ORDINAL = Regex("\\b(\\d+)(?:st|nd|rd|th)\\b")
        private val PHONE = Regex("(?<![\\w])\\+?\\d{2,4}(?:[\\s-]\\d{2,4}){2,}(?![\\w])")
        private val LONG_DIGITS = Regex("\\b\\d{7,}\\b")
        private val YEAR = Regex("\\b(1[1-9]\\d{2}|20\\d{2})\\b(?![.,]\\d)")
        private val NUMBER = Regex("-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|-?\\d+(?:\\.\\d+)?")
        private val TOKEN = Regex("[A-Za-z][A-Za-z'-]*|[,.?!;:\"()—–…]")

        /** 0..999 999 999 999 999 as words, without "and" (American usage). */
        fun spell(n: Long): String {
            if (n < 0) return "minus " + spell(-n)
            if (n < 10) return ONES[n.toInt()]
            if (n < 20) return TEENS[(n - 10).toInt()]
            if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
            if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + spell(n % 100) else ""
            for ((value, name) in SCALES) {
                if (n >= value) return spell(n / value) + " $name" + if (n % value != 0L) " " + spell(n % value) else ""
            }
            return n.toString()
        }
    }
}
