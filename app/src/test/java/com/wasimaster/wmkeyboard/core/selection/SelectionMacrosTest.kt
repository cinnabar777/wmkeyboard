package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection macro engine.
 *
 * Two halves, and the first is the one that matters: what a selection is read
 * as. Reading a filename as a link or an invoice total as a phone number puts
 * a chip on the keyboard that dials somebody, so the "ordinary text raises
 * nothing" cases below are the point of this file rather than filler.
 */
class SelectionMacrosTest {

    private val bd = listOf("+880 1XXX-XXXXXX")

    // ---- detection ----

    @Test
    fun `plain phone number is a phone number`() {
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("01712345678"))
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("+8801712345678"))
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("(555) 123-4567"))
    }

    @Test
    fun `address is an address`() {
        assertEquals(SelectionKind.EMAIL, SelectionMacros.detect("example@gmail.com"))
        assertEquals(SelectionKind.EMAIL, SelectionMacros.detect("first.last+tag@sub.example.co.uk"))
    }

    @Test
    fun `link is a link, with or without its scheme`() {
        assertEquals(SelectionKind.URL, SelectionMacros.detect("https://example.com"))
        assertEquals(SelectionKind.URL, SelectionMacros.detect("www.example.com/page?a=1"))
        assertEquals(SelectionKind.URL, SelectionMacros.detect("example.com/page"))
    }

    @Test
    fun `ordinary text raises nothing`() {
        val text = listOf(
            "Hello there",
            "the meeting is at 3",
            // Dates and money have the digits but not the shape.
            "12/04/2025",
            "1,234.56",
            // A filename ending that is also a top-level domain somewhere.
            "report.txt",
            "notes.zip",
            // A sentence that merely contains a number is the sentence.
            "call me on 01712345678 tomorrow",
            // An address inside prose is prose.
            "write to example@gmail.com about it",
            "",
            "   ",
        )
        for (selection in text) {
            assertEquals(selection, SelectionKind.TEXT, SelectionMacros.detect(selection))
        }
    }

    @Test
    fun `several lines are never one entity`() {
        assertEquals(SelectionKind.TEXT, SelectionMacros.detect("01712345678\n01812345678"))
    }

    @Test
    fun `a mask narrows what counts as a number`() {
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("01712345678", bd))
        // Right length, wrong country: eleven digits that no Bangladeshi
        // number starts with.
        assertEquals(SelectionKind.TEXT, SelectionMacros.detect("+15551234567", bd))
        // The same run with no mask set passes on shape alone.
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("+15551234567"))
    }

    // ---- the row ----

    @Test
    fun `each kind offers its own actions`() {
        val all = SelectionMacros.configurable.toSet()
        val phone = SelectionMacros.offer(SelectionKind.PHONE, all)
        assertTrue(SelectionMacro.CALL in phone)
        assertTrue(SelectionMacro.WHATSAPP in phone)
        assertTrue(SelectionMacro.ADD_CONTACT in phone)
        assertTrue(SelectionMacro.QR !in phone)
        assertTrue(SelectionMacro.FANCY !in phone)

        // Select all leads every row, and each kind's own lead comes next.
        val url = SelectionMacros.offer(SelectionKind.URL, all)
        assertEquals(listOf(SelectionMacro.SELECT_ALL, SelectionMacro.OPEN), url.take(2))
        assertTrue(SelectionMacro.QR in url)
        assertTrue(SelectionMacro.CALL !in url)
        assertTrue(SelectionMacro.ADD_CONTACT !in url)

        val email = SelectionMacros.offer(SelectionKind.EMAIL, all)
        assertEquals(listOf(SelectionMacro.SELECT_ALL, SelectionMacro.EMAIL), email.take(2))
        assertTrue(SelectionMacro.ADD_CONTACT in email)

        val text = SelectionMacros.offer(SelectionKind.TEXT, all)
        assertEquals(
            listOf(SelectionMacro.SELECT_ALL, SelectionMacro.COPY, SelectionMacro.CUT),
            text.take(3),
        )
        assertTrue(SelectionMacro.CALL !in text)
    }

    @Test
    fun `undo is first whenever there is one, and absent otherwise`() {
        val all = SelectionMacros.configurable.toSet()
        assertTrue(SelectionMacro.UNDO !in SelectionMacros.offer(SelectionKind.TEXT, all))
        val order = listOf(SelectionMacro.COPY, SelectionMacro.UNDO, SelectionMacro.SELECT_ALL)
        val row = SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(undoAvailable = true), order)
        assertEquals(SelectionMacro.UNDO, row.first())
        assertEquals(SelectionMacro.COPY, row[1])
    }

    @Test
    fun `the user's order is followed, the rest appended, stale and ladder members ignored`() {
        val all = SelectionMacros.configurable.toSet()
        val order = listOf(SelectionMacro.SHARE, SelectionMacro.CASE_CAMEL, SelectionMacro.COPY)
        val row = SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(), order)
        assertEquals(listOf(SelectionMacro.SHARE, SelectionMacro.COPY, SelectionMacro.SELECT_ALL), row.take(3))
        assertTrue(SelectionMacro.CASE_CAMEL !in row)
        assertTrue(SelectionMacro.CUT in row)
    }

    @Test
    fun `gates take their chips away`() {
        val all = SelectionMacros.configurable.toSet()
        val plain = SelectionMacros.offer(SelectionKind.TEXT, all)
        for (gated in listOf(
            SelectionMacro.PASTE, SelectionMacro.LINES_SORT, SelectionMacro.GRAMMAR_FIX, SelectionMacro.AI,
            SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH, SelectionMacro.DIGITS_LATIN, SelectionMacro.COLOUR,
            SelectionMacro.MAP, SelectionMacro.CALENDAR, SelectionMacro.TIME_ZONES, SelectionMacro.CHAT_BOLD,
            SelectionMacro.JSON_FORMAT, SelectionMacro.BASE64_DECODE, SelectionMacro.URL_DECODE,
        )) {
            assertTrue(gated.name, gated !in plain)
        }
        val open = MacroGates(
            clipboardHasText = true, grammarAvailable = true, aiAvailable = true, bengaliLoaded = true,
            chatSyntax = ChatSyntax.forPackage("com.whatsapp"),
            content = ContentFlags(multiLine = true, hasLatin = true, hasForeignDigits = true, base64 = true, urlEncoded = true),
        )
        val row = SelectionMacros.offer(SelectionKind.TEXT, all, open)
        for (on in listOf(
            SelectionMacro.PASTE, SelectionMacro.LINES_SORT, SelectionMacro.LINES_BULLET, SelectionMacro.GRAMMAR_FIX,
            SelectionMacro.AI, SelectionMacro.TO_BANGLA, SelectionMacro.DIGITS_LATIN, SelectionMacro.CHAT_BOLD,
            SelectionMacro.CHAT_MONO, SelectionMacro.BASE64_DECODE, SelectionMacro.URL_DECODE,
        )) {
            assertTrue(on.name, on in row)
        }
        // A multi-line selection has no "next" and nothing Bengali to romanise.
        assertTrue(SelectionMacro.FIND !in row)
        assertTrue(SelectionMacro.TO_BANGLISH !in row)
        // Threema has no monospace, so that chip alone stays off.
        val threema = open.copy(chatSyntax = ChatSyntax.forPackage("ch.threema.app"))
        assertTrue(SelectionMacro.CHAT_MONO !in SelectionMacros.offer(SelectionKind.TEXT, all, threema))
        assertTrue(SelectionMacro.CHAT_BOLD in SelectionMacros.offer(SelectionKind.TEXT, all, threema))
    }

    @Test
    fun `to bangla needs latin without bengali, to banglish needs bengali`() {
        val all = SelectionMacros.configurable.toSet()
        val latin = ContentFlags(hasLatin = true)
        val mixed = ContentFlags(hasLatin = true, hasBengali = true)
        assertTrue(SelectionMacro.TO_BANGLA in SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(bengaliLoaded = true, content = latin)))
        assertTrue(SelectionMacro.TO_BANGLA !in SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(bengaliLoaded = false, content = latin)))
        assertTrue(SelectionMacro.TO_BANGLA !in SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(bengaliLoaded = true, content = mixed)))
        assertTrue(SelectionMacro.TO_BANGLISH in SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(content = mixed)))
    }

    @Test
    fun `the hindi pair is for someone typing hindi phonetically, both ways`() {
        val all = SelectionMacros.configurable.toSet()
        val latin = ContentFlags(hasLatin = true)
        val devanagari = ContentFlags(hasDevanagari = true)
        val mixed = ContentFlags(hasLatin = true, hasDevanagari = true)
        fun offer(loaded: Boolean, content: ContentFlags) =
            SelectionMacros.offer(SelectionKind.TEXT, all, MacroGates(hindiLoaded = loaded, content = content))
        assertTrue(SelectionMacro.TO_HINDI in offer(true, latin))
        assertTrue(SelectionMacro.TO_HINDI !in offer(false, latin))
        assertTrue(SelectionMacro.TO_HINDI !in offer(true, mixed))
        assertTrue(SelectionMacro.TO_HINGLISH in offer(true, devanagari))
        assertTrue(SelectionMacro.TO_HINGLISH in offer(true, mixed))
        // Devanagari is Marathi and Nepali too: no Hindi, no offer to read it as Hindi.
        assertTrue(SelectionMacro.TO_HINGLISH !in offer(false, devanagari))
        // Loading Hindi says nothing about Bengali, and the other way round.
        assertTrue(SelectionMacro.TO_BANGLA !in offer(true, latin))
    }

    @Test
    fun `translate ships on, just before the AI chip`() {
        // #434: the Translate tool is how a selection in a language the user
        // cannot read gets read, and it must not be mistaken for the AI's own.
        assertTrue(SelectionMacro.TRANSLATE in SelectionMacros.defaultMacros)
        val order = SelectionMacros.defaultOrder
        assertEquals(order.indexOf(SelectionMacro.AI) - 1, order.indexOf(SelectionMacro.TRANSLATE))
        assertTrue(SelectionMacro.SEARCH !in SelectionMacros.defaultMacros)
    }

    @Test
    fun `the lists agree with each other`() {
        assertEquals(SelectionMacros.configurable.toSet() - SelectionMacros.ladderOnly, SelectionMacros.defaultOrder.toSet())
        assertEquals(SelectionMacros.defaultOrder.size, SelectionMacros.defaultOrder.distinct().size)
        assertEquals(SelectionMacro.entries.toSet() - SelectionMacros.fixedCaseMacros.toSet(), SelectionMacros.configurable.toSet())
        assertTrue(SelectionMacros.defaultMacros.all { it in SelectionMacros.configurable })
        val unsafe = SelectionMacro.entries.filter { !it.directBootSafe }.toSet()
        assertEquals(
            SelectionMacro.entries.filter { it.leavesApp }.toSet() + SelectionMacro.PASTE + SelectionMacro.READ_ALOUD,
            unsafe,
        )
        assertEquals(
            SelectionMacros.fixedCaseMacros + SelectionMacro.CASE_CAMEL,
            SelectionMacros.caseLadder(setOf(SelectionMacro.CASE_CAMEL, SelectionMacro.COPY)),
        )
    }

    @Test
    fun `ordinary text raises no content flags`() {
        for (text in listOf("Hello there", "12/04/2025", "report.txt", "Password", "50% off", "the meeting is at 3")) {
            val latin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
            assertEquals(text, ContentFlags(hasLatin = latin), SelectionMacros.detectContent(text))
        }
    }

    @Test
    fun `content flags read what is there`() {
        assertTrue(SelectionMacros.detectContent("ami valo asi").hasLatin)
        assertTrue(SelectionMacros.detectContent("আমি ভালো").hasBengali)
        assertTrue(SelectionMacros.detectContent("कैसे हो").hasDevanagari)
        assertFalse(SelectionMacros.detectContent("আমি ভালো").hasDevanagari)
        // Its digits and the danda are not words, and the digits still count as foreign.
        assertFalse(SelectionMacros.detectContent("२०२४ ।").hasDevanagari)
        assertTrue(SelectionMacros.detectContent("२०२४").hasForeignDigits)
        assertTrue(SelectionMacros.detectContent("০১৭").hasForeignDigits)
        assertTrue(SelectionMacros.detectContent("a\nb").multiLine)
        assertFalse(SelectionMacros.detectContent("a\n\n").multiLine)
        assertEquals(ColourForm.HEX, SelectionMacros.detectContent("#336699").colour?.form)
        assertEquals(JsonReformat.Shape.MINIFIED, SelectionMacros.detectContent("""{"a":1}""").jsonShape)
        assertTrue(SelectionMacros.detectContent("SGVsbG8gd29ybGQ=").base64)
        assertTrue(SelectionMacros.detectContent("a%20b").urlEncoded)
        // Dates and places only when asked for.
        assertNull(SelectionMacros.detectContent("14 Sep 2026").dateTime)
        assertNull(SelectionMacros.detectContent("12 Baker Street").place)
        val options = DetectOptions(dateTime = true, place = true, nowMillis = 0L)
        assertTrue(SelectionMacros.detectContent("14 Sep 2026", options).dateTime?.hasDate == true)
        assertTrue(SelectionMacros.detectContent("12 Baker Street", options).place is Place.Address)
        assertNull(SelectionMacros.detectContent("14 Sep 2026", options).place)
        // A long paragraph with everything on stays cheap and quiet.
        val paragraph = "lorem ipsum dolor sit amet ".repeat(150)
        val flags = SelectionMacros.detectContent(paragraph, options)
        assertEquals(ContentFlags(hasLatin = true), flags)
    }

    @Test
    fun `select all leads every row after undo, and ships on`() {
        for (kind in SelectionKind.entries) {
            assertEquals(kind.name, listOf(SelectionMacro.UNDO, SelectionMacro.SELECT_ALL), SelectionMacros.macrosFor(kind).take(2))
        }
        assertEquals(listOf(SelectionMacro.UNDO, SelectionMacro.SELECT_ALL), SelectionMacros.configurable.take(2))
        assertTrue(SelectionMacro.SELECT_ALL in SelectionMacros.defaultMacros)
        assertTrue(SelectionMacro.UNDO in SelectionMacros.defaultMacros)
    }

    @Test
    fun `select all is dropped once the whole field is selected`() {
        val all = SelectionMacros.configurable.toSet()
        for (kind in SelectionKind.entries) {
            val partial = SelectionMacros.offer(kind, all)
            // Nothing else on the row moves: only the chip with nothing to take.
            assertEquals(kind.name, partial - SelectionMacro.SELECT_ALL - SelectionMacro.FIND, SelectionMacros.offer(kind, all, MacroGates(wholeField = true)))
        }
    }

    @Test
    fun `a macro the user switched off is never offered`() {
        val offered = SelectionMacros.offer(
            SelectionKind.PHONE,
            SelectionMacros.defaultMacros - SelectionMacro.WHATSAPP,
        )
        assertTrue(SelectionMacro.WHATSAPP !in offered)
        assertTrue(SelectionMacro.CALL in offered)
    }

    @Test
    fun `missing apps and tools take their chips away`() {
        val all = SelectionMacros.configurable.toSet()
        val phone = SelectionMacros.offer(SelectionKind.PHONE, all, MacroGates(whatsAppInstalled = false))
        assertTrue(SelectionMacro.WHATSAPP !in phone)
        val url = SelectionMacros.offer(SelectionKind.URL, all, MacroGates(qrAvailable = false))
        assertTrue(SelectionMacro.QR !in url)
    }

    @Test
    fun `format is dropped when it would do nothing`() {
        val all = SelectionMacros.configurable.toSet()
        assertTrue(SelectionMacro.FORMAT !in SelectionMacros.offer(SelectionKind.URL, all, MacroGates(formattable = false)))
        assertTrue(SelectionMacro.FORMAT in SelectionMacros.offer(SelectionKind.URL, all))
    }

    @Test
    fun `fancy is offered on plain text and on no entity`() {
        val all = SelectionMacros.configurable.toSet()
        assertTrue(SelectionMacro.FANCY in SelectionMacros.offer(SelectionKind.TEXT, all))
        // A styled link is not a link and a styled number cannot be dialled.
        for (kind in listOf(SelectionKind.PHONE, SelectionKind.EMAIL, SelectionKind.URL)) {
            assertTrue(kind.name, SelectionMacro.FANCY !in SelectionMacros.offer(kind, all))
        }
    }

    @Test
    fun `the case ladder is never on the bar itself`() {
        for (macro in SelectionMacros.fixedCaseMacros) {
            assertTrue(macro !in SelectionMacros.configurable)
            for (kind in SelectionKind.entries) {
                assertTrue(macro !in SelectionMacros.macrosFor(kind))
            }
        }
        for (macro in SelectionMacros.ladderOnly) {
            assertTrue(macro in SelectionMacros.configurable)
            for (kind in SelectionKind.entries) {
                assertTrue(macro !in SelectionMacros.macrosFor(kind))
            }
        }
    }

    @Test
    fun `the code cases ride the case ladder`() {
        assertEquals("helloWorld", SelectionMacros.applyCase("Hello World", SelectionMacro.CASE_CAMEL))
        assertEquals("HELLO_WORLD", SelectionMacros.applyCase("hello world", SelectionMacro.CASE_CONSTANT))
        assertNull(SelectionMacros.applyCase("helloWorld", SelectionMacro.CASE_CAMEL))
    }

    // ---- formatting ----

    @Test
    fun `a number is rewritten in the user's own mask`() {
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("01712345678", bd))
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("+8801712345678", bd))
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("01712 345678", bd))
    }

    @Test
    fun `with no mask a number falls back to its digits`() {
        assertEquals("+8801712345678", SelectionMacros.formatPhone("+880 1712 345678", emptyList()))
        assertEquals("01712345678", SelectionMacros.formatPhone("01712-345678", emptyList()))
    }

    @Test
    fun `format returns null when it would change nothing`() {
        assertNull(SelectionMacros.format("+880 1712-345678", SelectionKind.PHONE, bd))
        assertNull(SelectionMacros.format("https://example.com/a", SelectionKind.URL))
        assertNull(SelectionMacros.format("example@gmail.com", SelectionKind.EMAIL))
        // Plain text is the case ladder's business, never this.
        assertNull(SelectionMacros.format("Hello", SelectionKind.TEXT))
    }

    @Test
    fun `a link loses its trackers and keeps everything else`() {
        assertEquals(
            "https://example.com/a?id=7#top",
            SelectionMacros.formatUrl("https://example.com/a?utm_source=x&id=7&fbclid=abc#top"),
        )
        assertEquals(
            "https://example.com/a",
            SelectionMacros.formatUrl("https://example.com/a?utm_campaign=spring"),
        )
        // A bare domain gains the scheme it was missing and nothing else.
        assertEquals("https://example.com/a", SelectionMacros.formatUrl("example.com/a"))
    }

    @Test
    fun `remove trackers keeps the link's own shape and only offers itself when there is something to take`() {
        assertEquals(
            "example.com/watch?v=abc#t",
            SelectionMacros.stripTrackers("example.com/watch?v=abc&si=XyZ&UTM_Source=share#t"),
        )
        assertEquals("https://shop.example/p", SelectionMacros.stripTrackers("https://shop.example/p?mtm_campaign=a&hsa_ad=1"))
        assertNull(SelectionMacros.stripTrackers("https://example.com/a?id=7&ref=home"))
        assertNull(SelectionMacros.stripTrackers("https://example.com/a"))
        assertNull(SelectionMacros.stripTrackers("https://example.com/a?"))

        assertTrue(SelectionMacros.detectContent("https://x.com/a?fbclid=1").hasTrackers)
        assertTrue(!SelectionMacros.detectContent("https://x.com/a?id=1").hasTrackers)

        val all = SelectionMacros.configurable.toSet()
        val tracked = MacroGates(content = ContentFlags(hasTrackers = true))
        assertTrue(SelectionMacro.STRIP_TRACKERS in SelectionMacros.offer(SelectionKind.URL, all, tracked))
        assertTrue(SelectionMacro.STRIP_TRACKERS !in SelectionMacros.offer(SelectionKind.URL, all))
        assertTrue(SelectionMacro.STRIP_TRACKERS !in SelectionMacros.offer(SelectionKind.TEXT, all, tracked))
    }

    @Test
    fun `an address is lower-cased`() {
        assertEquals("example@gmail.com", SelectionMacros.format("Example@Gmail.COM", SelectionKind.EMAIL))
    }

    // ---- the case ladder ----

    @Test
    fun `upper and lower are exactly that`() {
        assertEquals("HELLO THERE", SelectionMacros.applyCase("Hello there", SelectionMacro.CASE_UPPER))
        assertEquals("hello there", SelectionMacros.applyCase("Hello There", SelectionMacro.CASE_LOWER))
    }

    @Test
    fun `title case capitalises each word and sentence case only the first`() {
        assertEquals(
            "The Quick Brown Fox",
            SelectionMacros.applyCase("the quick brown fox", SelectionMacro.CASE_TITLE),
        )
        assertEquals(
            "The QUICK brown fox. It ran.",
            SelectionMacros.applyCase("the QUICK brown fox. it ran.", SelectionMacro.CASE_SENTENCE),
        )
    }

    @Test
    fun `an apostrophe stays inside its word`() {
        assertEquals("Don't Stop", SelectionMacros.applyCase("don't stop", SelectionMacro.CASE_TITLE))
    }

    @Test
    fun `an acronym in mixed text survives, and a shouted selection does not`() {
        assertEquals(
            "The NASA Launch",
            SelectionMacros.applyCase("the NASA launch", SelectionMacro.CASE_TITLE),
        )
        // Every word capitals means the user is fixing shouting, not protecting
        // acronyms, so Title case has to have somewhere to go.
        assertEquals(
            "Hello There",
            SelectionMacros.applyCase("HELLO THERE", SelectionMacro.CASE_TITLE),
        )
        assertEquals(
            "Hello there",
            SelectionMacros.applyCase("HELLO THERE", SelectionMacro.CASE_SENTENCE),
        )
    }

    @Test
    fun `a case that changes nothing reports nothing`() {
        assertNull(SelectionMacros.applyCase("HELLO", SelectionMacro.CASE_UPPER))
        assertNull(SelectionMacros.applyCase("hello", SelectionMacro.CASE_LOWER))
    }

    // ---- dialling ----

    @Test
    fun `dialling adds the country code the mask names`() {
        assertEquals("8801712345678", SelectionMacros.dialDigits("01712345678", bd))
        assertEquals("15551234567", SelectionMacros.dialDigits("+1 555-123-4567", bd))
        // No mask, no country: whatever digits were selected.
        assertEquals("01712345678", SelectionMacros.dialDigits("01712-345678", emptyList()))
    }

    @Test
    fun `a link is made openable`() {
        assertEquals("https://example.com", SelectionMacros.openableUrl("example.com"))
        assertEquals("http://example.com", SelectionMacros.openableUrl("http://example.com"))
    }
}
