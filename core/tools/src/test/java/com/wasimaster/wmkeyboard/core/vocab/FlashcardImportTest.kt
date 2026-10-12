package com.wasimaster.wmkeyboard.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardImportTest {

    @Test
    fun `quizlet tab export is term then definition`() {
        val cards = FlashcardImport.cardsFromText("abhor\tto hate\nbenign\tgentle; kind\n")
        assertEquals(listOf("abhor" to "to hate", "benign" to "gentle; kind"), cards.map { it.term to it.definition })
    }

    @Test
    fun `quizlet comma export keeps an unquoted comma inside the definition`() {
        val cards = FlashcardImport.cardsFromText("comer,to eat, to dine\nvivir,to live\n", "set.txt")
        assertEquals("to eat, to dine", cards[0].definition)
        assertEquals("to live", cards[1].definition)
    }

    @Test
    fun `quoted csv cells hold commas, quotes and line breaks`() {
        val text = "Term,Definition,Example\n\"ser, estar\",\"to be\nboth\",\"He said \"\"hi\"\"\"\n"
        val cards = FlashcardImport.cardsFromText(text, "deck.csv")
        assertEquals(1, cards.size)
        assertEquals("ser, estar", cards[0].term)
        assertEquals("to be; both", cards[0].definition)
        assertEquals("He said \"hi\"", cards[0].example)
    }

    @Test
    fun `a header row picks columns by name`() {
        val text = "Tags;Back;Front\nverbs;to speak;hablar\n"
        val cards = FlashcardImport.cardsFromText(text)
        assertEquals("hablar", cards[0].term)
        assertEquals("to speak", cards[0].definition)
    }

    @Test
    fun `dash separated lines`() {
        val cards = FlashcardImport.cardsFromText("perro - dog\ngato - cat - feline\n")
        assertEquals(listOf("perro" to "dog", "gato" to "cat - feline"), cards.map { it.term to it.definition })
    }

    @Test
    fun `anki plain text export honours its header lines`() {
        val text = "#separator:tab\n#html:true\n#guid column:1\n#notetype column:2\n#deck column:3\n" +
            "abc\tBasic\tSpanish\thablar\tto <b>speak</b><br>aloud\n"
        val cards = FlashcardImport.cardsFromText(text)
        assertEquals(1, cards.size)
        assertEquals("hablar", cards[0].term)
        assertEquals("to speak; aloud", cards[0].definition)
    }

    @Test
    fun `binary and json are not card lists`() {
        assertTrue(FlashcardImport.cardsFromText("{\"format\":\"x\",\"words\":[1,2]}").isEmpty())
        assertTrue(FlashcardImport.cardsFromText("PNG\u0000\u0001\t\u0002").isEmpty())
        assertTrue(FlashcardImport.cardsFromText("just one line with nothing to split").isEmpty())
    }

    @Test
    fun `html fields become plain text`() {
        assertEquals(
            "to live; two & more",
            FlashcardImport.plainText("to live [sound:vivir.mp3]<div>two &amp; more</div>"),
        )
        assertEquals("日本語", FlashcardImport.plainText(" 日本語[にほんご]"))
        assertEquals("a < b", FlashcardImport.plainText("a < b"))
        assertEquals("note[1]", FlashcardImport.plainText("note[1]"))
        assertEquals("é —", FlashcardImport.plainText("&#233; &#x2014;"))
    }

    @Test
    fun `anki notes map fields by name and skip cloze`() {
        val notes = listOf(
            FlashcardImport.AnkiNote(1, listOf("hablar", "to <b>speak</b>")),
            FlashcardImport.AnkiNote(2, listOf("{{c1::perro::animal}} is dog", "")),
            FlashcardImport.AnkiNote(3, listOf("I eat apples.", "comer", "to eat", "こめる")),
            FlashcardImport.AnkiNote(99, listOf("vivir", "to live")),
        )
        val names = mapOf(
            1L to listOf("Front", "Back"),
            2L to listOf("Text", "Back Extra"),
            3L to listOf("Sentence", "Word", "Meaning", "Reading"),
        )
        val cards = FlashcardImport.cardsFromAnki(notes, names)
        assertEquals(listOf("hablar", "comer", "vivir"), cards.map { it.term })
        assertEquals("to speak", cards[0].definition)
        assertEquals("to eat", cards[1].definition)
        assertEquals("I eat apples.", cards[1].example)
        assertEquals("こめる", cards[1].reading)
        assertEquals("to live", cards[2].definition)
    }

    @Test
    fun `repeated fronts merge into senses and long fronts are skipped`() {
        val cards = listOf(
            FlashcardImport.Card("Bank", "money place"),
            FlashcardImport.Card("bank", "river side"),
            FlashcardImport.Card("bank", "money place"),
            FlashcardImport.Card("x".repeat(80), "too long"),
            FlashcardImport.Card("alone"),
        )
        val pack = FlashcardImport.toPack(cards, "Mine")
        assertEquals("Mine", pack.name)
        assertEquals(listOf("bank", "alone"), pack.words.map { it.word })
        assertEquals(listOf("money place", "river side"), pack.words[0].senses.map { it.definition })
        assertTrue(pack.words[1].senses.isEmpty())
    }

    @Test
    fun `the pack round-trips through the file format`() {
        val pack = FlashcardImport.toPack(listOf(FlashcardImport.Card("abhor", "to hate", "I abhor it.", "ab-HOR")), "Deck")
        val decoded = VocabPackFile.decode(VocabPackFile.encode(pack, 1, "1"))!!
        assertEquals("abhor", decoded.words[0].word)
        assertEquals("to hate", decoded.words[0].definition)
        assertEquals("I abhor it.", decoded.words[0].senses[0].example)
        assertEquals("ab-HOR", decoded.words[0].respelling)
    }

    @Test
    fun `file names lose their extension, generic collection names are dropped`() {
        assertEquals("Spanish Verbs", FlashcardImport.nameFromFile("Spanish Verbs.apkg"))
        assertEquals("list", FlashcardImport.nameFromFile("list.csv"))
        assertNull(FlashcardImport.nameFromFile("collection-2026-10-12@01-45-00.colpkg"))
    }
}
