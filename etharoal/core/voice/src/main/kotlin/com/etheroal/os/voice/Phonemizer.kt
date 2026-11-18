package com.etheroal.os.voice

import java.io.InputStream

/**
 * English text -> phoneme ids and stress for tiny-voca (port of dizione/engine.py and sentence.py).
 *
 * Order of authority for one word: the hand-written exceptions, function words and joined
 * function-word pairs, the 124,926-word dictionary, regular endings and compounds, and finally
 * the unknown-word model. Not ported: the homograph classifier (homographs take their dictionary
 * pronunciation) and multi-word names with their own rhythm.
 *
 * @param open reads a data file by name (lexicon.tsv, g2p.json, g2p.npz, *.txt).
 */
class Phonemizer(open: (String) -> InputStream) {

    private data class Fn(val kind: String, val form: Phones, val final: Phones?)
    private data class Phrase(val kind: String, val forms: List<Phones>, val final: Phones?)

    private val lexicon = HashMap<String, String>(140_000)
    private val functions = HashMap<String, Fn>()
    private val phrases = HashMap<List<String>, Phrase>()
    private val phraseLength: Int
    private val exceptions = HashMap<String, Phones>()
    private val letterNames = HashMap<Char, Phones>()
    private val g2p: G2p
    private val cache = object : LinkedHashMap<String, Phones>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Phones>?) = size > 4000
    }
    private val normalizer = TextNormalizer(::isWord)

    init {
        open("lexicon.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line -> val tab = line.indexOf('\t'); if (tab > 0) lexicon[line.substring(0, tab)] = line.substring(tab + 1) }
        }
        rows(open("function_words.txt")).forEach { c ->
            functions[c[0]] = Fn(c[1], fromText(c[2]), c.getOrNull(3)?.let(::fromText))
        }
        rows(open("phrases.txt")).forEach { c ->
            phrases[c[0].split(' ')] = Phrase(c[1], c[2].split('|').map { fromText(it) }, c.getOrNull(3)?.let(::fromText))
        }
        phraseLength = phrases.keys.maxOfOrNull { it.size } ?: 1
        rows(open("exceptions.txt")).forEach { c ->
            if (c.size == 2 && !c[0].contains(' ')) exceptions[c[0].lowercase()] = pronunciation(c[0].lowercase(), c[1])
        }
        rows(open("letters.txt")).forEach { c -> if (c.size == 2) letterNames[c[0][0]] = fromText(c[1]) }
        g2p = G2p(open("g2p.json").readBytes().toString(Charsets.UTF_8), open("g2p.npz"))
    }

    /** Text -> the token sequence the model takes. Throws if nothing is pronounceable. */
    fun phonemize(text: String): Phonemized {
        require(text.isNotBlank()) { "text must not be empty" }
        return tokenize(pieces(text))
    }

    fun pieces(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        val words = mutableListOf<TextNormalizer.Token.Word>()
        fun flush() {
            if (words.isEmpty()) return
            val units = units(words)
            stressClause(units)
            joinClause(units)
            out += Piece.Words(units.map { it.phones }.filter { it.isNotEmpty() })
            words.clear()
        }
        for (token in normalizer.tokens(text)) {
            when (token) {
                is TextNormalizer.Token.Punct -> { flush(); out += Piece.Mark(token.mark) }
                is TextNormalizer.Token.Word -> words += token
            }
        }
        flush()
        return out
    }

    fun isWord(word: String) = word in lexicon || word in functions || word in exceptions

    // ---- single words ----------------------------------------------------

    private fun known(word: String): Phones? = exceptions[word] ?: lexicon[word]?.let(::fromText)

    private fun ordinary(word: String): Phones? {
        if (word.length < 3 || listOf("s", "es", "ed", "ing").none { (word + it) in lexicon }) return null
        return known(word)
    }

    fun word(word: String): Phones = cache.getOrPut(word) {
        known(word)
            ?: Morphology.derive(word, ::known, ::ordinary)?.let(Convert::settleStress)
            ?: if (word.endsWith("'s") && word.length > 3) Morphology.plural(guess(word.dropLast(2))) else guess(word)
    }

    /** The unknown-word model, then the conversion rules. */
    private fun guess(word: String): Phones {
        val spelling = g2p.spellable(word)
        val symbols = g2p.predict(spelling).toMutableList()
        for (k in symbols.indices) {
            if (symbols[k] == "R" && listOf(k - 1, k + 1).none { j -> symbols.getOrNull(j)?.last()?.let { it in "012" } == true }) {
                symbols[k] = "ER0"
            }
        }
        if (symbols.none { it.last() in "012" }) return spell(word)
        return promote(Convert.convert(spelling, symbols))
    }

    fun spell(letters: String): Phones {
        val possessive = letters.lowercase().endsWith("'s")
        val names = (if (possessive) letters.dropLast(2) else letters).lowercase().mapNotNull { letterNames[it] }
        val out = names.flatMapIndexed { index, name ->
            name.map { if (it.stress != 0 && index < names.size - 1) Ph(it.phone, 2) else it }
        }
        return if (possessive && out.isNotEmpty()) Morphology.plural(out) else out
    }

    private fun pronunciation(word: String, text: String): Phones {
        val symbols = text.trim().split(Regex("\\s+"))
        return if (Convert.isArpabet(symbols)) Convert.convert(word, symbols) else fromText(text)
    }

    // ---- sentences -------------------------------------------------------

    private class Unit(val word: String, var phones: Phones, val kind: String = "c", val final: Phones? = null) {
        val lastWord get() = word.substringAfterLast(' ')
        val firstWord get() = word.substringBefore(' ')
    }

    private fun units(words: List<TextNormalizer.Token.Word>): List<Unit> {
        val units = mutableListOf<Unit>()
        var index = 0
        while (index < words.size) {
            val token = words[index]
            if (token.spell) { units += Unit(token.text, spell(token.text), "p"); index++; continue }
            val size = phraseAt(words, index)
            if (size > 0) {
                val key = words.subList(index, index + size).map { it.text }
                val phrase = phrases.getValue(key)
                if (phrase.forms.size == 1) units += Unit(key.joinToString(" "), phrase.forms[0], phrase.kind, phrase.final)
                else key.zip(phrase.forms).forEach { (text, form) -> units += Unit(text, form, phrase.kind) }
                index += size
                continue
            }
            val fn = functions[token.text]
            units += if (fn != null && token.text !in exceptions) Unit(token.text, fn.form, fn.kind, fn.final)
            else Unit(token.text, word(token.text))
            index++
        }
        return units
    }

    private fun phraseAt(words: List<TextNormalizer.Token.Word>, index: Int): Int {
        for (size in minOf(phraseLength, words.size - index) downTo 2) {
            val span = words.subList(index, index + size)
            if (span.none { it.spell } && span.map { it.text } in phrases) return size
        }
        return 0
    }

    private fun unstress(p: Phones) = p.map { Ph(it.phone, 0) }

    private fun promote(phones: Phones): Phones {
        val marks = phones.map { it.stress }
        if (1 in marks) return phones
        val target = if (2 in marks) marks.lastIndexOf(2) else phones.indexOfFirst { it.phone in Symbols.VOWEL_SET }
        return phones.mapIndexed { i, p -> if (i == target) Ph(p.phone, 1) else p }
    }

    private fun stressClause(units: List<Unit>) {
        var primaryFollows = false
        for (index in units.indices.reversed()) {
            val u = units[index]
            val promoted = { u.final ?: promote(u.phones) }
            u.phones = when (u.kind) {
                "u" -> unstress(u.phones)
                "uf" -> if (index == units.lastIndex) promoted() else unstress(u.phones)
                "ur" -> if (primaryFollows) unstress(u.phones) else promoted()
                "s" -> if (primaryFollows) u.phones else promoted()
                "si" -> if (!primaryFollows) promoted() else if (index == 0) u.phones else unstress(u.phones)
                "pf" -> if (index == units.lastIndex) promoted() else u.phones
                else -> u.phones
            }
            primaryFollows = primaryFollows || u.phones.any { it.stress == 1 }
        }
        if (units.isNotEmpty() && units.none { u -> u.phones.any { it.stress != 0 } }) {
            units.lastOrNull { u -> u.phones.any { it.phone in Symbols.VOWEL_SET } }?.let { it.phones = promote(it.phones) }
        }
    }

    private fun joinClause(units: List<Unit>) {
        for (k in 0 until units.size - 1) {
            val unit = units[k]; val following = units[k + 1]
            if (unit.phones.isEmpty() || following.phones.isEmpty() || following.phones[0].phone !in Symbols.VOWEL_SET) continue
            val (last, stress) = unit.phones.last()
            val head = unit.phones.dropLast(1)
            unit.phones = when {
                unit.lastWord == "the" && last == "ə" -> head + Ph("ɪ", stress)
                unit.lastWord == "to" && last == "ə" -> head + Ph("ʊ", stress)
                following.firstWord in NO_LINK_BEFORE -> unit.phones
                last in SPLIT -> head + Ph(SPLIT.getValue(last), stress) + Ph("ɹ")
                last in RHOTIC -> unit.phones + Ph("ɹ")
                last == "t" && unit.word in FLAP_BEFORE_VOWEL && unit.phones.size > 1 &&
                    unit.phones[unit.phones.size - 2].phone in Symbols.VOWEL_SET -> head + Ph("ɾ")
                else -> unit.phones
            }
        }
    }

    private companion object {
        val SPLIT = mapOf("ɑːɹ" to "ɑː", "ɔːɹ" to "ɔː", "oːɹ" to "oː", "ɪɹ" to "ɪ", "ɛɹ" to "ɛ", "ʊɹ" to "ʊ")
        val RHOTIC = setOf("ɚ", "ɜː", "aɪɚ")
        val NO_LINK_BEFORE = setOf("and", "or", "if")
        val FLAP_BEFORE_VOWEL = setOf("it", "at")
        val COLUMNS = Regex("\\t+| {2,}")

        fun rows(input: InputStream): List<List<String>> = input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> line.split(COLUMNS).map { it.trim() } }.toList()
        }
    }
}
