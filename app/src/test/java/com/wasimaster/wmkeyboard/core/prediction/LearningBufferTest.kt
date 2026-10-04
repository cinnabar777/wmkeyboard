package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBufferTest {

    private fun LearningBuffer.words(): List<String> = drain().map { it.word }

    /** Commits [word], then reports the caret the editor would echo back. */
    private fun LearningBuffer.commit(word: String, caret: Int) {
        push(word, "en", 1)
        onCaret(caret)
    }

    @Test
    fun aGlideShapeRidesTheNewestCopyOfItsWordAndGoesWhereItGoes() {
        val buffer = LearningBuffer()
        val sample = GlideShapeSample(1L, ByteArray(96))
        assertFalse("nothing queued yet", buffer.attachGlide("wibble", sample))
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        buffer.commit("wibble", 21)
        assertTrue(buffer.attachGlide("Wibble", sample))
        val entries = buffer.drain()
        assertEquals(listOf(null, null, sample), entries.map { it.glideShape })
        // A copy the user takes back takes its shape with it.
        buffer.commit("wobble", 7)
        assertTrue(buffer.attachGlide("wobble", sample))
        buffer.drop("wobble")
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun typingForwardKeepsEverything() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        assertEquals(listOf("wibble", "wobble"), buffer.words())
    }

    @Test
    fun backspacingIntoTheLastWordDropsIt() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        // The keyboard reports its own backspace before the editor echoes it;
        // one character off the end of the word it just committed is enough.
        buffer.onDeleted(13, 14)
        buffer.onCaret(13)
        assertEquals(listOf("wibble"), buffer.words())
    }

    /**
     * Issue #115: going back to one word said nothing about the rest of the
     * text, but the whole queue in front of the caret was thrown away — so a
     * session spent reading back what had been written learned nothing at all.
     * The words the caret jumped over settle. The one it landed in is kept,
     * undecided (#159): only an edit the keyboard makes to it drops it.
     */
    @Test
    fun goingBackToEditSettlesTheWordsTheCaretJumpedOver() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        buffer.commit("wubble", 21)
        // A tap back into the first word.
        val moved = buffer.onCaret(3)
        assertTrue(moved.dropped.isEmpty())
        assertEquals(listOf("wobble", "wubble"), moved.settled.map { it.word })
        val waiting = buffer.drain().single()
        assertEquals("wibble", waiting.word)
        assertTrue(waiting.suspended)
    }

    /**
     * Issue #159: the caret parked on a word when the keyboard closed was
     * read as an edit of it, and that one word was the only one not learned.
     */
    @Test
    fun aCaretParkedOnAWordDoesNotUnlearnIt() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        buffer.onCaret(10)
        assertEquals(listOf("wibble", "wobble"), buffer.words())
    }

    /**
     * Issue #160: a spacebar swipe walks the caret back one character at a
     * time, through every word on its way. Each was dropped as the caret
     * entered it, and the personal dictionary got the first two words of a
     * four-word sentence.
     */
    @Test
    fun aSpacebarWalkBackThroughTheTextLearnsAllOfIt() {
        val buffer = LearningBuffer()
        buffer.commit("whatever", 9)
        buffer.commit("is", 12)
        buffer.commit("going", 18)
        buffer.commit("on", 21)
        val settled = ArrayList<String>()
        for (caret in 20 downTo 13) settled += buffer.onCaret(caret).settled.map { it.word }
        // Then a tap into the first word, and the app is left.
        settled += buffer.onCaret(3).settled.map { it.word }
        settled += buffer.words()
        assertEquals(setOf("whatever", "is", "going", "on"), settled.toSet())
        assertEquals(4, settled.size)
    }

    /** Issue #160: words a delete swipe took out in one go were settling as "jumped over". */
    @Test
    fun aDeleteSwipeTakesItsWordsOutOfTheQueue() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        buffer.commit("wubble", 21)
        val dropped = buffer.onDeleted(7, 21)
        assertEquals(listOf("wobble", "wubble"), dropped.map { it.word })
        // The editor's echo lands where the deletion started.
        val moved = buffer.onCaret(7)
        assertTrue(moved.settled.isEmpty())
        assertEquals(listOf("wibble"), buffer.words())
    }

    @Test
    fun wordsAfterADeletedStretchMoveUp() {
        val buffer = LearningBuffer()
        buffer.commit("aa", 3)
        buffer.commit("bb", 6)
        buffer.commit("cc", 9)
        // The range stops short of the next word's slack: a delete that runs
        // right up against a word counts as touching it (the anchor may or may
        // not include the trailing space), and that word goes too.
        assertEquals(listOf("aa"), buffer.onDeleted(0, 2).map { it.word })
        assertEquals(listOf(4, 7), buffer.drain().map { it.anchor })
    }

    @Test
    fun aCommitLandingOnASuspendedWordReplacesIt() {
        val buffer = LearningBuffer()
        buffer.commit("going", 6)
        buffer.onCaret(3)
        buffer.push("goings", "en", 1, known = true)
        buffer.onCaret(7)
        val entry = buffer.drain().single()
        assertEquals("goings", entry.word)
        assertEquals("going", entry.replaces)
        assertTrue(entry.suspect)
    }

    @Test
    fun aWordTypedAfterASuspendedOneLeavesItAlone() {
        val buffer = LearningBuffer()
        buffer.commit("going", 6)
        buffer.onCaret(5)
        buffer.push("on", "en", 1, known = true)
        buffer.onCaret(9)
        assertEquals(listOf("going", "on"), buffer.words())
    }

    @Test
    fun aReplacesPushDropsTheSuspendedCopy() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        buffer.onCaret(3)
        buffer.push("the", "en", 1, known = true, replaces = "teh")
        buffer.onCaret(4)
        val entry = buffer.drain().single()
        assertEquals("the", entry.word)
        assertEquals("teh", entry.replaces)
        assertFalse(entry.suspect)
    }

    @Test
    fun replacesReplacesWordInPlacePreservingSentenceOrder() {
        val buffer = LearningBuffer()
        buffer.commit("what", 4)
        buffer.commit("ia", 7)
        buffer.commit("this", 12)
        buffer.commit("going", 18)
        buffer.commit("to", 21)
        buffer.commit("do", 24)

        // Replace "ia" with "is"
        buffer.push("is", "en", 1, known = true, replaces = "ia")

        assertEquals(listOf("what", "is", "this", "going", "to", "do"), buffer.words())
    }

    /**
     * The same move, with the caret nowhere near any of them: a tap into text
     * typed before the keyboard even opened settles the lot.
     */
    @Test
    fun aCaretInOlderTextSettlesEverythingItJumpedOver() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 107)
        buffer.commit("wobble", 114)
        val moved = buffer.onCaret(3)
        assertTrue(moved.dropped.isEmpty())
        assertEquals(listOf("wibble", "wobble"), moved.settled.map { it.word })
    }

    @Test
    fun aCaretAtTheEndChangesNothing() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.onCaret(7)
        buffer.onCaret(7)
        assertEquals(listOf("wibble"), buffer.words())
    }

    @Test
    fun anEntryIsNeverDroppedByItsOwnEcho() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        // The next word's commit echo arrives before anything anchors it; it
        // must anchor rather than be read as the caret jumping backwards.
        buffer.push("wobble", "en", 1)
        buffer.onCaret(14)
        assertEquals(listOf("wibble", "wobble"), buffer.words())
    }

    @Test
    fun dropRemovesOneWordByName() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.commit("wobble", 14)
        buffer.drop("Wibble")
        assertEquals(listOf("wobble"), buffer.words())
    }

    @Test
    fun overflowSettlesTheOldestByDistance() {
        val buffer = LearningBuffer(capacity = 2)
        assertTrue(buffer.push("one", "en", 1).isEmpty())
        assertTrue(buffer.push("two", "en", 1).isEmpty())
        val settled = buffer.push("three", "en", 1)
        assertEquals(listOf("one"), settled.map { it.word })
        assertEquals(listOf("two", "three"), buffer.words())
    }

    @Test
    fun drainEmptiesTheBuffer() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        assertEquals(listOf("wibble"), buffer.words())
        assertTrue(buffer.isEmpty())
        assertTrue(buffer.words().isEmpty())
    }

    @Test
    fun clearThrowsTheQueueAway() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.clear()
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun theWeightAndLanguageSurviveTheQueue() {
        val buffer = LearningBuffer()
        buffer.push("wibble", "bn", 2)
        val entry = buffer.drain().single()
        assertEquals("bn", entry.langId)
        assertEquals(2, entry.weight)
    }

    @Test
    fun whetherTheWordWasRecognisedSurvivesTheQueue() {
        val buffer = LearningBuffer()
        buffer.push("wibble", "en", 1, caseTrusted = true, known = true)
        buffer.push("wobble", "en", 1)
        val (recognised, unrecognised) = buffer.drain()
        assertTrue(recognised.known)
        assertTrue(recognised.caseTrusted)
        assertFalse(unrecognised.known)
    }

    /**
     * The whole point of #101: a recognised word the user glided, read back
     * and took out again must never reach the personal dictionary.
     */
    @Test
    fun aRecognisedWordBackspacedAwayNeverSettles() {
        val buffer = LearningBuffer()
        buffer.push("form", "en", 1, known = true)
        buffer.onCaret(4)
        // Backspacing the swipe away: the keyboard reports each character it
        // takes, and the echo puts the caret in front of where the word was.
        buffer.onDeleted(3, 4)
        buffer.onCaret(3)
        buffer.onDeleted(0, 3)
        buffer.onCaret(0)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun onDeletedHandsBackWhatItDropped() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        val dropped = buffer.onDeleted(3, 4)
        assertEquals(listOf("teh"), dropped.map { it.word })
        assertEquals(4, dropped.single().anchor)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun aDroppedWordPairsWithTheWordCommittedInItsPlace() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        // Backspace into it, retype, space: the new word ends where the old did.
        buffer.onCaret(3)
        buffer.push("the", "en", 1, known = true)
        buffer.onCaret(4)
        val entry = buffer.drain().single()
        assertEquals("teh", entry.replaces)
        assertTrue(entry.suspect)
        assertNull(entry.revised)
    }

    @Test
    fun anExplicitReplacesIsNeverOverwrittenByAGuess() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        buffer.onCaret(3)
        buffer.push("the", "en", 1, known = true, replaces = "tge")
        buffer.onCaret(4)
        val entry = buffer.drain().single()
        assertEquals("tge", entry.replaces)
        assertFalse(entry.suspect)
    }

    @Test
    fun positionAloneIsOnlyEverASuspicion() {
        // "the cat sat": the caret goes back before "sat" and "so" is typed in
        // front of it. By position and distance this looks like sat -> so; the
        // suspect flag is what sends the caller to the field to check.
        val buffer = LearningBuffer()
        buffer.commit("the", 4)
        buffer.commit("cat", 8)
        buffer.commit("sat", 12)
        buffer.onCaret(8)
        buffer.push("so", "en", 1, known = true)
        buffer.onCaret(11)
        val entry = buffer.drain().last()
        assertEquals("sat", entry.replaces)
        assertTrue(entry.suspect)
    }

    @Test
    fun aWordFarFromTheSuspendedOneIsNotItsReplacement() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        buffer.onCaret(3)
        buffer.push("the", "en", 1, known = true)
        // Anchored twenty characters away: the user went on writing elsewhere,
        // and the word they had been looking at stands.
        buffer.onCaret(24)
        val (old, new) = buffer.drain()
        assertEquals("teh", old.word)
        assertNull(new.replaces)
    }

    @Test
    fun aRewriteIsNotAFix() {
        val buffer = LearningBuffer()
        buffer.commit("wibble", 7)
        buffer.onCaret(2)
        buffer.push("zzzzzz", "en", 1, known = true)
        buffer.onCaret(7)
        assertNull(buffer.drain().single().replaces)
    }

    @Test
    fun aFatFingeredSpacePutBackPairsBothHalves() {
        val buffer = LearningBuffer()
        buffer.commit("thisbis", 8)
        buffer.onCaret(5)
        buffer.push("this", "en", 1, known = true)
        buffer.onCaret(5)
        buffer.push("is", "en", 1, known = true)
        buffer.onCaret(8)
        val (first, second) = buffer.drain()
        assertNull(first.replaces)
        assertEquals("thisbis", second.replaces)
        assertEquals("this is", second.revised)
        assertTrue(second.suspect)
    }

    @Test
    fun theSameSpellingComingBackIsNotAReplacement() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        buffer.onCaret(3)
        buffer.push("Teh", "en", 1)
        buffer.onCaret(4)
        assertNull(buffer.drain().single().replaces)
    }

    @Test
    fun drainForgetsTheDroppedWords() {
        val buffer = LearningBuffer()
        buffer.commit("teh", 4)
        buffer.onCaret(3)
        buffer.drain()
        buffer.push("the", "en", 1, known = true)
        buffer.onCaret(4)
        assertNull(buffer.drain().single().replaces)
    }

    @Test
    fun anExplicitAnchorIsKeptAndTheNextEchoDoesNotMoveIt() {
        val buffer = LearningBuffer()
        // A word fixed in place at 10..13, then the caret taps to the end of the text.
        buffer.push("the", "en", 1, known = true, replaces = "teh", anchor = 13)
        buffer.onCaret(40)
        val entry = buffer.drain().single()
        assertEquals(13, entry.anchor)
        assertEquals("teh", entry.replaces)
    }

    @Test
    fun theTapsAndTypedTextSurviveTheQueue() {
        val buffer = LearningBuffer()
        val taps = listOf(TouchPoint(1f, 1f), null, TouchPoint(3f, 1f))
        val keys = KeyTouchModel(mapOf('t' to TouchPoint(1f, 1f)))
        buffer.push("the", "en", 2, known = true, origin = WordOrigin.PICK, typed = "th", taps = taps, keys = keys)
        val entry = buffer.drain().single()
        assertEquals(WordOrigin.PICK, entry.origin)
        assertEquals("th", entry.typed)
        assertEquals(taps, entry.taps)
        assertTrue(keys === entry.keys)
    }

    @Test
    fun aDroppedWordCarriesItsOrigin() {
        val buffer = LearningBuffer()
        buffer.push("hello", "en", 1, known = true, origin = WordOrigin.GLIDE)
        buffer.onCaret(6)
        // Still known while the caret merely sits in it, and after a delete.
        buffer.onCaret(2)
        assertEquals(WordOrigin.GLIDE, buffer.originOf("Hello"))
        val dropped = buffer.onDeleted(2, 3).single()
        assertEquals(WordOrigin.GLIDE, dropped.origin)
        assertEquals(WordOrigin.GLIDE, buffer.originOf("hello"))
    }

    @Test
    fun snapshotToJsonAndRestoreFromJsonPreservesBufferState() {
        val buffer = LearningBuffer(capacity = 300)
        val taps = listOf(TouchPoint(1.2f, 3.4f), null, TouchPoint(5.6f, 7.8f))
        val keys = KeyTouchModel(mapOf('t' to TouchPoint(1.2f, 3.4f), 'e' to TouchPoint(5.6f, 7.8f)))
        val glideSample = GlideShapeSample(42L, byteArrayOf(10, 20, 30, 40))

        buffer.push(
            word = "testing",
            langId = "en",
            weight = 2,
            caseTrusted = true,
            known = false,
            origin = WordOrigin.GLIDE,
            replaces = "tasting",
            anchor = 10,
            typed = "tstng",
            taps = taps,
            keys = keys,
            replacesOrigin = WordOrigin.TYPED,
            revised = "testing now",
        )
        buffer.attachGlide("testing", glideSample)

        // Drop an entry into recent
        buffer.push("oldword", "en", 1)
        buffer.onCaret(17)
        buffer.onDeleted(10, 17)

        val json = buffer.snapshotToJson()
        assertTrue(json.isNotBlank())

        val restoredBuffer = LearningBuffer()
        restoredBuffer.restoreFromJson(json)

        // Verify recent dropped item origin BEFORE draining entries, since drain() clears recent
        assertEquals(WordOrigin.TYPED, restoredBuffer.originOf("oldword"))

        val entries = restoredBuffer.drain()
        assertEquals(1, entries.size)
        val restored = entries.single()

        assertEquals("testing", restored.word)
        assertEquals("en", restored.langId)
        assertEquals(2, restored.weight)
        assertTrue(restored.caseTrusted)
        assertFalse(restored.known)
        assertEquals(WordOrigin.GLIDE, restored.origin)
        assertEquals("tasting", restored.replaces)
        assertEquals("tstng", restored.typed)
        assertEquals(WordOrigin.TYPED, restored.replacesOrigin)
        assertEquals("testing now", restored.revised)
        assertEquals(10, restored.anchor)
        assertEquals(glideSample.layoutKey, restored.glideShape?.layoutKey)
        assertEquals(glideSample.shape.toList(), restored.glideShape?.shape?.toList())

        // Check key touch model restored
        assertTrue(restored.keys != null)
        assertTrue(restored.keys!!.knows('t'))
        assertEquals(TouchPoint(1.2f, 3.4f), restored.keys!!.center('t'))

    }

    @Test
    fun purgeReadingsClearsCandidateReadingsFromEntries() {
        val buffer = LearningBuffer()
        buffer.push("apple", "en", 1)
        buffer.attachReadings("apple", listOf("apple", "apply", "ample"))
        assertEquals(listOf("apple", "apply", "ample"), buffer.queuedEntries.single().readings)

        buffer.purgeReadings()
        assertNull(buffer.queuedEntries.single().readings)
    }
}
