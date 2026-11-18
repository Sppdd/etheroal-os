package com.etheroal.os.voice

/**
 * Words the dictionary lacks but can be built from words it has: plurals, -ed, -ing, -ly,
 * regular endings, prefixes, British spellings, compounds (port of dizione/morphology.py).
 */
object Morphology {
    private val SHORT_STEM = Regex("(?:^|[^aeiou])[aeiou](?:[bcdfgklmnprstvz]|th)$")
    private val SIBILANT = setOf("s", "z", "ʃ", "ʒ", "tʃ", "dʒ")
    private val VOICELESS = setOf("p", "t", "k", "f", "θ", "ʔ")
    private val SPLIT = mapOf("ɑːɹ" to "ɑː", "ɔːɹ" to "ɔː", "oːɹ" to "oː", "ɪɹ" to "ɪ", "ɛɹ" to "ɛ")
    private val RHOTIC = setOf("ɚ", "ɜː", "aɪɚ", "ʊɹ")
    private val CONSONANT_LETTERS = "bcdfghjklmnpqrstvwxz".toSet()
    private fun p(vararg phones: String): Phones = phones.map { Ph(it) }
    private fun ph(vararg pairs: Pair<String, Int>): Phones = pairs.map { Ph(it.first, it.second) }

    private val ENDINGS: List<Pair<String, Phones>> = listOf(
        "fully" to p("f", "əl", "i"), "lessly" to p("l", "ə", "s", "l", "i"), "ness" to p("n", "ə", "s"),
        "less" to p("l", "ə", "s"), "ments" to p("m", "ə", "n", "t", "s"), "ment" to p("m", "ə", "n", "t"),
        "ful" to p("f", "əl"), "ship" to ph("ʃ" to 0, "ɪ" to 2, "p" to 0), "hood" to ph("h" to 0, "ʊ" to 2, "d" to 0),
        "like" to ph("l" to 0, "aɪ" to 2, "k" to 0), "dom" to p("d", "ə", "m"), "ish" to p("ɪ", "ʃ"),
        "able" to p("ə", "b", "əl"), "ism" to p("ɪ", "z", "ə", "m"), "ist" to p("ɪ", "s", "t"),
        "wards" to p("w", "ɚ", "d", "z"), "ward" to p("w", "ɚ", "d"), "age" to p("ɪ", "dʒ"),
        "ers" to p("ɚ", "z"), "er" to p("ɚ"), "est" to p("ɪ", "s", "t"), "y" to p("i"),
    )
    private val PREFIXES: List<Triple<String, Phones, Phones>> = listOf(
        Triple("under", ph("ʌ" to 2, "n" to 0, "d" to 0, "ɚ" to 0), ph("ʌ" to 2, "n" to 0, "d" to 0, "ɚ" to 0)),
        Triple("over", ph("oʊ" to 2, "v" to 0, "ɚ" to 0), ph("oʊ" to 2, "v" to 0, "ɚ" to 0)),
        Triple("anti", ph("æ" to 2, "n" to 0, "t" to 0, "i" to 0), ph("æ" to 2, "n" to 0, "t" to 0, "i" to 0)),
        Triple("semi", ph("s" to 0, "ɛ" to 2, "m" to 0, "i" to 0), ph("s" to 0, "ɛ" to 2, "m" to 0, "i" to 0)),
        Triple("non", ph("n" to 0, "ɑː" to 2, "n" to 0), ph("n" to 0, "ɑː" to 2, "n" to 0)),
        Triple("out", ph("aʊ" to 2, "t" to 0), ph("aʊ" to 2, "t" to 0)),
        Triple("dis", p("d", "ɪ", "s"), ph("d" to 0, "ɪ" to 2, "s" to 0)),
        Triple("mis", p("m", "ɪ", "s"), ph("m" to 0, "ɪ" to 2, "s" to 0)),
        Triple("un", p("ʌ", "n"), ph("ʌ" to 2, "n" to 0)),
        Triple("in", p("ɪ", "n"), ph("ɪ" to 2, "n" to 0)),
        Triple("re", p("ɹ", "ᵻ"), ph("ɹ" to 0, "iː" to 2)),
        Triple("pre", ph("p" to 0, "ɹ" to 0, "iː" to 2), ph("p" to 0, "ɹ" to 0, "iː" to 2)),
    )
    private val BRITISH = listOf(
        "isation" to "ization", "ising" to "izing", "ised" to "ized", "ises" to "izes", "ise" to "ize", "yse" to "yze",
        "our" to "or", "ourable" to "orable", "ours" to "ors", "oured" to "ored", "ouring" to "oring",
        "tre" to "ter", "tres" to "ters", "bre" to "ber", "ence" to "ense", "ogue" to "og", "mme" to "m",
        "lled" to "led", "lling" to "ling", "ller" to "ler", "oul" to "ol", "ae" to "e", "enqui" to "inqui",
    )

    /** Stem + ending, adjusted at the seam. */
    fun join(stemIn: Phones, ending: Phones): Phones {
        if (stemIn.isEmpty() || ending.isEmpty()) return stemIn + ending
        val stem = stemIn.toMutableList()
        val (last, stress) = stem.last()
        val (first, firstStress) = ending[0]
        if (first in Symbols.VOWEL_SET) {
            when {
                last in SPLIT -> { stem.removeAt(stem.lastIndex); stem += Ph(SPLIT.getValue(last), stress); stem += Ph("ɹ") }
                last in RHOTIC -> stem += Ph("ɹ")
                last == "t" && stem.size > 1 && (stem[stem.size - 2].phone in Symbols.VOWEL_SET || stem[stem.size - 2].phone == "ɹ") &&
                    firstStress == 0 && !(first == "ə" && ending.size > 1 && ending[1].phone == "n") -> stem[stem.lastIndex] = Ph("ɾ")
                last == "i" -> stem[stem.lastIndex] = if (first != "ɪ") Ph("ɪ", stress) else Ph("i", stress)
            }
        } else if (last == "i" && first in setOf("n", "l", "f", "m")) {
            stem[stem.lastIndex] = Ph("ɪ", stress)
        } else if (last == "n" && first in setOf("k", "ɡ")) {
            stem[stem.lastIndex] = Ph("ŋ")
        }
        return stem + ending
    }

    fun plural(stem: Phones): Phones {
        val last = stem.last().phone
        return join(stem, if (last in SIBILANT) p("ᵻ", "z") else if (last in VOICELESS) p("s") else p("z"))
    }

    fun past(stem: Phones): Phones {
        val last = stem.last().phone
        if (last in setOf("t", "d", "ɾ")) return join(stem, p("ᵻ", "d"))
        return join(stem, if (last in VOICELESS || last in setOf("s", "ʃ", "tʃ")) p("t") else p("d"))
    }

    private fun adverb(stem: Phones): Phones = join(stem, if (stem.last().phone in setOf("əl", "l")) p("i") else p("l", "i"))

    private fun stems(body: String): List<String> {
        val out = mutableListOf(body)
        if (body.length > 2 && body.last() == body[body.length - 2] && body.last() in CONSONANT_LETTERS) out += body.dropLast(1)
        if (body.isNotEmpty() && (body.last() in CONSONANT_LETTERS || body.last() == 'u')) {
            if (SHORT_STEM.containsMatchIn(body)) out.add(0, body + "e") else out += body + "e"
        }
        if (body.endsWith("i") && body.length > 2) out += body.dropLast(1) + "y"
        return out
    }

    fun compound(first: Phones, second: Phones): Phones = join(first, second.map { if (it.stress == 1) Ph(it.phone, 2) else it })

    fun derive(word: String, lookup: (String) -> Phones?, part: (String) -> Phones? = lookup, depth: Int = 0): Phones? {
        fun stem(s: String): Phones? = if (s.length < 2) null else lookup(s) ?: if (depth < 2) derive(s, lookup, part, depth + 1) else null
        fun first(candidates: List<String>, build: (Phones) -> Phones?): Phones? {
            for (c in candidates) stem(c)?.let { return build(it) }
            return null
        }
        if (word.endsWith("'s")) return first(listOf(word.dropLast(2)), ::plural)
        if (word.endsWith("s'")) return first(listOf(word.dropLast(1))) { it }
        if (word.endsWith("ies") && word.length > 4) first(listOf(word.dropLast(3) + "y"), ::plural)?.let { return it }
        if (word.endsWith("ves") && word.length > 4) first(listOf(word.dropLast(3) + "f", word.dropLast(3) + "fe")) { f ->
            if (f.last().phone == "f") join(f.dropLast(1) + Ph("v"), p("z")) else null
        }?.let { return it }
        if (word.endsWith("es") && word.length > 3) first(if (word[word.length - 3] in "sxzh") listOf(word.dropLast(2)) else emptyList(), ::plural)?.let { return it }
        if (word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") && word.length > 3) {
            first(listOf(word.dropLast(1)), ::plural)?.let { return it }
        }
        if (word.endsWith("ied") && word.length > 4) first(listOf(word.dropLast(3) + "y"), ::past)?.let { return it }
        if (word.endsWith("ed") && word.length > 3) first(stems(word.dropLast(2)), ::past)?.let { return it }
        if (word.endsWith("ing") && word.length > 4) first(stems(word.dropLast(3))) { join(it, p("ɪ", "ŋ")) }?.let { return it }
        if (word.endsWith("ily") && word.length > 4) first(listOf(word.dropLast(3) + "y"), ::adverb)?.let { return it }
        if (word.endsWith("ly") && word.length > 3) {
            val c = if (word.endsWith("bly") || word.endsWith("ply")) listOf(word.dropLast(2), word.dropLast(2) + "le", word.dropLast(1) + "e")
            else listOf(word.dropLast(2))
            first(c, ::adverb)?.let { return it }
        }
        for ((spelling, phones) in ENDINGS) {
            if (word.endsWith(spelling) && word.length - spelling.length > 2) {
                val body = word.dropLast(spelling.length)
                if (spelling == "y" && body.last() !in CONSONANT_LETTERS) continue
                val vowelFirst = phones[0].phone in Symbols.VOWEL_SET
                val candidates = if (vowelFirst) stems(body) else if (body.endsWith("i")) listOf(body.dropLast(1) + "y", body) else listOf(body)
                first(candidates) { join(it, phones) }?.let { return it }
            }
        }
        for ((spelling, plain, stressed) in PREFIXES) {
            if (word.startsWith(spelling) && word.length - spelling.length > 2) {
                stem(word.substring(spelling.length))?.let { found ->
                    val vowels = found.filter { it.phone in Symbols.VOWEL_SET }.map { it.stress }
                    return join(if (vowels.isNotEmpty() && vowels[0] == 1) plain else stressed, found)
                }
            }
        }
        for ((old, new) in BRITISH) if (old in word) stem(word.replaceFirst(old, new))?.let { return it }
        if (depth == 0) {
            var best: Triple<Int, Phones, Phones>? = null
            for (cut in 4 until word.length - 3) {
                val left = part(word.substring(0, cut)); val right = part(word.substring(cut))
                if (left != null && right != null) {
                    val score = minOf(cut, word.length - cut)
                    if (best == null || score > best.first) best = Triple(score, left, right)
                }
            }
            best?.let { return compound(it.second, it.third) }
        }
        return null
    }
}
