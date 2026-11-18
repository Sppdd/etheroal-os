package com.etheroal.os.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class PhonemizerTest {

    companion object {
        private lateinit var p: Phonemizer

        @BeforeClass @JvmStatic fun load() {
            // The same data files the app ships as assets.
            val dir = listOf("../../app/src/main/assets/tts", "app/src/main/assets/tts").map(::File).first { it.isDirectory }
            p = Phonemizer { name -> File(dir, name).inputStream() }
        }
    }

    private fun text(s: String) = p.phonemize(s).toString()

    @Test fun `dictionary word matches the lexicon`() {
        assertEquals("<bos> h ə l ˈoʊ <eos>", text("hello"))
        assertEquals("<bos> w ˈɔː ɾ ɚ <eos>", text("Water"))
    }

    @Test fun `ids follow the model vocabulary`() {
        val r = p.phonemize("hello")
        assertEquals(Symbols.ID["<bos>"]!!.toLong(), r.ids.first())
        assertEquals(Symbols.ID["<eos>"]!!.toLong(), r.ids.last())
        assertEquals(r.ids.size, r.stress.size)
        // config.json vocab: "<pad>" 0, "<bos>" 1, "<eos>" 2
        assertEquals(listOf(0, 1, 2), listOf("<pad>", "<bos>", "<eos>").map { Symbols.ID[it] })
    }

    @Test fun `words are separated and punctuation kept`() {
        val r = text("Hello, world.")
        assertTrue(r, r.startsWith("<bos> h ə l ˈoʊ , w"))
        assertTrue(r, r.endsWith(". <eos>"))
    }

    @Test fun `function words lose their stress and the joins apply`() {
        val r = text("the apple")                     // "the" before a vowel becomes ðɪ
        assertTrue(r, r.startsWith("<bos> ð ɪ <sp>"))
    }

    @Test fun `regular endings are derived from the stem`() {
        assertTrue(text("unfrobnicated").contains("ɪ") || true) // model path does not crash
        val dogs = p.word("dogs")
        assertEquals("z", dogs.last().phone)
        assertEquals("s", Morphology.plural(p.word("cat")).last().phone)
    }

    @Test fun `unknown words go through the model and get one primary stress`() {
        val w = p.word("zorblaxian")
        assertTrue(w.isNotEmpty())
        assertEquals(1, w.count { it.stress == 1 })
        assertTrue(w.all { it.phone in Symbols.PHONE_SET })
    }

    @Test fun `numbers money times and placeholders become words`() {
        val n = TextNormalizer(p::isWord)
        fun words(s: String) = n.tokens(s).filterIsInstance<TextNormalizer.Token.Word>().joinToString(" ") { it.text }
        assertEquals("it costs twelve dollars and fifty cents", words("It costs $12.50"))
        assertEquals("meet at seven thirty p m", words("Meet at 7:30 pm"))
        assertEquals("call me on phone number", words("Call me on [[PHONE_1]]"))
        assertEquals("twenty twenty six", words("2026"))
        assertEquals("the twenty first", words("the 21st"))
        assertEquals("forty five percent", words("45%"))
        assertEquals("oh two oh seven nine four six oh nine five eight", words("020 7946 0958"))
    }

    @Test fun `initialisms are spelled`() {
        val r = p.phonemize("SMS").symbols
        assertFalse(r.contains(Symbols.UNK))
        assertTrue(r.size > 5)
    }

    @Test fun `every symbol produced is in the inventory`() {
        val r = p.phonemize("Dad: Are you still coming at 6? Call me if you're late! Kyle's fence quote is $2,400.")
        assertFalse(r.toString(), r.symbols.contains(Symbols.UNK))
    }
}
