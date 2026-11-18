package com.etheroal.os.voice

/**
 * The phone inventory of tiny-voca (port of dizione/symbols.py). The order is the model's
 * vocabulary and must never change: ids are positions in [SYMBOLS].
 */
object Symbols {
    const val PAD = "<pad>"
    const val BOS = "<bos>"
    const val EOS = "<eos>"
    const val SP = "<sp>"
    const val UNK = "<unk>"

    val SPECIALS = listOf(PAD, BOS, EOS, SP, UNK)
    val PUNCTUATION = listOf(",", ".", "?", "!", ";", ":", "\"", "'", "(", ")", "—", "…")
    val CONSONANTS = listOf(
        "p", "b", "t", "d", "k", "ɡ", "f", "v", "θ", "ð", "s", "z", "ʃ", "ʒ", "h",
        "m", "n", "ŋ", "l", "ɹ", "j", "w", "tʃ", "dʒ", "ɾ", "ʔ",
    )
    val VOWELS = listOf(
        "ə", "ɐ", "ɚ", "ᵻ", "ɪ", "i", "iː", "ɛ", "æ", "ʌ", "ɑː", "ɔ", "ɔː", "ʊ", "uː", "ɜː",
        "eɪ", "aɪ", "ɔɪ", "oʊ", "aʊ", "iə", "oː",
        "ɑːɹ", "ɔːɹ", "oːɹ", "ɪɹ", "ɛɹ", "ʊɹ", "aɪɚ", "aɪə", "əl", "n̩",
    )
    val PHONES = CONSONANTS + VOWELS
    val SYMBOLS = SPECIALS + PUNCTUATION + PHONES
    val ID: Map<String, Int> = SYMBOLS.withIndex().associate { (i, s) -> s to i }
    val PHONE_SET = PHONES.toHashSet()
    val VOWEL_SET = VOWELS.toHashSet()
    val PUNCT_SET = PUNCTUATION.toHashSet()
}

/** One phone with its lexical stress: 0 none, 1 primary, 2 secondary. */
data class Ph(val phone: String, val stress: Int = 0)

typealias Phones = List<Ph>

/** The token sequence the ONNX model takes. */
data class Phonemized(val symbols: List<String>, val ids: LongArray, val stress: LongArray) {
    override fun toString(): String = symbols.zip(stress.toList()).joinToString(" ") { (s, l) ->
        (if (l == 1L) "ˈ" else if (l == 2L) "ˌ" else "") + s
    }
}

/** "h ə l ˈoʊ" -> phones. Throws on a symbol outside the inventory. */
fun fromText(text: String): Phones = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { unit ->
    val stress = when (unit[0]) { 'ˈ' -> 1; 'ˌ' -> 2; else -> 0 }
    val phone = if (stress != 0) unit.substring(1) else unit
    require(phone in Symbols.PHONE_SET) { "'$phone' is not a phone of the inventory" }
    require(stress == 0 || phone in Symbols.VOWEL_SET) { "stress mark on the consonant '$phone'" }
    Ph(phone, stress)
}

/**
 * Builds the token sequence from pieces: a punctuation mark, or a run of spoken words.
 * Words are separated by <sp>; repeated punctuation is collapsed (port of symbols.tokenize).
 */
fun tokenize(pieces: List<Piece>): Phonemized {
    val symbols = mutableListOf(Symbols.BOS)
    val stress = mutableListOf(0L)
    for (piece in pieces) {
        when (piece) {
            is Piece.Mark -> if (symbols.last() != piece.mark) { symbols += piece.mark; stress += 0 }
            is Piece.Words -> for (word in piece.words) {
                if (symbols.last() in Symbols.PHONE_SET || symbols.last() == Symbols.UNK) { symbols += Symbols.SP; stress += 0 }
                for (p in word) {
                    if (p.phone in Symbols.PHONE_SET) { symbols += p.phone; stress += p.stress.toLong() }
                    else { symbols += Symbols.UNK; stress += 0 }
                }
            }
        }
    }
    symbols += Symbols.EOS
    stress += 0
    require(symbols.any { it in Symbols.PHONE_SET || it == Symbols.UNK }) { "the text has nothing to pronounce" }
    return Phonemized(symbols, LongArray(symbols.size) { Symbols.ID.getValue(symbols[it]).toLong() }, stress.toLongArray())
}

sealed interface Piece {
    data class Mark(val mark: String) : Piece
    data class Words(val words: List<Phones>) : Piece
}
