package com.wasimaster.wmkeyboard.core.transliteration

import com.wasimaster.wmkeyboard.core.transliteration.BijoyAnsi.Version
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BijoyAnsi] against okkhor52.com's own converters. The expected strings are
 * what their scripts return for each sentence, one per version; regenerate
 * them from the scripts rather than editing them by hand.
 */
class BijoyAnsiTest {

    private class Case(val unicode: String, val v1: String, val v2: String, val v3: String)

    private val cases = listOf(
        Case("\u0986\u09AE\u09BE\u09B0 \u09B8\u09CB\u09A8\u09BE\u09B0 \u09AC\u09BE\u0982\u09B2\u09BE", "Avgvi \u2020mvbvi evsjv", "Avgvi \u2020mvbvi evsjv", "xy\u203Ay\u00EE\u00FB \u00F6\u00A2y\u02DCy\u00EE\u00FB \u00EEy\u201A\u0153y"),
        Case("\u0986\u09AE\u09BF \u09A4\u09CB\u09AE\u09BE\u09AF\u09BC \u09AD\u09BE\u09B2\u09CB\u09AC\u09BE\u09B8\u09BF\u0964", "Avwg \u2020Zvgvq fv\u2021jvevwm|", "Avwg \u2020Zvgvq fv\u2021jvevwm|", "xy!\u203A \u00F6\u201C\u00FEy\u203Ay\u00EB\u00FB \u00A6\u00FEy\u00F6\u00EC\u0153y\u00EEy!\u00A2\u00D0"),
        Case("\u099A\u09BF\u09B0\u09A6\u09BF\u09A8 \u09A4\u09CB\u09AE\u09BE\u09B0 \u0986\u0995\u09BE\u09B6, \u09A4\u09CB\u09AE\u09BE\u09B0 \u09AC\u09BE\u09A4\u09BE\u09B8", "wPiw`b \u2020Zvgvi AvKvk, \u2020Zvgvi evZvm", "wPiw`b \u2020Zvgvi AvKvk, \u2020Zvgvi evZvm", "!\u2030\u00FE\u00EE\u00FB!\u201D\u02DC \u00F6\u201C\u00FEy\u203Ay\u00EE\u00FB xy\u201E\u00FEy\u0178\u2013 \u00F6\u201C\u00FEy\u203Ay\u00EE\u00FB \u00EEy\u201C\u00FEy\u00A2"),
        Case("\u0995\u09B0\u09CD\u09AE \u09A7\u09B0\u09CD\u09AE \u09AE\u09B0\u09CD\u09AE\u09B0 \u09B8\u09C2\u09B0\u09CD\u09AF", "Kg\u00A9 ag\u00A9 gg\u00A9i m~h\u00A9", "Kg\u00A9 ag\u00A9 gg\u00A9i m~h\u00A9", "\u201E\u00FE\u203A\u00C5 \u2022\u203A\u00C5 \u203A\u203A\u00C5\u00EE\u00FB \u00A2)\u00EB\u00C5"),
        Case("\u09B0\u09BE\u09B7\u09CD\u099F\u09CD\u09B0 \u0995\u09C3\u09B7\u09CD\u09A3 \u0995\u09CD\u09B7\u09AE\u09BE \u09B2\u0995\u09CD\u09B7\u09CD\u09AE\u09C0", "iv\u00F3\u00AA K\u2026\u00F2 \u00B6gv j\u00B2x", "iv\u00F3\u00AA K\u2026\u00F2 \u00FFgv j\u00B2x", "\u00EE\u00FBy\u00DCT\u00C9 \u201E,\u00FE\u00A1\u0152 \u00C7\u00FE\u203Ay \u0153\"#"),
        Case("\u09AC\u09BF\u09A6\u09CD\u09AF\u09BE\u09B2\u09AF\u09BC \u09AA\u09CD\u09B0\u09A7\u09BE\u09A8 \u09B6\u09BF\u0995\u09CD\u09B7\u0995", "we`\u00A8vjq c\u00D6avb wk\u00B6K", "we`\u00A8vjq c\u00D6avb wk\u00FFK", "!\u00EE\u201D\u00C4y\u0153\u00EB\u00FB \u00B2\u00CC\u2022y\u02DC !\u0178\u00C7\u00FE\u201E\u00FE"),
        Case("\u0995\u09CC\u09B6\u09B2 \u09AE\u09CC\u09AE\u09BE\u099B\u09BF \u09AA\u09CC\u0981\u099B\u09C7", "\u2020K\u0160kj \u2020g\u0160gvwQ \u2020c\u0160u\u2021Q", "\u2020K\u0160kj \u2020g\u0160gvwQ \u2020c\u0160u\u2021Q", "\u00F6\u201E\u00FE\u00EF\u0178\u0153 \u00F6\u203A\u00EF\u203Ay!\u0160\u00E9 \u00F6\u00FE\u2122\u00EF\u00A4\u00F6\u00EC\u0160\u00E9"),
        Case("\u09B9\u09A0\u09BE\u09CE \u09AC\u09C3\u09B7\u09CD\u099F\u09BF \u098F\u09B2\u09CB", "nVvr e\u201Ew\u00F3 G\u2021jv", "nVvr e\u201Ew\u00F3 G\u2021jv", "\u00A3\u00E0\u00FEy\u00EA \u00EE,!\u00DCT ~\u00F6\u00EC\u0153y"),
        Case("\u09A1\u09BC\u09A2\u09BC\u09AF\u09BC \u09AA\u09A1\u09BC\u09BE \u09AA\u09BE\u09B9\u09BE\u09A1\u09BC \u0986\u09B7\u09BE\u09A2\u09BC \u09A8\u09AF\u09BC\u09A8", "opq cov cvnvo Avlvp bqb", "opq cov cvnvo Avlvp bqb", "v\u00FE\u00FC\u2018\u00FE\u00FC\u00EB\u00FB \u00FE\u2122v\u00FE\u00FCy \u00FE\u2122y\u00A3yv\u00FE\u00FC xy\u00A1\u00ECy\u2018\u00FE\u00FC \u02DC\u00EB\u00FB\u02DC"),
        Case("\u098F\u0995\u09C1\u09B6\u09C7 \u09AB\u09C7\u09AC\u09CD\u09B0\u09C1\u09AF\u09BC\u09BE\u09B0\u09BF \u09E7\u09EF\u09EB\u09E8", "GKz\u2021k \u2020de\u00AA\u201Cqvwi 1952", "GKz\u2021k \u2020de\u00AA\u00E6qvwi 1952", "~\u201E%\u00FE\u00F6\u00EC\u0178 \u00F6\u0161\u00FE\u00EE\u00CA&\u00EB\u00FBy!\u00EE\u00FB 1952"),
        Case("\u201C\u09AC\u09BE\u0982\u09B2\u09BE\u201D \u0986\u09AE\u09BE\u09B0 \u2018\u09AE\u09BE\u09A4\u09C3\u09AD\u09BE\u09B7\u09BE\u2019", "\u00D2evsjv\u00D3 Avgvi \u00D4gvZ\u2026fvlv\u00D5", "\u00D2evsjv\u00D3 Avgvi \u00D4gvZ\u2026fvlv\u00D5", "\u201C\u00EEy\u201A\u0153y\u201D xy\u203Ay\u00EE\u00FB \u00F2\u203Ay\u201C,\u00FE\u00A6\u00FEy\u00A1\u00ECy\u00F3"),
        Case("\u0989\u099A\u09CD\u099B\u09CD\u09AC\u09BE\u09B8 \u09B8\u09CD\u09AC\u09BE\u09B8\u09CD\u09A5\u09CD\u09AF \u09B8\u09BE\u09A8\u09CD\u09A4\u09CD\u09AC\u09A8\u09BE", "D\u201DQ\u00A1vm \u00AF^v\u00AF\u2019\u00A8 mv\u0161\u2014\u00A1bv", "D\u201DQ\u00A1vm \u00AF^v\u00AF\u2019\u00A8 mv\u0161\u00CD\u00A1bv", "v\u00FEzF\u0160\u00B4\u00E9y\u00A2 \u00DF\u00FE\u00BAy\u00DF\u00FEi\u00C4 \u00A2ygs\u00FE\u02DCy"),
        Case("\u099C\u09CD\u099E\u09BE\u09A8 \u09AC\u09BF\u099C\u09CD\u099E\u09BE\u09A8 \u09B8\u0982\u099C\u09CD\u099E\u09BE", "\u00C1vb we\u00C1vb ms\u00C1v", "\u00C1vb we\u00C1vb ms\u00C1v", "K\u00FE\u00E9y\u02DC !\u00EEK\u00FE\u00E9y\u02DC \u00A2\u201AK\u00FE\u00E9y"),
        Case("\u098B\u09A4\u09C1 \u098B\u09B7\u09BF \u0997\u09C3\u09B9 \u09B9\u09C3\u09A6\u09AF\u09BC \u09A4\u09C3\u09A3", "FZz Fwl M\u201En \u00FC`q Z\u2026Y", "FZz Fwl M\u201En \u00FC`q Z\u2026Y", "}\u201C%\u00FE }!\u00A1\u00EC \u2020,\u00A3 \u00A3*\u201D\u00EB\u00FB \u201C,\u00FE\u2019"),
        Case("\u09B6\u09C1\u09AD \u0997\u09C1\u09B0\u09C1 \u09B0\u09C2\u09AA \u09B0\u09C1\u09AA\u09BE \u09B9\u09C1\u0995\u09C1\u09AE", "\u00EFf \u00B8i\u201C i\u0192c i\u201Ccv \u00FBKzg", "\u00EFf \u00B8i\u00E6 i\u0192c i\u00E6cv \u00FBKzg", "\u00D6\u00A6\u00FE =\u00EE\u00FB& \u00EE\u00FB*\u00FE\u2122 \u00EE\u00FB&\u00FE\u2122y \u00FD\u201E%\u00FE\u203A"),
        Case("\u099A\u09BE\u0981\u09A6 \u0995\u09BE\u0981\u09A6\u09C7 \u09AC\u09BE\u0981\u09B6\u09BF", "Pvu` Kvu\u2021` evuwk", "Pvu` Kvu\u2021` evuwk", "\u2030\u00FEy\u00A4\u201D \u201E\u00FEy\u00A4\u00F6\u00EC\u201D \u00EEy\u00A4!\u0178"),
        Case("\u09AF\u09C1\u0995\u09CD\u09A4\u09B0\u09BE\u09B7\u09CD\u099F\u09CD\u09B0 \u09B8\u09AE\u09CD\u09AA\u09CD\u09B0\u09A6\u09BE\u09AF\u09BC \u0989\u099C\u09CD\u099C\u09CD\u09AC\u09B2", "hy\u00B3iv\u00F3\u00AA m\u00A4\u00FA\u00D6`vq D\u00BE\u00A1j", "hy\u00B3iv\u00F3\u00AA m\u00A4\u00FA\u00D6`vq D\u00BE\u00A1j", "\u00EB%_\u00AB\u00EE\u00FBy\u00DCT\u00C9 \u00A2\u00C1\u00B1\u201Dy\u00EB\u00FB v\u00FEzI\u00B5\u0153"),
        Case("\u0995\u09BE\u09B0\u09CD\u09AF\u0995\u09B0 \u09AA\u09B0\u09CD\u09AF\u099F\u09A8 \u0986\u09B0\u09CD\u09AF", "Kvh\u00A9Ki ch\u00A9Ub Avh\u00A9", "Kvh\u00A9Ki ch\u00A9Ub Avh\u00A9", "\u201E\u00FEy\u00EB\u00C5\u201E\u00FE\u00EE\u00FB \u00FE\u2122\u00EB\u00C5\u00DD\u00FE\u02DC xy\u00EB\u00C5"),
        Case("\u0995\u09CD\u09AF\u09BE\u09AE\u09CD\u09AA \u09B0\u200C\u09CD\u09AF\u09BE\u09AC \u099F\u09CD\u09B0\u09C7\u09A8 \u0995\u09CD\u09B2\u09BE\u09B8", "K\u00A8v\u00A4\u00FA i\u00A8ve \u2020U\u00AAb K\u00ACvm", "K\u00A8v\u00A4\u00FA i\u00A8ve \u2020U\u00AAb K\u00ACvm", "\u201E\u00FE\u00C4y\u00C1\u2122 \u00EE\u00FB\u00C4y\u00EE \u00F6\u00DD\u00FE\u00C6\u02DC \u201E\u00CF\u00FEy\u00A2"),
        Case("\u09A6\u09C1\u0983\u0996 \u09A8\u09BF\u0983\u09B6\u09CD\u09AC\u09BE\u09B8 \u09F3\u09E7\u09E6\u09E6", "`ytL wbtk\u00A6vm \$100", "`ytL wbtk\u00A6vm \$100", "\u201D%\u0192\u2026 !\u02DC\u0192\u00D9\u00BBy\u00A2 \u09F3100"),
        Case("\u0995\u09C7\u09A8 \u0995\u09C8 \u0995\u09CB\u09A5\u09BE\u09AF\u09BC \u0995\u09CC\u09A4\u09C1\u0995", "\u2020Kb \u02C6K \u2020Kv_vq \u2020K\u0160ZzK", "\u2020Kb \u02C6K \u2020Kv_vq \u2020K\u0160ZzK", "\u00F6\u201E\u00FE\u02DC \u00F7\u201E\u00FE \u00F6\u201E\u00FEy\u00EDy\u00EB\u00FB \u00F6\u201E\u00FE\u00EF\u201C%\u00FE\u201E\u00FE"),
        Case("\u09B8\u09CD\u0995\u09C1\u09B2 \u09B8\u09CD\u099F\u09C7\u09B6\u09A8 \u09B8\u09CD\u09A4\u09CD\u09B0\u09C0 \u09A8\u09CD\u09AF\u09BE\u09AF\u09BC", "\u00AF\u2039zj \u2020\u00F7kb \u00AF\u00BFx b\u00A8vq", "\u00AF\u2039zj \u2020\u00F7kb \u00AF\u00BFx b\u00A8vq", "\u00DF\u00FE%\u00F1\u0153 \u00F6\u00DE\u00DD\u00FE\u0178\u02DC \u00DF\u00FEf# \u02DC\u00C4y\u00EB\u00FB"),
        Case("\u0999\u09CD\u0997 \u09AC\u0999\u09CD\u0997 \u09B8\u0999\u09CD\u0998 \u0985\u0999\u09CD\u0995 \u09B6\u0999\u09CD\u0996", "\u00BD e\u00BD m\u2022N A\u00BC k\u2022L", "\u00BD e\u00BD m\u2022N A\u00BC k\u2022L", "D \u00EED \u00A2A\u2021 xB\u00FE \u0178C"),
        Case("\u09A6\u09CD\u09AC\u09BF\u09A4\u09C0\u09AF\u09BC \u09A4\u09CD\u09B0\u09BF\u09B6 \u09AE\u09C1\u0995\u09CD\u09A4\u09BF", "w\u00D8Zxq w\u00CEk gyw\u00B3", "w\u00D8Zxq w\u00CEk gyw\u00B3", "!m\u201C\u00FE#\u00EB\u00FB !e\u0178 \u203A%!_\u00AB"),
        Case("\u09A8\u09A4\u09C1\u09A8 \u09AC\u099B\u09B0 \u09B6\u09C1\u09AD \u09B9\u09CB\u0995\u0965", "bZzb eQi \u00EFf \u2020nvK\\", "bZzb eQi \u00EFf \u2020nvK\\", "\u02DC\u201C%\u00FE\u02DC \u00EE\u0160\u00E9\u00EE\u00FB \u00D6\u00A6\u00FE \u00F6\u00A3y\u201E\u00FE\u0965"),
        Case("\u09AB\u09C1\u09B2 \u09AB\u09B2 \u0997\u09C1\u09B2\u09CD\u09AE \u09B6\u09C1\u09B2\u09CD\u0995", "dzj dj \u00B8j\u00A5 \u00EF\u00E9", "dzj dj \u00B8j\u00A5 \u00EF\u00E9", "\u0161%\u00FE\u0153 \u0161\u00FE\u0153 =\u00CD\u00C3 \u00D6\u00CD\u00F1"),
    )

    @Test
    fun matchesTheConvertersForEveryVersion() {
        for (c in cases) {
            assertEquals(c.unicode, c.v1, BijoyAnsi.convert(c.unicode, Version.V1))
            assertEquals(c.unicode, c.v2, BijoyAnsi.convert(c.unicode, Version.V2))
            assertEquals(c.unicode, c.v3, BijoyAnsi.convert(c.unicode, Version.V3))
        }
    }

    @Test
    fun theExampleSentence() {
        assertEquals("Av", BijoyAnsi.convert("\u0986"))
        assertEquals("Avgvi \u2020mvbvi evsjv", BijoyAnsi.convert("\u0986\u09AE\u09BE\u09B0 \u09B8\u09CB\u09A8\u09BE\u09B0 \u09AC\u09BE\u0982\u09B2\u09BE"))
    }

    /**
     * The keyboard converts a word at a time, as each one is committed, with
     * what is already in the field in front of it. That has to come out the
     * same as converting the sentence whole.
     */
    @Test
    fun wordByWordMatchesWholeSentence() {
        for (c in cases) {
            for ((version, whole) in listOf(Version.V1 to c.v1, Version.V2 to c.v2, Version.V3 to c.v3)) {
                val field = StringBuilder()
                for ((index, word) in c.unicode.split(' ').withIndex()) {
                    if (index > 0) field.append(BijoyAnsi.convert(" ", version, field))
                    field.append(BijoyAnsi.convert(word, version, field))
                }
                assertEquals(c.unicode, whole, field.toString())
            }
        }
    }

    @Test
    fun theWordAfterASpaceTakesTheWordStartGlyph() {
        // ে after a space is the word-start glyph, after a letter the other one.
        assertEquals("\u2020K", BijoyAnsi.convert("\u0995\u09C7", Version.V2, before = " "))
        assertEquals("\u2021K", BijoyAnsi.convert("\u0995\u09C7", Version.V2, before = "v"))
        assertEquals("\u2020K", BijoyAnsi.convert("\u0995\u09C7", Version.V2, before = ""))
    }

    @Test
    fun textWithNothingBengaliIsLeftAlone() {
        assertEquals("hello, world 123", BijoyAnsi.convert("hello, world 123"))
        assertEquals(false, BijoyAnsi.needsConversion("hello"))
        assertEquals(true, BijoyAnsi.needsConversion("\u0964"))
    }

    @Test
    fun unknownVersionNumberFallsBackToTheDefault() {
        assertEquals(Version.V2, Version.of(9))
        assertEquals(Version.V3, Version.of(3))
    }
}
