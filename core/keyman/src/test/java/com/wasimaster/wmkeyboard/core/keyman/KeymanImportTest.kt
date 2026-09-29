package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.ForeignSource
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.canBeEnabled
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.keyman.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user-facing import path for a Keyman touch layout someone picked. */
class KeymanImportTest {

    private fun fixture(id: String): String =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream("touch/$id.keyman-touch-layout"),
        ) { "missing fixture touch/$id.keyman-touch-layout" }.use { it.readBytes().decodeToString() }

    @Test
    fun `a real touch layout is recognised`() {
        for (id in FIXTURES) {
            assertTrue("$id was not recognised", KeymanImport.looksLikeTouchLayout(fixture(id)))
        }
    }

    /**
     * The sniff has to be narrow, because the import screen tries it before the
     * FlorisBoard reader. A false positive there means a FlorisBoard layout is
     * refused rather than converted.
     */
    @Test
    fun `other keyboards' files are not mistaken for touch layouts`() {
        val notKeyman = listOf(
            """[[{"$":"text_key","code":113,"label":"q"}]]""",
            """{"arrangement":[["a","b"]]}""",
            """{"rows":[["a"]]}""",
            """{"shopping":["milk"]}""",
            "q w e\na s d\n",
            "{not json",
            "",
        )
        for (text in notKeyman) {
            assertFalse("mistook ${text.take(24)} for a touch layout", KeymanImport.looksLikeTouchLayout(text))
            assertNull("converted ${text.take(24)} anyway", KeymanImport.convert(text, "x.json"))
        }
    }

    @Test
    fun `an imported layout is usable`() {
        for (id in FIXTURES) {
            val converted = checkNotNull(KeymanImport.convert(fixture(id), "$id.json")) {
                "$id did not convert"
            }
            assertEquals(ForeignSource.KEYMAN_TOUCH_LAYOUT, converted.source)
            assertTrue("$id has no letters layer", LayoutLayer.LETTERS.key in converted.layout.layers)

            val spec = converted.withLanguage("en")
            assertTrue("$id cannot be enabled", spec.canBeEnabled())
            assertTrue("$id still needs repair", spec.repair().repairNotes.isEmpty())
        }
    }

    /**
     * An imported grid must not claim a binding. A binding means "the rules are
     * on this device", and for a file out of someone's downloads they are not;
     * claiming otherwise would have the engine look for rules that never arrive.
     */
    @Test
    fun `an imported layout carries no rule binding`() {
        for (id in FIXTURES) {
            val converted = KeymanImport.convert(fixture(id), "$id.json")!!
            assertNull("$id claimed a rule binding", converted.layout.keyman)
        }
    }

    /**
     * The language is left blank on the spec so `withLanguage` decides it. A
     * layout stored with a blank id is silently migrated to English on the next
     * read, so the guess exists to give the picker a starting point.
     */
    @Test
    fun `the language is guessed but not baked in`() {
        val converted = KeymanImport.convert(fixture("khmer_angkor"), "khmer_angkor.json")!!
        assertEquals("", converted.layout.langId)
        assertTrue("no language guessed", converted.guessedLangId.isNotBlank())
        assertEquals("km", converted.guessedLangId)
        assertEquals("bn", converted.withLanguage("bn").langId)
    }

    /** Every import says up front that the rules are absent. */
    @Test
    fun `the notes lead with the missing rules`() {
        for (id in FIXTURES) {
            val notes = KeymanImport.convert(fixture(id), "$id.json")!!.notes
            assertTrue("$id produced no notes", notes.isNotEmpty())
            assertEquals(
                "$id did not lead with the rules note",
                R.string.core_keyman_rules_missing,
                notes.first().stringRes,
            )
        }
    }

    /** `sil_euro_latin` cycles its `*123*` key through the symbol pages, which does not convert. */
    @Test
    fun `dropped gestures reach the notes`() {
        val notes = KeymanImport.convert(fixture("sil_euro_latin"), "s.json")!!.notes
        assertTrue(
            "a keyboard whose multitap did not convert reported nothing",
            notes.any { it.pluralsRes == R.plurals.core_keyman_multitap_unconverted },
        )
    }

    /** `geezword_tigrinya`'s cycles are on keys that type, and all of them convert. */
    @Test
    fun `kept multitaps are not reported`() {
        val notes = KeymanImport.convert(fixture("geezword_tigrinya"), "t.json")!!.notes
        assertTrue(
            "a keyboard whose multitaps all converted still reported some dropped",
            notes.none { it.pluralsRes == R.plurals.core_keyman_multitap_unconverted },
        )
    }

    private companion object {
        val FIXTURES = listOf(
            "basic_kbdus",
            "khmer_angkor",
            "lao_2008_basic",
            "sil_euro_latin",
            "geezword_tigrinya",
            "urdu_dvorak",
        )
    }
}
