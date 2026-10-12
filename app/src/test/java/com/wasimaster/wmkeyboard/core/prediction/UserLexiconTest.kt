package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserLexiconTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "learning/user_lexicon.json")

    @Test
    fun legacySnapshotWithoutNewFieldsLoads() {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText("""{"words":{"hello":5},"bigrams":{"hello":{"world":2}}}""")
        val lexicon = UserLexicon(f)
        assertEquals(5, lexicon.frequencyOf("hello"))
        assertEquals(listOf("world"), lexicon.nextWords("hello", 3))
        assertEquals(2, lexicon.bigramCount("hello", "world"))
        assertEquals(0, lexicon.skip1gramCount("hello", "world"))
    }

    @Test
    fun skipStoresWrittenByPositionsBackLoadUnderTheSkippedWordsNaming() {
        // The first shipped naming counted positions back: "skip2grams" was
        // the word two back (one word skipped) and "skip3grams" the word
        // three back. Read into the 1-skip and 2-skip stores, and rewritten
        // under the current naming by the next save.
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(
            """{"words":{"gotten":3},"skip2grams":{"gotten":{"that":4}},"skip3grams":{"gotten":{"you've":2}}}"""
        )
        val lexicon = UserLexicon(f)
        assertEquals(4, lexicon.skip1gramCount("gotten", "that"))
        assertEquals(2, lexicon.skip2gramCount("gotten", "you've"))
        assertEquals(0, lexicon.skip2gramCount("gotten", "that"))
        lexicon.save()
        val text = f.readText()
        assertTrue("\"skipSchema\":1" in text)
        assertTrue("\"skip1grams\":{\"gotten\":{\"that\":4}}" in text)
        assertTrue("\"skip2grams\":{\"gotten\":{\"you've\":2}}" in text)
        assertFalse("skip3grams" in text)
        val back = UserLexicon(f)
        assertEquals(4, back.skip1gramCount("gotten", "that"))
        assertEquals(2, back.skip2gramCount("gotten", "you've"))
    }

    @Test
    fun roundTripPreservesEverything() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("hello", 3)
            learnBigram("hello", "world")
            learnBigram("hello", "world")
            learnBigram("hello", "there")
            save()
        }
        val back = UserLexicon(f)
        assertEquals(3, back.frequencyOf("hello"))
        assertEquals(2, back.bigramCount("hello", "world"))
        assertEquals(listOf("world", "there"), back.nextWords("hello", 5))
    }

    @Test
    fun languageTagsRoundTripAndFollowTheWord() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("wasi", 3, langId = "bn_rom")
            // Untagged learns (blank langId) leave no tag behind.
            learnWord("hello", 3)
            save()
        }
        val back = UserLexicon(f)
        assertEquals("bn_rom", back.languageOf("wasi"))
        assertEquals(null, back.languageOf("hello"))
        // The most recent language wins.
        back.learnWord("wasi", 1, langId = "en")
        assertEquals("en", back.languageOf("wasi"))
        // Forgetting the word drops its tag with it.
        back.forget("wasi")
        assertEquals(null, back.languageOf("wasi"))
    }

    @Test
    fun nextWordsOrderSurvivesMutation() {
        val lexicon = UserLexicon(null)
        lexicon.learnBigram("a", "x")
        lexicon.learnBigram("a", "y")
        lexicon.learnBigram("a", "y")
        assertEquals(listOf("y", "x"), lexicon.nextWords("a", 5))
        // The cached order must invalidate when counts change.
        lexicon.learnBigram("a", "x")
        lexicon.learnBigram("a", "x")
        assertEquals(listOf("x", "y"), lexicon.nextWords("a", 5))
    }

    @Test
    fun followerListIsCapped() {
        val lexicon = UserLexicon(null)
        // "keep" gets weight so it survives; then flood with singles.
        repeat(5) { lexicon.learnBigram("prev", "keep") }
        for (i in 0 until 40) lexicon.learnBigram("prev", "w$i")
        val followers = lexicon.followerCounts("prev")
        assertTrue("cap exceeded: ${followers.size}", followers.size <= 32)
        assertTrue("keep" in followers)
    }

    @Test
    fun wordLengthAndCountGuards() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("x".repeat(33))
        assertFalse(lexicon.contains("x".repeat(33)))
        lexicon.learnWord("ok", count = Int.MAX_VALUE)
        lexicon.learnWord("ok", count = Int.MAX_VALUE)
        assertTrue(lexicon.frequencyOf("ok") in 1..1_000_000)
    }

    @Test
    fun capEvictsStaleWordsButKeepsRecentAndSticky() {
        val f = file()
        val lexicon = UserLexicon(f)
        // Old cohort learned at generation 0, then aged far past the cap's
        // half-life by saving repeatedly (each dirty save ticks a generation).
        for (i in 0 until 3000) lexicon.learnWord("old$i")
        repeat(200) {
            lexicon.learnWord("clock")
            lexicon.save()
        }
        lexicon.addWord("cherished", boost = 200) // sticky
        // Fresh higher-count cohort pushes past MAX_WORDS = 10_000; the
        // eviction quota (size - 9000) is smaller than the old cohort, so
        // every evicted word must come from it and no fresh word may die.
        for (i in 0 until 8000) lexicon.learnWord("new$i", count = 2)
        lexicon.save() // triggers compaction
        val kept = UserLexicon(f)
        assertTrue("sticky word evicted", kept.contains("cherished"))
        for (i in 0 until 8000 step 997) {
            assertTrue("fresh word new$i evicted", kept.contains("new$i"))
        }
        val oldSurvivors = kept.allWords().count { it.first.startsWith("old") }
        assertTrue("no stale words were evicted", oldSurvivors < 3000)
        assertTrue(kept.allWords().size <= 10_000)
    }

    @Test
    fun trigramsServeRoundTripAndCap() {
        val f = file()
        val lexicon = UserLexicon(f)
        lexicon.learnTrigram("i", "was", "going")
        lexicon.learnTrigram("i", "was", "going")
        lexicon.learnTrigram("i", "was", "there")
        assertEquals(listOf("going", "there"), lexicon.nextWordsAfter("i", "was", 5))
        assertEquals(2, lexicon.trigramCount("i", "was", "going"))
        // A different context is a different table.
        assertTrue(lexicon.nextWordsAfter("he", "was", 5).isEmpty())
        lexicon.save()
        val back = UserLexicon(f)
        assertEquals(listOf("going", "there"), back.nextWordsAfter("i", "was", 5))
        // Follower cap applies to trigram contexts too.
        for (i in 0 until 40) back.learnTrigram("a", "b", "w$i")
        assertTrue(back.nextWordsAfter("a", "b", 50).size <= 32)
        // forget() scrubs contexts and followers that mention the word.
        back.learnTrigram("x", "target", "y")
        back.learnTrigram("p", "q", "target")
        back.forget("target")
        assertTrue(back.nextWordsAfter("x", "target", 5).isEmpty())
        assertTrue("target" !in back.nextWordsAfter("p", "q", 5))
    }

    @Test
    fun skip1gramsServeRoundTripAndCap() {
        val f = file()
        val lexicon = UserLexicon(f)
        // "deploy the service", "deploy a service", "deploy my server": the
        // gappy store pools the first two across their middle words.
        lexicon.learnSkip1gram("deploy", "service")
        lexicon.learnSkip1gram("deploy", "service")
        lexicon.learnSkip1gram("deploy", "server")
        assertEquals(2, lexicon.skip1gramCount("deploy", "service"))
        assertEquals(1, lexicon.skip1gramCount("deploy", "server"))
        // A different head is a different table, and the gappy store never
        // leaks into the adjacent one.
        assertEquals(0, lexicon.skip1gramCount("restart", "service"))
        assertEquals(0, lexicon.bigramCount("deploy", "service"))
        lexicon.save()
        val back = UserLexicon(f)
        assertEquals(2, back.skip1gramCount("deploy", "service"))
        // Follower cap applies here too.
        for (i in 0 until 40) back.learnSkip1gram("a", "w$i")
        assertTrue((0 until 40).count { back.skip1gramCount("a", "w$it") > 0 } <= 32)
        // forget() scrubs heads and followers that mention the word.
        back.learnSkip1gram("target", "y")
        back.learnSkip1gram("p", "target")
        back.forget("target")
        assertEquals(0, back.skip1gramCount("target", "y"))
        assertEquals(0, back.skip1gramCount("p", "target"))
    }

    @Test
    fun skip2gramsServeRoundTripAndForget() {
        val f = file()
        val lexicon = UserLexicon(f)
        // "gotten so that you've" (#195): the word three back, across two.
        lexicon.learnWord("gotten", 1)
        lexicon.learnSkip2gram("gotten", "you've")
        lexicon.learnSkip2gram("gotten", "you've")
        assertEquals(2, lexicon.skip2gramCount("gotten", "you've"))
        // Its own table: neither the adjacent nor the 1-skip store sees it.
        assertEquals(0, lexicon.skip1gramCount("gotten", "you've"))
        assertEquals(0, lexicon.bigramCount("gotten", "you've"))
        lexicon.save()
        val back = UserLexicon(f)
        assertEquals(2, back.skip2gramCount("gotten", "you've"))
        assertTrue(back.rename("gotten", "got"))
        assertEquals(2, back.skip2gramCount("got", "you've"))
        assertEquals(0, back.skip2gramCount("gotten", "you've"))
        back.learnSkip2gram("p", "target")
        back.forget("target")
        assertEquals(0, back.skip2gramCount("p", "target"))
    }

    @Test
    fun skip1gramHeadsAreCappedAtSave() {
        val f = file()
        val lexicon = UserLexicon(f)
        // 2,100 heads; the hundred weakest go, the strongest stay.
        for (i in 0 until 2_100) {
            repeat(if (i < 100) 1 else 3) { lexicon.learnSkip1gram("h$i", "w") }
        }
        lexicon.save()
        val back = UserLexicon(f)
        assertEquals(0, back.skip1gramCount("h0", "w"))
        assertEquals(3, back.skip1gramCount("h2099", "w"))
    }

    @Test
    fun nullFileModeLearnsInMemoryOnly() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("ghost", 3)
        assertEquals(3, lexicon.frequencyOf("ghost"))
        lexicon.save() // must be a no-op, not a crash
        lexicon.clear()
        assertFalse(lexicon.contains("ghost"))
    }

    @Test
    fun forgetCleansEveryIndex() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("target", 5)
        lexicon.learnBigram("target", "next")
        lexicon.learnBigram("other", "target")
        lexicon.learnSkip1gram("target", "next")
        lexicon.learnSkip1gram("other", "target")
        lexicon.forget("target")
        assertFalse(lexicon.contains("target"))
        assertTrue(lexicon.nextWords("target", 5).isEmpty())
        assertFalse("target" in lexicon.followerCounts("other"))
        assertEquals(0, lexicon.skip1gramCount("target", "next"))
        assertEquals(0, lexicon.skip1gramCount("other", "target"))
    }

    @Test
    fun settingsAppRewriteWithoutWordGenIsTreatedAsFresh() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("mine", 3)
            save()
        }
        // The settings app rewrites words only (no wordGen for the new entry).
        f.writeText("""{"words":{"edited":7},"bigrams":{}}""")
        val back = UserLexicon(f)
        assertEquals(7, back.frequencyOf("edited"))
        assertFalse(back.contains("mine"))
    }

    @Test
    fun renameKeepsCountTagAndPairs() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("teh", 7, langId = "en")
            learnWord("hello", 3)
            learnWord("world", 3)
            learnBigram("hello", "teh")
            learnBigram("teh", "world")
            learnTrigram("hello", "teh", "world")
            learnSkip1gram("teh", "world")
            learnSkip1gram("hello", "teh")
            assertTrue(rename("teh", "the"))
            save()
        }
        val back = UserLexicon(f)
        assertFalse(back.contains("teh"))
        assertEquals(7, back.frequencyOf("the"))
        assertEquals("en", back.languageOf("the"))
        assertEquals(null, back.languageOf("teh"))
        // Pairs where it followed, led, and sat in the middle all moved.
        assertEquals(1, back.bigramCount("hello", "the"))
        assertEquals(0, back.bigramCount("hello", "teh"))
        assertEquals(listOf("world"), back.nextWords("the", 3))
        assertEquals(1, back.trigramCount("hello", "the", "world"))
        assertEquals(0, back.trigramCount("hello", "teh", "world"))
        // Gappy pairs where it led and where it followed both moved.
        assertEquals(1, back.skip1gramCount("the", "world"))
        assertEquals(0, back.skip1gramCount("teh", "world"))
        assertEquals(1, back.skip1gramCount("hello", "the"))
        assertEquals(0, back.skip1gramCount("hello", "teh"))
    }

    @Test
    fun renameOntoExistingWordMergesCounts() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("colour", 4, langId = "en_gb")
        lexicon.learnWord("color", 6, langId = "en")
        lexicon.learnBigram("nice", "colour")
        lexicon.learnBigram("nice", "color")
        assertTrue(lexicon.rename("colour", "color"))
        assertEquals(10, lexicon.frequencyOf("color"))
        assertFalse(lexicon.contains("colour"))
        // The surviving word keeps its own tag, and the follower counts add.
        assertEquals("en", lexicon.languageOf("color"))
        assertEquals(2, lexicon.bigramCount("nice", "color"))
    }

    @Test
    fun renameRefusesUnknownEmptyAndSameSpelling() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("hello", 2)
        val before = lexicon.mutationCount()
        assertFalse(lexicon.rename("nope", "yes"))
        assertFalse(lexicon.rename("hello", "   "))
        assertFalse(lexicon.rename("hello", "x".repeat(33)))
        // Case folds to the same key, and matches the spelling already stored:
        // nothing to do (a case-only respelling that *changes* the spelling is
        // a case edit, covered below).
        assertFalse(lexicon.rename("hello", "hello"))
        assertFalse(lexicon.rename("nope", "Nope"))
        assertEquals(before, lexicon.mutationCount())
        assertEquals(2, lexicon.frequencyOf("hello"))
    }

    @Test
    fun setCountClampsAndReachesTheTrie() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("hello", 50)
        assertTrue(lexicon.setCount("hello", 5))
        assertEquals(5, lexicon.frequencyOf("hello"))
        assertEquals(5, lexicon.complete("hel", 1).single().frequency)
        assertTrue(lexicon.setCount("hello", 0))
        assertEquals(1, lexicon.frequencyOf("hello"))
        assertTrue(lexicon.setCount("hello", Int.MAX_VALUE))
        assertEquals(UserLexicon.MAX_COUNT, lexicon.frequencyOf("hello"))
        assertFalse(lexicon.setCount("unknown", 3))
        assertFalse(lexicon.contains("unknown"))
    }

    // ---- case memory (#44) ----

    @Test
    fun aTrustedCapitalIsRememberedAndAnUntrustedOneIsNot() {
        val lexicon = UserLexicon(null)
        // Auto-capitalize's capital teaches the word but not the spelling.
        lexicon.learnWord("Boston", caseEvidence = false)
        assertNull(lexicon.displayOf("boston"))
        // The user's own capital does.
        lexicon.learnWord("Boston", caseEvidence = true)
        assertEquals("Boston", lexicon.displayOf("boston"))
        // Looked up under either spelling; the key is what is stored.
        assertEquals("Boston", lexicon.displayOf("BOSTON"))
        assertTrue("Boston" to 2 in lexicon.allWords())
    }

    @Test
    fun ordinaryLowerCaseTypingVotesAStaleCapitalBackOut() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("SALE", caseEvidence = true)
        assertEquals("SALE", lexicon.displayOf("sale"))
        // One sighting in, one sighting out.
        lexicon.learnWord("sale", caseEvidence = true)
        assertNull(lexicon.displayOf("sale"))
        // An untrusted sighting is not a vote either way.
        lexicon.learnWord("Sale", caseEvidence = true)
        lexicon.learnWord("sale", caseEvidence = false)
        assertEquals("Sale", lexicon.displayOf("sale"))
    }

    // ---- pinned case (#100) ----

    @Test
    fun aWordAddedByHandIsPinnedAndNoVoteMovesIt() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("you")
        assertTrue(lexicon.isCasePinned("you"))
        // A sentence-start capital, trusted or not, a hundred times over.
        repeat(100) { lexicon.learnWord("You", caseEvidence = true) }
        assertNull(lexicon.displayOf("you"))
        // And the other way round: a pinned capital survives lower-case typing.
        lexicon.addWord("iPhone")
        repeat(100) { lexicon.learnWord("iphone", caseEvidence = true) }
        assertEquals("iPhone", lexicon.displayOf("iphone"))
    }

    @Test
    fun anUnpinnedAddStillVotes() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("iPhone", pinCase = false)
        assertFalse(lexicon.isCasePinned("iphone"))
        assertEquals("iPhone", lexicon.displayOf("iphone"))
        repeat(20) { lexicon.learnWord("iphone", caseEvidence = true) }
        assertNull(lexicon.displayOf("iphone"))
    }

    @Test
    fun pinningCanBeSwitchedAndForgettingDropsIt() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("Boston", caseEvidence = true)
        assertFalse(lexicon.isCasePinned("boston"))
        assertTrue(lexicon.pinCase("boston", true))
        assertFalse(lexicon.pinCase("boston", true))
        repeat(20) { lexicon.learnWord("boston", caseEvidence = true) }
        assertEquals("Boston", lexicon.displayOf("boston"))
        assertTrue(lexicon.pinCase("boston", false))
        repeat(20) { lexicon.learnWord("boston", caseEvidence = true) }
        assertNull(lexicon.displayOf("boston"))
        // Unknown words cannot be pinned; forgetting a pinned word drops the pin.
        assertFalse(lexicon.pinCase("nowhere", true))
        lexicon.addWord("Wasi")
        lexicon.forget("wasi")
        assertFalse(lexicon.isCasePinned("wasi"))
    }

    @Test
    fun aRespellingPinsTheNewSpelling() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("boston", 3)
        assertTrue(lexicon.rename("boston", "Boston"))
        assertTrue(lexicon.isCasePinned("boston"))
        // Same spelling again is nothing to do.
        assertFalse(lexicon.rename("boston", "Boston"))
        assertTrue(lexicon.rename("Boston", "Bostn"))
        assertTrue(lexicon.isCasePinned("bostn"))
        assertFalse(lexicon.isCasePinned("boston"))
    }

    @Test
    fun pinsRoundTripThroughTheFile() {
        val f = file()
        UserLexicon(f).apply {
            addWord("iPhone")
            learnWord("Boston", caseEvidence = true)
            save()
        }
        val back = UserLexicon(f)
        assertTrue(back.isCasePinned("iphone"))
        assertFalse(back.isCasePinned("boston"))
        repeat(20) { back.learnWord("iphone", caseEvidence = true) }
        assertEquals("iPhone", back.displayOf("iphone"))
    }

    @Test
    fun aWordAddedByHandKeepsItsSpellingAgainstOrdinaryTyping() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("iPhone")
        assertEquals("iPhone", lexicon.displayOf("iphone"))
        // A deliberate add outweighs a stray lower-case commit or two.
        lexicon.learnWord("iphone", caseEvidence = true)
        lexicon.learnWord("iphone", caseEvidence = true)
        assertEquals("iPhone", lexicon.displayOf("iphone"))
        // Adding it in lower case takes the spelling straight back off.
        lexicon.addWord("iphone")
        assertNull(lexicon.displayOf("iphone"))
    }

    @Test
    fun aCaseOnlyRespellingIsACaseEditRatherThanARename() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("boston", 5)
        assertTrue(lexicon.rename("boston", "Boston"))
        assertEquals("Boston", lexicon.displayOf("boston"))
        // The word itself never moved: same key, same count, same bigrams.
        assertEquals(5, lexicon.frequencyOf("boston"))
        assertTrue("Boston" to 5 in lexicon.allWords())
    }

    @Test
    fun aRenameCarriesTheNewSpellingAndDropsTheOldOne() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("bostn", 3, caseEvidence = true)
        assertTrue(lexicon.rename("bostn", "Boston"))
        assertNull(lexicon.displayOf("bostn"))
        assertEquals("Boston", lexicon.displayOf("boston"))
    }

    @Test
    fun spellingsSurviveASaveAndGoWithAForgottenWord() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("Boston", 3, caseEvidence = true)
            learnWord("paris", 3)
            save()
        }
        val reloaded = UserLexicon(f)
        assertEquals("Boston", reloaded.displayOf("boston"))
        assertNull(reloaded.displayOf("paris"))
        reloaded.forget("Boston")
        assertNull(reloaded.displayOf("boston"))
        reloaded.save()
        assertNull(UserLexicon(f).displayOf("boston"))
    }

    // ---- lower case is an incumbent too (#154) ----

    @Test
    fun oneShiftedSightingDoesNotFlipAWellWornLowerCaseWord() {
        val lexicon = UserLexicon(null)
        repeat(5) { lexicon.learnWord("keyboard", caseEvidence = true) }
        lexicon.learnWord("Keyboard", caseEvidence = true)
        assertNull(lexicon.displayOf("keyboard"))
        // As many capitals as the lower case had, and it turns.
        repeat(5) { lexicon.learnWord("Keyboard", caseEvidence = true) }
        assertEquals("Keyboard", lexicon.displayOf("keyboard"))
    }

    @Test
    fun aWordLearnedInLowerCaseWithoutVotesStillResistsOneCapital() {
        val lexicon = UserLexicon(null)
        // Swipes cast no case vote; the word's count stands in for them.
        repeat(20) { lexicon.learnWord("keyboard") }
        lexicon.learnWord("Keyboard", caseEvidence = true)
        assertNull(lexicon.displayOf("keyboard"))
        repeat(7) { lexicon.learnWord("Keyboard", caseEvidence = true) }
        assertEquals("Keyboard", lexicon.displayOf("keyboard"))
    }

    @Test
    fun aNewWordStillTakesItsCapitalOnTheFirstSighting() {
        val lexicon = UserLexicon(null)
        lexicon.learnWord("Zorbek", caseEvidence = true)
        assertEquals("Zorbek", lexicon.displayOf("zorbek"))
    }

    @Test
    fun aWordlistWordIsNotNewJustBecauseItWasNeverLearned() {
        val lexicon = UserLexicon(null)
        // "the" is in every English list: one shifted glide learns the word,
        // not the capital.
        lexicon.learnWord("The", caseEvidence = true, listedInLowerCase = true)
        assertTrue(lexicon.contains("the"))
        assertNull(lexicon.displayOf("the"))
        // It is still a vote, and enough of them turn it like any other word.
        repeat(7) { lexicon.learnWord("The", caseEvidence = true, listedInLowerCase = true) }
        assertEquals("The", lexicon.displayOf("the"))
    }

    @Test
    fun lowerCaseVotesSurviveASave() {
        val f = file()
        val lexicon = UserLexicon(f)
        repeat(5) { lexicon.learnWord("keyboard", caseEvidence = true) }
        lexicon.save()
        val back = UserLexicon(f)
        repeat(4) { back.learnWord("Keyboard", caseEvidence = true) }
        assertNull(back.displayOf("keyboard"))
    }

    // ---- added by hand (#164) ----

    @Test
    fun aWordAddedByHandStartsAtOneUseAndIsStillShielded() {
        val lexicon = UserLexicon(null)
        lexicon.addWord("zorbek")
        assertEquals(1, lexicon.frequencyOf("zorbek"))
        assertTrue(lexicon.isAddedByHand("zorbek"))
        assertTrue(lexicon.isEstablished("zorbek", minCount = 5))
        lexicon.learnWord("organic")
        assertFalse(lexicon.isAddedByHand("organic"))
        assertFalse(lexicon.isEstablished("organic", minCount = 5))
        // It earns weight like any other word.
        lexicon.learnWord("zorbek")
        assertEquals(2, lexicon.frequencyOf("zorbek"))
    }

    @Test
    fun addedByHandSurvivesSaveRenameAndForget() {
        val f = file()
        val lexicon = UserLexicon(f)
        lexicon.addWord("zorbek")
        lexicon.save()
        val back = UserLexicon(f)
        assertTrue(back.isAddedByHand("zorbek"))
        assertTrue(back.rename("zorbek", "zorbeck"))
        assertTrue(back.isAddedByHand("zorbeck"))
        assertFalse(back.isAddedByHand("zorbek"))
        back.forget("zorbeck")
        assertFalse(back.isAddedByHand("zorbeck"))
    }

    @Test
    fun addedGenerationOrdersWordsByWhenTheyJoined() {
        val f = file()
        UserLexicon(f).apply {
            learnWord("older", 2)
            save()
        }
        UserLexicon(f).apply {
            addWord("newer")
            // Typing an old word again does not make it a new one (#194).
            learnWord("older", 1)
            save()
        }
        val back = UserLexicon(f)
        assertTrue(back.addedGeneration("newer")!! > back.addedGeneration("older")!!)
        assertNull(back.addedGeneration("missing"))
    }

    @Test
    fun renameKeepsTheOlderAddedGeneration() {
        val lex = UserLexicon(file())
        lex.learnWord("teh", 2)
        lex.save()
        lex.addWord("fresh")
        lex.save()
        val old = lex.addedGeneration("teh")!!
        assertTrue(lex.addedGeneration("fresh")!! > old)
        assertTrue(lex.rename("teh", "tea"))
        assertEquals(old, lex.addedGeneration("tea"))
        assertNull(lex.addedGeneration("teh"))
        // A merge keeps the older of the two.
        assertTrue(lex.rename("fresh", "tea"))
        assertEquals(old, lex.addedGeneration("tea"))
        lex.forget("tea")
        assertNull(lex.addedGeneration("tea"))
    }

    @Test
    fun legacyFileReadsAddedGenerationFromLastUse() {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText("""{"words":{"aa":1,"bb":1},"generation":9,"wordGen":{"bb":4}}""")
        val lex = UserLexicon(f)
        assertEquals(4L, lex.addedGeneration("bb"))
        assertEquals(9L, lex.addedGeneration("aa"))
    }

    // ---- the gate on what is learned unasked (#185) ----

    @Test
    fun learningRefusesAWordWithASymbolGluedOn() {
        val lexicon = UserLexicon(null)
        assertFalse(lexicon.learnWord("manager\""))
        assertFalse(lexicon.learnWord("man\"ager"))
        assertFalse(lexicon.contains("manager\""))
        assertTrue(lexicon.learnWord("manager"))
        assertTrue(lexicon.contains("manager"))
    }

    @Test
    fun addingByHandTakesWhateverTheUserSpelled() {
        // The deliberate path is not gated: an oddity the user typed into a
        // dialog is theirs to keep.
        val lexicon = UserLexicon(null)
        lexicon.addWord("c++")
        assertTrue(lexicon.contains("c++"))
        assertTrue(lexicon.isAddedByHand("c++"))
    }

    @Test
    fun loadingDropsJunkThatGotInBeforeTheGateButKeepsHandAddedWords() {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(
            """{"words":{"manager\"":3,"hello":2,"c++":1,"old\"":250},
               "addedByHand":["c++"],
               "bigrams":{"hello":{"manager\"":2,"world":1},"manager\"":{"hello":1}},
               "trigrams":{"hello\u0000world":{"manager\"":1,"again":1}}}"""
        )
        val lexicon = UserLexicon(f)
        assertFalse("learned unasked, dropped", lexicon.contains("manager\""))
        assertTrue("a plain word stays", lexicon.contains("hello"))
        assertTrue("added by hand, kept whatever it looks like", lexicon.contains("c++"))
        assertTrue("the old 200 boost was the by-hand marker, kept too", lexicon.contains("old\""))
        assertEquals("and its n-grams go with it", listOf("world"), lexicon.nextWords("hello", 3))
        assertEquals(0, lexicon.bigramCount("manager\"", "hello"))
        assertEquals(listOf("again"), lexicon.nextWordsAfter("hello", "world", 3))
        // The cleanup is a change to persist: the next save must write it.
        lexicon.save()
        assertFalse(f.readText().contains("manager\\\""))
        assertTrue(f.readText().contains("c++"))
    }

    /** #620: "I" and "a" are words, and the pairs they start are learned. */
    @Test
    fun aOneLetterWordIsLearnedWithItsPairs() {
        val lexicon = UserLexicon(null)
        assertTrue(lexicon.learnWord("I"))
        assertTrue(lexicon.learnWord("a"))
        assertTrue(lexicon.contains("i"))
        assertTrue(lexicon.contains("a"))
        lexicon.learnBigram("I", "think")
        assertEquals(listOf("think"), lexicon.nextWords("i", 3))
        assertFalse(lexicon.learnWord(""))
    }
}
