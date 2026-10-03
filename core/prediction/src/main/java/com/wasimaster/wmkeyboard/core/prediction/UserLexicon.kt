package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The user's personal vocabulary: words they have typed and the bigrams,
 * trigrams and 1-skip bigrams between them, used for personalized
 * completion, next-word prediction and the context reranker.
 *
 * Persisted as JSON in app-private storage. All typing data stays on
 * device; [clear] wipes it for the privacy setting.
 */
class UserLexicon(private val storageFile: File?) {

    @Serializable
    private data class Snapshot(
        val words: Map<String, Int> = emptyMap(),
        val bigrams: Map<String, Map<String, Int>> = emptyMap(),
        /** Save-session counter — the decay clock. Old files default to 0. */
        val generation: Long = 0L,
        /** Per-word last-touched generation, for lazy decay at compaction. */
        val wordGen: Map<String, Long> = emptyMap(),
        /** Trigram contexts, keyed "prev2<NUL>prev1". Additive; old files
         * simply have none. */
        val trigrams: Map<String, Map<String, Int>> = emptyMap(),
        /** 1-skip bigrams: the word two back -> the word that followed,
         * whatever stood between them (#195). Named by words skipped, the
         * k-skip-n-gram convention. Additive; old files have none. */
        val skip1grams: Map<String, Map<String, Int>> = emptyMap(),
        /** 2-skip bigrams: the word three back -> the word that
         * followed, across two middle words (#195). Additive — but see
         * [skipSchema]: a file from before the naming settled holds the
         * 1-skip store under this key. */
        val skip2grams: Map<String, Map<String, Int>> = emptyMap(),
        /**
         * Which naming the skip stores were written under. 0 (absent) is the
         * first shipped naming, by positions back: "skip2grams" held the 1-skip
         * store and "skip3grams" the 2-skip one. [SKIP_SCHEMA] counts words
         * skipped, as the literature does, and the reporter of #195 did.
         */
        val skipSchema: Int = 0,
        /** The 2-skip store as files under schema 0 wrote it; never written now. */
        @SerialName("skip3grams")
        val legacySkip3grams: Map<String, Map<String, Int>> = emptyMap(),
        /** Language id each word was last learned under. Additive; words
         * with no entry (legacy files, settings-app adds) are untagged and
         * treated as belonging to every language. */
        val wordLang: Map<String, String> = emptyMap(),
        /** Surface spelling for words the user writes with capitals ("boston"
         * -> "Boston"). Only words whose spelling differs from their key have
         * an entry; everything else is written the way it is stored. */
        val wordCase: Map<String, String> = emptyMap(),
        /** How much evidence stands behind each [wordCase] entry, so a
         * spelling the user has moved on from can be voted back out. */
        val caseVotes: Map<String, Int> = emptyMap(),
        /** Words whose spelling the user settled by hand and that no vote may
         * change (#100). Additive; old files pin nothing. */
        val casePinned: Set<String> = emptySet(),
        /** Words the user put in themselves — a dialog, the strip's Add, the
         * learn chip — as opposed to ones the keyboard picked up (#164).
         * Additive; in old files the 200 boost was the marker. */
        val addedByHand: Set<String> = emptySet(),
        /** Generation each word first joined the store, for the personal
         * dictionary screen's newest-first order (#194). Additive; a word with
         * no entry reads as its [wordGen]. */
        val wordBorn: Map<String, Long> = emptyMap(),
    )

    /** A word's followers plus a lazily cached count-descending order, so the
     * per-strip-refresh nextWords read stops re-sorting on every call. */
    private class Followers {
        val counts = HashMap<String, Int>()
        var sorted: List<String>? = null

        fun bump(next: String) {
            counts.merge(next, 1, Int::plus)
            sorted = null
            if (counts.size > MAX_FOLLOWERS) {
                counts.remove(counts.minByOrNull { it.value }?.key)
            }
        }

        /** Moves [old]'s count onto [new], adding to any count [new] already has. */
        fun rename(old: String, new: String) {
            val moved = counts.remove(old) ?: return
            counts.merge(new, moved, Int::plus)
            sorted = null
        }

        /** Folds [other]'s counts into this one, then re-applies the follower cap. */
        fun absorb(other: Followers) {
            for ((next, count) in other.counts) counts.merge(next, count, Int::plus)
            sorted = null
            while (counts.size > MAX_FOLLOWERS) {
                counts.remove(counts.minByOrNull { it.value }?.key)
            }
        }

        fun ordered(): List<String> = sorted ?: counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .also { sorted = it }

        fun total(): Int = counts.values.sum()
    }

    private var trie = Trie()
    private val words = HashMap<String, Int>()
    private val bigrams = HashMap<String, Followers>()
    private val trigrams = HashMap<String, Followers>()
    private val skip1grams = HashMap<String, Followers>()
    private val skip2grams = HashMap<String, Followers>()
    private val wordGen = HashMap<String, Long>()
    private val wordLangs = HashMap<String, String>()
    private val wordCase = HashMap<String, String>()
    private val caseVotes = HashMap<String, Int>()
    private val casePinned = HashSet<String>()
    private val addedByHand = HashSet<String>()
    private val wordBorn = HashMap<String, Long>()
    private var generation = 0L
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether anything has been learned since the file last matched memory.
     *
     * [save] runs on the main thread every time the keyboard is dismissed, and
     * it serialises every word and bigram the user has ever typed. Most
     * dismissals have nothing new to write — the keyboard came up, the user
     * tapped a suggestion or typed nothing at all, and it went away again — so
     * the flag turns those into a return rather than a re-encode and a rewrite
     * of the whole file. It is deliberately not a "save later" scheme: when
     * there *is* something new it is still written synchronously, before the
     * process can be killed with the user's new words only in memory.
     */
    private var dirty = false
    private val snapshotFile = storageFile?.let(::SnapshotFile)

    /**
     * Bumped whenever the word set changes, so the engine's cached walk
     * results can key on it. Volatile: read lock-free on the suggestion
     * coroutine while learning happens on the main thread.
     */
    @Volatile
    private var mutations = 0L

    fun mutationCount(): Long = mutations

    init {
        load()
    }

    /**
     * [count] grades the strength of the signal: a suggestion the user
     * deliberately tapped teaches harder than a word that merely got
     * committed in passing.
     *
     * Returns whether the word was taken. The automatic path ends here, so
     * this is where a word that is not a word is refused (#185) — see
     * [WordContext.isLearnableWord]; [addWord], the deliberate path, does not
     * ask, because a word the user typed into a dialog is theirs to spell.
     */
    @Synchronized
    fun learnWord(
        word: String,
        count: Int = 1,
        langId: String = "",
        caseEvidence: Boolean = false,
        listedInLowerCase: Boolean = false,
    ): Boolean {
        val key = WordKey.of(word)
        if (key.length < 2 || key.length > MAX_WORD_LENGTH || count <= 0) return false
        if (!WordContext.isLearnableWord(key)) return false
        // Only the caller knows whether the capital it is holding is the
        // user's or the keyboard's, so the vote is cast on its say-so (#44).
        if (caseEvidence) voteCase(key, WordKey.surface(word), weight = 1, listedInLowerCase)
        val before = words[key] ?: 0
        val merged = (before.toLong() + count).coerceAtMost(MAX_COUNT.toLong()).toInt()
        words[key] = merged
        // Reinforce by the clamped delta so the trie's copy can never race
        // past the cap (or overflow) while the word map stays clamped.
        trie.reinforce(key, merged - before)
        wordGen[key] = generation
        if (key !in wordBorn) wordBorn[key] = generation
        // Most-recent language wins: a word the user types under several
        // languages keeps flipping its tag, which is harmless — the engine
        // only damps a tag that *disagrees* with the active language, and a
        // genuinely shared word keeps being re-tagged to whatever is active.
        if (langId.isNotBlank()) wordLangs[key] = langId
        mutations++
        dirty = true
        return true
    }

    /**
     * Language id [word] was last learned under, or null for untagged words
     * (settings-app additions, legacy files) — untagged means "any language".
     */
    @Synchronized
    fun languageOf(word: String): String? = wordLangs[WordKey.of(word)]

    /**
     * The spelling [word] should be written in — "Boston" for a key of
     * "boston" — or null when the user writes it in lower case (#44).
     *
     * Everything the keyboard stores is keyed lower case, so a proper noun
     * came back off a swipe or a completion stripped of its capital and had
     * to be re-picked from the strip every time. This is the memory that puts
     * the capital back, and it is only ever fed by spellings the user chose
     * themselves: a capital that auto-capitalize put there says nothing about
     * how the word is spelled, so the callers that know the difference say so
     * (see `learnWord`'s `caseEvidence`).
     */
    @Synchronized
    fun displayOf(word: String): String? = wordCase[WordKey.of(word)]

    /**
     * One sighting of [surface] as the spelling of [key], worth [weight]
     * ordinary sightings.
     *
     * A streaming majority vote: one spelling and one counter per word. The
     * stored spelling is reinforced by a sighting that agrees with it and
     * worn down by one that does not, and a challenger takes the slot only
     * once the incumbent's evidence has run out. That costs two small map
     * entries per capitalized word — no per-spelling histogram — and it
     * converges on whichever casing the user actually writes, so a shouted
     * "SALE" or a stray shift is voted straight back out by ordinary typing.
     *
     * A [surface] equal to the key is a vote for "no capital at all", which is
     * why lower-case sightings are what wear a stale spelling down.
     *
     * Lower case is an incumbent like any other (#154). It used to hold no
     * votes — a word the user had written in lower case a hundred times had
     * nothing standing behind that, so one deliberate "Keyboard" flipped it
     * for good. Now a lower-case word's evidence is its own count, up to the
     * same ceiling a capital gets, and the challenger has to wear that down
     * first; a word never seen before still takes its capital on the first
     * sighting, since there is nothing to wear down. The count stands in
     * only until a vote is recorded, so the ordinary bookkeeping is one map
     * entry either way.
     *
     * "Never seen before" means never seen anywhere. A word a loaded wordlist
     * already spells in lower case ([listedInLowerCase]: "the", "keyboard")
     * is not new just because the personal dictionary has not counted it yet,
     * so its lower case starts with the full ceiling behind it; otherwise one
     * shifted "The" would be the first thing learned about "the" and every
     * later suggestion would carry the capital.
     *
     * A pinned word takes no votes at all: the user said how it is spelled
     * (#100), and a sentence-start capital or a shouted heading is not a
     * counter-argument.
     */
    private fun voteCase(
        key: String,
        surface: String,
        weight: Int,
        listedInLowerCase: Boolean = false,
    ) {
        if (weight <= 0 || WordKey.of(surface) != key || key in casePinned) return
        val shape = surface.takeIf { it != key }
        val current = wordCase[key]
        val standing = caseVotes[key] ?: when {
            current != null -> 0
            listedInLowerCase -> MAX_CASE_VOTES
            else -> lowerCaseEvidence(key)
        }
        if (shape == current) {
            caseVotes[key] = (standing + weight).coerceAtMost(MAX_CASE_VOTES)
            return
        }
        val left = standing - weight
        if (left > 0) {
            caseVotes[key] = left
            return
        }
        wordCase.remove(key)
        caseVotes.remove(key)
        // Whatever the challenger had left over after unseating the incumbent
        // becomes its own opening balance, so a decisive vote (a word added by
        // hand) does not arrive on one sighting's worth of evidence.
        if (shape != null) wordCase[key] = shape
        caseVotes[key] = (-left).coerceIn(1, MAX_CASE_VOTES)
    }

    /**
     * What stands behind a word's lower-case spelling before any vote has
     * been recorded for it: the times it was learned, capped like a vote.
     * Called before [learnWord] counts the sighting at hand, so a new word
     * starts from nothing.
     */
    private fun lowerCaseEvidence(key: String): Int =
        (words[key] ?: 0).coerceAtMost(MAX_CASE_VOTES)

    /** Settles [key]'s spelling outright, past whatever the vote held. */
    private fun setCase(key: String, surface: String) {
        if (WordKey.of(surface) != key) return
        if (surface == key) {
            wordCase.remove(key)
            caseVotes.remove(key)
        } else {
            wordCase[key] = surface
            caseVotes[key] = MAX_CASE_VOTES
        }
    }

    /**
     * User-added dictionary entry. Counted like a word typed [boost] times —
     * one, by default: adding a word says it is a word, not that it is a
     * frequent one, and the 200 it used to arrive with put every rare name
     * ahead of the common word it resembled (#164). It earns weight the
     * way every other word does, by being typed. What being added by hand
     * still buys is that the word is never "corrected" away, whatever the
     * learn-after threshold ([isEstablished]), and that compaction evicts it
     * last.
     *
     * [pinCase] keeps the spelling past every later vote (#100): a word the
     * user typed into a dialog, or chose off the strip, is spelled the way
     * they wrote it for as long as it is in the dictionary. It defaults to
     * following [caseEvidence], and is ignored without it — there is no
     * spelling to pin when the capital is not trusted.
     */
    @Synchronized
    fun addWord(
        word: String,
        boost: Int = 1,
        caseEvidence: Boolean = true,
        pinCase: Boolean = caseEvidence,
    ) {
        val trimmed = word.trim()
        val key = WordKey.of(trimmed)
        if (key.isEmpty() || key.length > MAX_WORD_LENGTH || boost <= 0) return
        // A word added by hand is spelled the way the user spelled it, so its
        // casing is settled outright rather than voted on.
        if (caseEvidence) {
            setCase(key, WordKey.surface(trimmed))
            if (pinCase) casePinned.add(key)
        }
        addedByHand.add(key)
        val before = words[key] ?: 0
        val merged = (before.toLong() + boost).coerceAtMost(MAX_COUNT.toLong()).toInt()
        words[key] = merged
        trie.reinforce(key, merged - before)
        wordGen[key] = generation
        if (key !in wordBorn) wordBorn[key] = generation
        mutations++
        dirty = true
    }

    /** Whether the user put [word] in themselves (#164); see [addWord]. */
    @Synchronized
    fun isAddedByHand(word: String): Boolean = WordKey.of(word) in addedByHand

    /**
     * When [word] first joined the store, as a save generation: a larger
     * number is a newer word (#194). It is an order, not a date. Words from
     * files written before this was kept read as the generation they were
     * last used in. Null for a word the store does not have.
     */
    @Synchronized
    fun addedGeneration(word: String): Long? = wordBorn[WordKey.of(word)]

    /**
     * Respells [word] as [replacement], keeping its count, language tag and
     * age, and carrying the word pairs and triples it took part in across
     * with it (personal dictionary screen, #47). Respelling onto a word that
     * already exists merges the two: counts add, clamped, and the existing
     * word keeps its own tag. Returns false, and touches nothing, when [word]
     * is unknown, when the new spelling is empty or too long, or when the two
     * spellings fold to the same key.
     */
    @Synchronized
    fun rename(word: String, replacement: String): Boolean {
        val oldKey = WordKey.of(word)
        val trimmed = replacement.trim()
        val newKey = WordKey.of(trimmed)
        if (newKey.isEmpty() || newKey.length > MAX_WORD_LENGTH) return false
        // A respelling that only moves capitals about ("boston" -> "Boston")
        // is not a rename at all — every store keys the word the same way —
        // so it settles the word's case memory instead (#44).
        if (oldKey == newKey) {
            val surface = WordKey.surface(trimmed)
            if (surface == (wordCase[oldKey] ?: oldKey) || oldKey !in words) return false
            // Respelled by hand, so the spelling is pinned as well as set (#100).
            setCase(oldKey, surface)
            casePinned.add(oldKey)
            mutations++
            dirty = true
            return true
        }
        val count = words.remove(oldKey) ?: return false
        val existing = words[newKey] ?: 0
        words[newKey] = (existing.toLong() + count).coerceAtMost(MAX_COUNT.toLong()).toInt()
        val oldGen = wordGen.remove(oldKey) ?: generation
        wordGen[newKey] = maxOf(oldGen, wordGen[newKey] ?: 0L)
        // The older age survives, so a respelling or a merge does not move
        // the word to the top of the newest-first order (#194).
        val oldBorn = wordBorn.remove(oldKey) ?: oldGen
        wordBorn[newKey] = minOf(oldBorn, wordBorn[newKey] ?: oldBorn)
        val oldLang = wordLangs.remove(oldKey)
        if (oldLang != null && newKey !in wordLangs) wordLangs[newKey] = oldLang
        // The new spelling is the user's own, so it settles the new key's case
        // outright; the old key's memory goes with the word it described.
        wordCase.remove(oldKey)
        caseVotes.remove(oldKey)
        casePinned.remove(oldKey)
        setCase(newKey, WordKey.surface(trimmed))
        casePinned.add(newKey)
        // Respelled by hand is added by hand: the user has vouched for the
        // new spelling the same way a dialog add would.
        addedByHand.remove(oldKey)
        addedByHand.add(newKey)
        // Pairs where the word led: its follower set moves under the new key.
        bigrams.remove(oldKey)?.let { moved ->
            val target = bigrams[newKey]
            if (target == null) bigrams[newKey] = moved else target.absorb(moved)
        }
        // Pairs where it followed: the count moves within each follower set.
        bigrams.values.forEach { it.rename(oldKey, newKey) }
        // Triples: rewrite every context the word is part of, merging on
        // collision, then move it within the follower sets too.
        val contexts = trigrams.keys.filter { key ->
            key.split(TRIGRAM_SEPARATOR).any { it == oldKey }
        }
        for (context in contexts) {
            val moved = trigrams.remove(context) ?: continue
            val rewritten = context.split(TRIGRAM_SEPARATOR)
                .joinToString(TRIGRAM_SEPARATOR.toString()) { if (it == oldKey) newKey else it }
            val target = trigrams[rewritten]
            if (target == null) trigrams[rewritten] = moved else target.absorb(moved)
        }
        trigrams.values.forEach { it.rename(oldKey, newKey) }
        // Gappy pairs: the same two moves as the bigrams.
        skip1grams.remove(oldKey)?.let { moved ->
            val target = skip1grams[newKey]
            if (target == null) skip1grams[newKey] = moved else target.absorb(moved)
        }
        skip1grams.values.forEach { it.rename(oldKey, newKey) }
        skip2grams.remove(oldKey)?.let { moved ->
            val target = skip2grams[newKey]
            if (target == null) skip2grams[newKey] = moved else target.absorb(moved)
        }
        skip2grams.values.forEach { it.rename(oldKey, newKey) }
        rebuildTrie()
        mutations++
        dirty = true
        return true
    }

    /**
     * Sets a known word's weight outright, clamped to `1..MAX_COUNT`, so the
     * personal dictionary screen can raise or lower how hard a word competes
     * (#47). Unknown words are left alone: use [addWord] for those. Lowering
     * a weight has to rebuild the trie, since its per-node upper bounds only
     * ever grow; this is a settings-app path, never the typing path.
     */
    @Synchronized
    fun setCount(word: String, count: Int): Boolean {
        val key = WordKey.of(word)
        if (key !in words) return false
        words[key] = count.coerceIn(1, MAX_COUNT)
        wordGen[key] = generation
        rebuildTrie()
        mutations++
        dirty = true
        return true
    }

    /** Whether [word]'s spelling is pinned against the case vote (#100). */
    @Synchronized
    fun isCasePinned(word: String): Boolean = WordKey.of(word) in casePinned

    /**
     * Pins or releases [word]'s current spelling (#100). Pinning settles
     * whatever the word is written as right now, so a later vote cannot move
     * it; releasing puts it back under the ordinary vote with full evidence,
     * so it is not flipped by the very next sighting either. Unknown words
     * are left alone. Returns whether anything changed.
     */
    @Synchronized
    fun pinCase(word: String, pinned: Boolean): Boolean {
        val key = WordKey.of(word)
        if (key !in words) return false
        val changed = if (pinned) casePinned.add(key) else casePinned.remove(key)
        if (!changed) return false
        if (wordCase[key] != null) caseVotes[key] = MAX_CASE_VOTES
        mutations++
        dirty = true
        return true
    }

    /**
     * Re-reads the storage file. The settings app edits the file directly
     * (personal dictionary screen); the IME calls this when signalled so
     * its in-memory copy doesn't clobber those edits on the next save.
     */
    @Synchronized
    fun reload() {
        snapshotFile?.supersede()
        words.clear()
        bigrams.clear()
        trigrams.clear()
        skip1grams.clear()
        skip2grams.clear()
        wordGen.clear()
        wordBorn.clear()
        wordLangs.clear()
        wordCase.clear()
        caseVotes.clear()
        casePinned.clear()
        addedByHand.clear()
        rebuildTrie()
        load()
        mutations++
        // Memory is the file again, so there is nothing outstanding to write —
        // and writing would clobber the settings-app edit this reload exists
        // to pick up.
        dirty = false
    }

    @Synchronized
    fun learnBigram(previous: String, next: String) {
        val prev = WordKey.of(previous)
        val nxt = WordKey.of(next)
        if (prev.isEmpty() || nxt.isEmpty()) return
        if (prev.length > MAX_WORD_LENGTH || nxt.length > MAX_WORD_LENGTH) return
        bigrams.getOrPut(prev) { Followers() }.bump(nxt)
        dirty = true
    }

    @Synchronized
    fun nextWords(previous: String, limit: Int): List<String> =
        bigrams[WordKey.of(previous)]?.ordered()?.take(limit).orEmpty()

    @Synchronized
    fun learnTrigram(prev2: String, prev1: String, next: String) {
        val a = WordKey.of(prev2)
        val b = WordKey.of(prev1)
        val c = WordKey.of(next)
        if (a.isEmpty() || b.isEmpty() || c.isEmpty()) return
        if (a.length > MAX_WORD_LENGTH || b.length > MAX_WORD_LENGTH ||
            c.length > MAX_WORD_LENGTH
        ) {
            return
        }
        trigrams.getOrPut(trigramKey(a, b)) { Followers() }.bump(c)
        dirty = true
    }

    /** Followers of the two-word context (prev2, prev1), best first. More
     * specific than [nextWords]; callers consult this first and fall back. */
    @Synchronized
    fun nextWordsAfter(prev2: String, prev1: String, limit: Int): List<String> =
        trigrams[trigramKey(WordKey.of(prev2), WordKey.of(prev1))]
            ?.ordered()?.take(limit).orEmpty()

    /** Learned count of ((prev2, prev1) -> next), 0 when never seen. */
    @Synchronized
    fun trigramCount(prev2: String, prev1: String, next: String): Int =
        trigrams[trigramKey(WordKey.of(prev2), WordKey.of(prev1))]
            ?.counts?.get(WordKey.of(next)) ?: 0

    /**
     * Learns the gappy pair (prev2 -> next): [prev2] stood two words before
     * [next], with anything at all in between (#195). Where a trigram splits
     * its counts across every middle word ("deploy the/a/my service"), this
     * pools them, and it is the only store that still speaks when the middle
     * word is one nothing recognises. Read by the context reranker alone,
     * never offered as a next word, so it carries no known-word gate of its own.
     */
    @Synchronized
    fun learnSkip1gram(prev2: String, next: String) {
        val prev = WordKey.of(prev2)
        val nxt = WordKey.of(next)
        if (prev.isEmpty() || nxt.isEmpty()) return
        if (prev.length > MAX_WORD_LENGTH || nxt.length > MAX_WORD_LENGTH) return
        skip1grams.getOrPut(prev) { Followers() }.bump(nxt)
        dirty = true
    }

    /** Learned count of the gappy pair (prev2 -> next), 0 when never seen. */
    @Synchronized
    fun skip1gramCount(prev2: String, next: String): Int =
        skip1grams[WordKey.of(prev2)]?.counts?.get(WordKey.of(next)) ?: 0

    /** Words that have followed [prev2] one word later, best first. */
    @Synchronized
    fun skip1Followers(prev2: String, limit: Int): List<String> =
        skip1grams[WordKey.of(prev2)]?.ordered()?.take(limit).orEmpty()

    /** Words that have followed [prev3] two words later, best first. */
    @Synchronized
    fun skip2Followers(prev3: String, limit: Int): List<String> =
        skip2grams[WordKey.of(prev3)]?.ordered()?.take(limit).orEmpty()

    /** Learns the pair (prev3 -> next), [prev3] three words before [next]
     * with any two words between — the long-range twin of [learnSkip1gram]. */
    @Synchronized
    fun learnSkip2gram(prev3: String, next: String) {
        val prev = WordKey.of(prev3)
        val nxt = WordKey.of(next)
        if (prev.isEmpty() || nxt.isEmpty()) return
        if (prev.length > MAX_WORD_LENGTH || nxt.length > MAX_WORD_LENGTH) return
        skip2grams.getOrPut(prev) { Followers() }.bump(nxt)
        dirty = true
    }

    /** Learned count of the pair (prev3 -> next), 0 when never seen. */
    @Synchronized
    fun skip2gramCount(prev3: String, next: String): Int =
        skip2grams[WordKey.of(prev3)]?.counts?.get(WordKey.of(next)) ?: 0

    /** Learned count of the pair (previous -> next), 0 when never seen. */
    @Synchronized
    fun bigramCount(previous: String, next: String): Int =
        bigrams[WordKey.of(previous)]?.counts?.get(WordKey.of(next)) ?: 0

    /** Defensive copy of a word's follower counts (capped at MAX_FOLLOWERS). */
    @Synchronized
    fun followerCounts(previous: String): Map<String, Int> =
        bigrams[WordKey.of(previous)]?.counts?.let(::HashMap).orEmpty()

    @Synchronized
    fun complete(prefix: String, limit: Int): List<Suggestion> = trie.complete(prefix, limit)

    /**
     * Walkers over the learned-word trie for the fuzzy beam search. The walker
     * escapes this monitor, so a walk concurrent with learning has the same
     * (pre-existing, benign) staleness hazard as [complete] — callers
     * invalidate their caches on lexicon change.
     */
    @Synchronized
    fun walkers(): List<TrieWalker> = trie.walkers()

    @Synchronized
    fun contains(word: String): Boolean = trie.contains(WordKey.of(word))

    /**
     * Whether the word has been seen often enough to be treated as one the
     * user really means, rather than one they typed once.
     *
     * The difference matters for autocorrect: a learned word is exempt from
     * correction, so at a threshold of 1 a single committed typo is
     * permanently protected. Suggestion ranking is unaffected either way, so a
     * word below the threshold can still be offered, just not shielded.
     */
    @Synchronized
    fun isEstablished(word: String, minCount: Int): Boolean {
        val key = WordKey.of(word)
        if (!trie.contains(key)) return false
        // The threshold guards against a typo learned in passing; a word the
        // user added outright was never in passing (#164).
        return minCount <= 1 || key in addedByHand || (words[key] ?: 0) >= minCount
    }

    @Synchronized
    fun frequencyOf(word: String): Int = trie.frequencyOf(WordKey.of(word))

    /**
     * Snapshot of all learned words with their counts, each in the spelling it
     * is written in — so the personal dictionary screen shows "Boston", not
     * the "boston" it is filed under. Every write-side call takes either form:
     * they all key through [WordKey].
     */
    /**
     * How many words this user has learned. [allWords] answers the same
     * question by building the whole list, which is fine for the personal
     * dictionary screen and far too much for a caller that only wants the
     * count — the sandbox ladder asks once a stroke.
     */
    @Synchronized
    fun wordCount(): Int = words.size

    @Synchronized
    fun allWords(): List<Pair<String, Int>> =
        words.map { (key, count) -> (wordCase[key] ?: key) to count }

    @Synchronized
    fun forget(word: String) {
        forgetAll(listOf(word))
    }

    /**
     * Drops every word in [victims] in one pass.
     *
     * The trie is append-only, so each removal rebuilds it; doing that once for
     * a thousand words rather than a thousand times is the difference between
     * the settings screen's "clean up" finishing and appearing to hang.
     */
    @Synchronized
    fun forgetAll(victims: Collection<String>) {
        val keys = victims.mapTo(HashSet()) { WordKey.of(it) }
        if (keys.isEmpty()) return
        for (key in keys) {
            words.remove(key)
            wordGen.remove(key)
            wordBorn.remove(key)
            wordLangs.remove(key)
            wordCase.remove(key)
            caseVotes.remove(key)
            casePinned.remove(key)
            addedByHand.remove(key)
            bigrams.remove(key)
            skip1grams.remove(key)
            skip2grams.remove(key)
        }
        bigrams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        skip1grams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        skip2grams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        trigrams.keys.removeAll { context ->
            context.split(TRIGRAM_SEPARATOR).any { it in keys }
        }
        trigrams.values.forEach { followers ->
            if (followers.counts.keys.removeAll(keys)) followers.sorted = null
        }
        rebuildTrie()
        mutations++
        dirty = true
    }

    @Synchronized
    fun clear() {
        words.clear()
        bigrams.clear()
        trigrams.clear()
        skip1grams.clear()
        skip2grams.clear()
        wordGen.clear()
        wordBorn.clear()
        wordLangs.clear()
        wordCase.clear()
        caseVotes.clear()
        casePinned.clear()
        addedByHand.clear()
        rebuildTrie()
        mutations++
        // The delete is the write, so there is normally nothing left to save.
        // If it failed, the file still holds the data this call was meant to
        // wipe — stay dirty so the next save overwrites it with the empty
        // snapshot, which is what the old unconditional save did.
        dirty = snapshotFile?.delete() == false
    }

    fun save() {
        val file = snapshotFile ?: return
        val (ticket, snapshot) = synchronized(this) {
            if (!dirty) return
            generation++
            compactIfNeeded()
            dirty = false
            file.ticket() to Snapshot(
                words = words.toMap(),
                bigrams = bigrams.mapValues { it.value.counts.toMap() },
                generation = generation,
                wordGen = wordGen.toMap(),
                trigrams = trigrams.mapValues { it.value.counts.toMap() },
                skip1grams = skip1grams.mapValues { it.value.counts.toMap() },
                skip2grams = skip2grams.mapValues { it.value.counts.toMap() },
                skipSchema = SKIP_SCHEMA,
                wordLang = wordLangs.toMap(),
                wordCase = wordCase.toMap(),
                caseVotes = caseVotes.toMap(),
                casePinned = casePinned.toSet(),
                addedByHand = addedByHand.toSet(),
                wordBorn = wordBorn.toMap(),
            )
        }
        // Encoded and written outside the lock, so the store stays usable while
        // the file goes to disk. A failed write makes the store dirty again, so
        // the next save retries rather than assuming it landed.
        if (!file.write(ticket) { json.encodeToString(snapshot) }) markUnsaved()
    }

    @Synchronized
    private fun markUnsaved() {
        dirty = true
    }

    private fun load() {
        val file = storageFile ?: return
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            words.putAll(snapshot.words)
            // What got in before the store had a gate (#185): a word with a
            // symbol glued on that some path learned unasked. Dropped once,
            // here, with its n-grams; a word the user put in by hand — the
            // marker set, or the 200 boost that was the marker before #164 —
            // is theirs and stays whatever it looks like.
            fun junk(word: String): Boolean =
                !WordContext.isLearnableWord(word) &&
                    word !in snapshot.addedByHand &&
                    (snapshot.words[word] ?: 0) < STICKY_MIN_COUNT
            if (words.keys.removeAll(::junk)) dirty = true
            snapshot.bigrams.forEach { (prev, map) ->
                if (junk(prev)) return@forEach
                bigrams[prev] = Followers().also { it.counts.putAll(map.filterKeys { !junk(it) }) }
            }
            snapshot.trigrams.forEach { (context, map) ->
                if (context.split(TRIGRAM_SEPARATOR).any(::junk)) return@forEach
                trigrams[context] = Followers().also { it.counts.putAll(map.filterKeys { !junk(it) }) }
            }
            // The skip stores under whichever naming the file was written
            // with; a schema-0 file is rewritten under the current one by
            // the next save, and nothing ever writes the old key again.
            val legacy = snapshot.skipSchema < SKIP_SCHEMA
            val oneSkip = if (legacy) snapshot.skip2grams else snapshot.skip1grams
            val twoSkip = if (legacy) snapshot.legacySkip3grams else snapshot.skip2grams
            if (legacy && (oneSkip.isNotEmpty() || twoSkip.isNotEmpty())) dirty = true
            oneSkip.forEach { (prev, map) ->
                if (junk(prev)) return@forEach
                skip1grams[prev] = Followers().also { it.counts.putAll(map.filterKeys { !junk(it) }) }
            }
            twoSkip.forEach { (prev, map) ->
                if (junk(prev)) return@forEach
                skip2grams[prev] = Followers().also { it.counts.putAll(map.filterKeys { !junk(it) }) }
            }
            generation = snapshot.generation
            // Words with no recorded generation (legacy files, settings-app
            // rewrites) are treated as fresh rather than instantly decayed;
            // orphaned entries for words no longer present are dropped.
            for (word in words.keys) {
                wordGen[word] = snapshot.wordGen[word] ?: snapshot.generation
                // Files from before #194 kept no join date; the last use is
                // the closest thing they have.
                wordBorn[word] = snapshot.wordBorn[word] ?: wordGen.getValue(word)
                snapshot.wordLang[word]?.let { wordLangs[word] = it }
                // Dropped if the file disagrees with itself: a spelling that
                // no longer folds to its own key would be written into text.
                val shape = snapshot.wordCase[word]
                    ?.takeIf { WordKey.of(it) == word && it != word }
                if (shape != null) {
                    wordCase[word] = shape
                    caseVotes[word] =
                        (snapshot.caseVotes[word] ?: 1).coerceIn(1, MAX_CASE_VOTES)
                } else {
                    // Votes recorded for the lower-case spelling itself (#154);
                    // absent, the word's count stands in for them.
                    snapshot.caseVotes[word]?.let { caseVotes[word] = it.coerceIn(1, MAX_CASE_VOTES) }
                }
                // A pin on a word no longer present is an orphan, dropped
                // like every other orphaned entry.
                if (word in snapshot.casePinned) casePinned.add(word)
                if (word in snapshot.addedByHand) addedByHand.add(word)
            }
            rebuildTrie()
        }
    }

    /** Trie is append-only, so removal rebuilds it from the word map. */
    private fun rebuildTrie() {
        val fresh = Trie()
        for ((word, count) in words) fresh.insert(word, count)
        trie = fresh
    }

    /**
     * Bounds the store, running only on a dirty save (dismissal-time, main
     * thread — the same moment that already rewrites the whole file). Words
     * are scored with lazy exponential decay — `count * 2^(-age/HALF_LIFE)`
     * with age in save-generations — so a year-old typo-learn finally loses
     * to anything the user still types, while raw counts are never rewritten
     * on the hot path. Deliberately user-added words ([addWord]; in files
     * from before #164, a count >= STICKY_MIN_COUNT) are evicted only after
     * every organic word is gone.
     */
    private fun compactIfNeeded() {
        if (words.size > MAX_WORDS) {
            fun score(word: String): Double {
                val age = (generation - (wordGen[word] ?: generation)).coerceAtLeast(0L)
                val halfLives = age.toDouble() / HALF_LIFE_GENERATIONS
                return (words[word] ?: 0) * Math.pow(2.0, -halfLives)
            }
            val evictable = words.keys
                .sortedWith(
                    compareBy(
                        { it in addedByHand || (words[it] ?: 0) >= STICKY_MIN_COUNT },
                        { score(it) },
                    )
                )
            val toEvict = evictable.take(words.size - EVICT_TO)
            for (word in toEvict) {
                words.remove(word)
                wordGen.remove(word)
                wordBorn.remove(word)
                wordLangs.remove(word)
                wordCase.remove(word)
                caseVotes.remove(word)
                casePinned.remove(word)
                addedByHand.remove(word)
                bigrams.remove(word)
                bigrams.values.forEach {
                    if (it.counts.remove(word) != null) it.sorted = null
                }
                skip1grams.remove(word)
                skip1grams.values.forEach {
                    if (it.counts.remove(word) != null) it.sorted = null
                }
                skip2grams.remove(word)
                skip2grams.values.forEach {
                    if (it.counts.remove(word) != null) it.sorted = null
                }
            }
            rebuildTrie()
            mutations++
        }
        if (bigrams.size > MAX_BIGRAM_PREVS) {
            val evictable = bigrams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(bigrams.size - MAX_BIGRAM_PREVS)) {
                bigrams.remove(entry.key)
            }
        }
        if (trigrams.size > MAX_TRIGRAM_CONTEXTS) {
            val evictable = trigrams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(trigrams.size - MAX_TRIGRAM_CONTEXTS)) {
                trigrams.remove(entry.key)
            }
        }
        if (skip1grams.size > MAX_SKIP1_HEADS) {
            val evictable = skip1grams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(skip1grams.size - MAX_SKIP1_HEADS)) {
                skip1grams.remove(entry.key)
            }
        }
        if (skip2grams.size > MAX_SKIP2_HEADS) {
            val evictable = skip2grams.entries.sortedBy { it.value.total() }
            for (entry in evictable.take(skip2grams.size - MAX_SKIP2_HEADS)) {
                skip2grams.remove(entry.key)
            }
        }
    }

    companion object {
        /** Longest word the store keeps, in folded characters. */
        const val MAX_WORD_LENGTH = 32
        /** Ceiling on a word's count. 1e6 x USER_WORD_WEIGHT(500) stays far
         * inside Int range. Public so the personal dictionary screen can
         * bound the weight it lets the user type. */
        const val MAX_COUNT = 1_000_000
        private const val MAX_WORDS = 10_000
        /** Eviction target below the cap: 10% hysteresis so compaction does
         * not churn on every save once the cap is reached. */
        private const val EVICT_TO = 9_000
        private const val MAX_BIGRAM_PREVS = 5_000
        private const val MAX_TRIGRAM_CONTEXTS = 2_000
        /** Heads of the gappy (prev2 -> next) store (#195). */
        /** See [Snapshot.skipSchema]. */
        private const val SKIP_SCHEMA = 1
        private const val MAX_SKIP1_HEADS = 2_000
        /** Heads of the (prev3 -> next) store; the issue's own budget for it. */
        private const val MAX_SKIP2_HEADS = 1_000
        private const val MAX_FOLLOWERS = 32

        /** NUL, built rather than written literally. */
        private val TRIGRAM_SEPARATOR: Char = 0.toChar()

        private fun trigramKey(prev2: String, prev1: String): String =
            prev2 + TRIGRAM_SEPARATOR + prev1
        private const val HALF_LIFE_GENERATIONS = 64.0
        /** addWord's boost landed at 200 before #164; organic words rarely
         * reach this, so it doubled as the "deliberately added" marker and
         * still reads that way for files written back then. */
        private const val STICKY_MIN_COUNT = 100

        /**
         * Ceiling on a spelling's evidence. Low on purpose: a capitalization
         * the user has stopped writing should be gone within a handful of
         * words, not defended by a count built up over months.
         */
        private const val MAX_CASE_VOTES = 8
    }
}
