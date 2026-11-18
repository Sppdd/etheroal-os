package com.etheroal.os.voice

/**
 * CMUdict notation -> phones of the inventory (port of dizione/convert.py).
 *
 * Dictionary words are already converted in lexicon.tsv. This is only used for words the
 * unknown-word model spells out and for ARPABET entries in the hand-written tables. It runs
 * without the spelling alignment, which is the original's documented fallback: rules that need
 * to know which letters spell a vowel take their default.
 */
object Convert {
    private val ARPA_VOWELS = "AA AE AH AO AW AY EH ER EY IH IY OW OY UH UW".split(" ").toSet()
    private val CONSONANTS = mapOf(
        "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ", "HH" to "h", "JH" to "dʒ",
        "K" to "k", "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "P" to "p", "R" to "ɹ", "S" to "s",
        "SH" to "ʃ", "T" to "t", "TH" to "θ", "V" to "v", "W" to "w", "Y" to "j", "Z" to "z", "ZH" to "ʒ",
    )
    private val FULL_VOWELS = mapOf(
        "AA" to "ɑː", "AE" to "æ", "AH" to "ʌ", "AO" to "ɔː", "AW" to "aʊ", "AY" to "aɪ", "EH" to "ɛ", "ER" to "ɜː",
        "EY" to "eɪ", "IH" to "ɪ", "IY" to "iː", "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "uː",
    )
    private val SIBILANTS = setOf("S", "Z", "SH", "ZH", "CH", "JH")
    private val ARPABET = CONSONANTS.keys + ARPA_VOWELS.flatMap { v -> listOf("${v}0", "${v}1", "${v}2") }
    private val VOWEL_LETTERS = "aeiouy".toSet()

    fun isArpabet(symbols: List<String>) = symbols.isNotEmpty() && symbols.all { it in ARPABET }

    private class W(val word: String, arpabet: List<String>) {
        val bases = arpabet.map { it.trimEnd('0', '1', '2') }
        val stress = arpabet.map { s -> s.last().takeIf { it in "012" }?.digitToInt() }
        val count = bases.size
        fun base(i: Int) = bases.getOrNull(i)
        fun vowel(i: Int) = i in 0 until count && bases[i] in ARPA_VOWELS
        fun unstressed(i: Int) = vowel(i) && (stress[i] ?: 0) == 0
        fun endsWith(i: Int, vararg s: String) = bases.subList(minOf(i + 1, count), count) == s.toList()
        fun firstVowel(i: Int) = bases.take(i).none { it in ARPA_VOWELS }
        fun nextVowelStress(i: Int): Int? = (i + 1 until count).firstOrNull { bases[it] in ARPA_VOWELS }?.let { stress[it] }
    }

    private fun inflection(w: W, i: Int): Boolean {
        if (i + 1 >= w.count) return false
        val nxt = w.base(i + 1); val prev = w.base(i - 1)
        if (nxt == "D" && prev in setOf("T", "D") && (w.endsWith(i, "D") || w.endsWith(i, "D", "L", "IY"))) return true
        return nxt == "Z" && prev in SIBILANTS && w.endsWith(i, "Z")
    }

    /** Unaligned: only the rules that read the whole word apply. */
    private fun firstSyllable(w: W, i: Int): String? {
        if (!w.firstVowel(i) || (w.nextVowelStress(i) ?: 0) == 0) return null
        val word = w.word
        if (i == 0 && word.startsWith("e")) {
            if (word.startsWith("ex")) return "ɛ"
            if (word.length > 2 && word[1] in "nms" && word[2] !in VOWEL_LETTERS && word[2] != word[1]) return "ɛ"
            return "ɪ"
        }
        return null
    }

    private fun reduced(w: W, i: Int): String {
        val name = w.bases[i]; val nxt = w.base(i + 1)
        if (name == "IH" && nxt == "NG") return "ɪ"
        if (inflection(w, i)) return "ᵻ"
        if (name == "AH") {
            if (i == 0 && w.word.startsWith("a")) return "ɐ"
            if (i == 0 && nxt == "N" && w.word.startsWith("un")) return "ʌ"
        }
        firstSyllable(w, i)?.let { return it }
        if (name == "AH" && nxt == "M" && w.endsWith(i, "M") && w.word.endsWith("ome")) return "ʌ"
        return if (name == "IH") "ɪ" else "ə"
    }

    private fun unstressedIy(w: W, i: Int): Ph {
        val nxt = w.base(i + 1)
        if (nxt == null || w.endsWith(i, "Z") || w.endsWith(i, "D")) return Ph("i")
        if (w.vowel(i + 1)) return Ph(if (nxt == "IH" && w.base(i + 2) == "NG") "i" else "ɪ")
        return Ph("ɪ")
    }

    fun convert(word: String, arpabet: List<String>): Phones {
        val w = W(word, arpabet)
        val out = mutableListOf<Array<Any>>()          // [phone, stress]
        fun emit(phone: String, stress: Int = 0) { out += arrayOf(phone, stress) }
        var i = 0
        while (i < w.count) {
            val name = w.bases[i]; val stress = w.stress[i] ?: 0; val nxt = w.base(i + 1)
            if (name !in ARPA_VOWELS) {
                if (name == "Y" && nxt == "AH" && w.unstressed(i + 1) && w.base(i - 1) in setOf("N", "L") && w.base(i + 2) != "L") {
                    emit("iə"); i += 2; continue                         // opinion, million
                }
                emit(CONSONANTS.getValue(name)); i += 1; continue
            }
            val beforeVowel = w.vowel(i + 2)
            when {
                name == "ER" -> {
                    emit(if (stress != 0) "ɜː" else "ɚ", stress)
                    if (nxt == "R") i += 1
                    if (w.vowel(i + 1)) emit("ɹ")
                }
                nxt == "R" && name == "AW" -> {
                    emit("aʊ", stress); emit("ɚ"); if (beforeVowel) emit("ɹ"); i += 1
                }
                nxt == "R" && name in setOf("AA", "AO", "IH", "IY", "EH", "UH", "UW", "AY") -> {
                    val (quality, single) = when (name) {
                        "AA" -> "ɑː" to "ɑːɹ"
                        "AO" -> "ɔː" to "ɔːɹ"
                        "IH", "IY" -> "ɪ" to "ɪɹ"
                        "EH" -> "ɛ" to "ɛɹ"
                        "UH", "UW" -> "ʊɹ" to "ʊɹ"
                        else -> "aɪɚ" to "aɪɚ"
                    }
                    if (beforeVowel) { emit(quality, stress); emit("ɹ") } else emit(single, stress)
                    i += 1
                }
                name == "AY" && nxt == "ER" && w.unstressed(i + 1) -> {
                    emit("aɪɚ", stress); i += 1; if (w.vowel(i + 1)) emit("ɹ")
                }
                name == "AY" && nxt == "AH" && w.unstressed(i + 1) -> { emit("aɪə", stress); i += 1 }
                name == "IY" && nxt == "AH" && w.unstressed(i + 1) -> {
                    val after = w.base(i + 2)
                    if (stress != 1 && (after in setOf("L", "D", "T") || (after == "N" && w.base(i + 3) == "S"))) emit("ɪ")
                    else { emit("iə", stress); i += 1 }
                }
                stress == 0 && name == "AH" && nxt == "N" && w.base(i - 1) == "T" && w.base(i + 2) != "T" &&
                    out.size >= 2 && out.last()[0] == "t" && out[out.size - 2][0] in Symbols.VOWEL_SET && out[out.size - 2][1] != 0 -> {
                    out.last()[0] = "ʔ"; emit("n̩"); i += 1                // button, certain
                }
                stress == 0 && name == "AH" && nxt == "L" && i > 0 && !(w.vowel(i + 2) && (w.stress[i + 2] ?: 0) != 0) -> {
                    emit("əl"); i += 1                                    // little, finally
                }
                stress == 0 && (name == "AH" || name == "IH") -> emit(reduced(w, i))
                name == "IY" && (stress == 0 || (stress == 2 && nxt == null)) -> unstressedIy(w, i).let { emit(it.phone, it.stress) }
                stress == 0 && name == "AE" && i == 0 && (w.word.startsWith("ad") || w.word.startsWith("acc")) && nxt in setOf("D", "K") -> emit("ɐ")
                stress == 0 && name == "ER" -> emit("ɚ")
                else -> emit(FULL_VOWELS.getValue(name), stress)
            }
            i += 1
        }
        flap(out)
        for (k in 0 until out.size - 1) if (out[k][0] == "n" && out[k + 1][0] in setOf("k", "ɡ")) out[k][0] = "ŋ"
        val phones = out.map { Ph(it[0] as String, it[1] as Int) }
        return settleStress(phones)
    }

    /** t between a vowel (or r) and an unstressed vowel: water, city, party. */
    private fun flap(out: MutableList<Array<Any>>) {
        for (k in 1 until out.size - 1) {
            if (out[k][0] != "t") continue
            val before = out[k - 1][0] as String
            val after = out[k + 1][0] as String; val stress = out[k + 1][1] as Int
            if ((before !in Symbols.VOWEL_SET && before != "ɹ") || before in setOf("n̩", "əl") || after !in Symbols.VOWEL_SET || after == "n̩") continue
            val finalO = after == "oʊ" && (k + 2 == out.size || (k + 3 == out.size && out[k + 2][0] == "z"))
            if (stress != 0 && !finalO) continue
            if (after in setOf("ə", "ɚ") && k + 2 < out.size && out[k + 2][0] == "n") continue
            out[k][0] = "ɾ"
        }
    }

    /** One primary stress per word, and no secondary stress next to the primary. */
    fun settleStress(phones: Phones): Phones {
        val out = phones.map { arrayOf<Any>(it.phone, it.stress) }
        val vowels = out.indices.filter { out[it][0] in Symbols.VOWEL_SET }
        val primaries = vowels.filter { out[it][1] == 1 }
        for (k in primaries.dropLast(1)) out[k][1] = 2
        for ((pos, k) in vowels.withIndex()) {
            if (out[k][1] != 2) continue
            val neighbours = listOf(pos - 1, pos + 1).filter { it in vowels.indices }.map { vowels[it] }
            if (neighbours.any { out[it][1] == 1 }) out[k][1] = 0
        }
        return out.map { Ph(it[0] as String, it[1] as Int) }
    }
}
