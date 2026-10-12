package com.wasimaster.wmkeyboard.core.prediction

/**
 * Fixes contractions typed without their apostrophe: arent → aren't,
 * im → I'm, isnt → isn't. Applied at word-commit time (space) when the
 * "Fix missing apostrophes" setting is on, before autocorrect gets a look.
 *
 * The table deliberately excludes every apostrophe-less form that is a
 * real English word in its own right — its, were, well, id, hell,
 * shell, wed, shed, lets, whore, hes-vs-hers style near-misses aside —
 * so the fix never rewrites something the user may have meant literally.
 * "cant" and "wont" are technically words (a cant, wont to do) but are
 * rare enough that every mainstream keyboard corrects them anyway, and
 * "ill" joins them for the same reason (#482).
 */
object Apostrophes {

    private val CONTRACTIONS: Map<String, String> = mapOf(
        // to be / negations
        "aint" to "ain't",
        "arent" to "aren't",
        "isnt" to "isn't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        // auxiliaries + not
        "cant" to "can't",
        "couldnt" to "couldn't",
        "didnt" to "didn't",
        "doesnt" to "doesn't",
        "dont" to "don't",
        "hadnt" to "hadn't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "mightnt" to "mightn't",
        "mustnt" to "mustn't",
        "neednt" to "needn't",
        "shant" to "shan't",
        "shouldnt" to "shouldn't",
        "wont" to "won't",
        "wouldnt" to "wouldn't",
        // auxiliary + have
        "couldve" to "could've",
        "mightve" to "might've",
        "mustve" to "must've",
        "shouldve" to "should've",
        "wouldve" to "would've",
        // I. The apostrophe-less slips, and — because the word lists are
        // lowercase throughout — the correctly spelled forms too, which carry
        // the same lone capital "i" does and reach it nowhere else.
        "im" to "I'm",
        "ive" to "I've",
        // A word too, but typed for I'll far more often than for ill, and one
        // backspace puts it back — an undone repair is only offered after
        // that (#482). Offering it behind the typed word (#384) was not
        // enough: the slip is typed at speed and the strip goes unread.
        "ill" to "I'll",
        "i'm" to "I'm",
        "i've" to "I've",
        "i'll" to "I'll",
        "i'd" to "I'd",
        // he / she / it / that
        "hed" to "he'd",
        "hes" to "he's",
        "shes" to "she's",
        "itd" to "it'd",
        "itll" to "it'll",
        "thatd" to "that'd",
        "thatll" to "that'll",
        "thats" to "that's",
        // they / we / you
        "theyd" to "they'd",
        "theyll" to "they'll",
        "theyre" to "they're",
        "theyve" to "they've",
        "weve" to "we've",
        "youd" to "you'd",
        "youll" to "you'll",
        "youre" to "you're",
        "youve" to "you've",
        "yall" to "y'all",
        // question words
        "whatd" to "what'd",
        "whatll" to "what'll",
        "whatre" to "what're",
        "whats" to "what's",
        "whatve" to "what've",
        "whens" to "when's",
        "whered" to "where'd",
        "wheres" to "where's",
        "whod" to "who'd",
        "wholl" to "who'll",
        "whos" to "who's",
        "whove" to "who've",
        "whyd" to "why'd",
        "whys" to "why's",
        "howd" to "how'd",
        "hows" to "how's",
        // there / here
        "heres" to "here's",
        "theres" to "there's",
        // odds and ends
        "cmon" to "c'mon",
        "maam" to "ma'am",
        "oclock" to "o'clock",
        // Not a contraction, but the same class of slip. Autocorrect also
        // capitalizes it now, from the bundled capitals (#620), but only with
        // autocorrect on; this keeps it fixed with autocorrect off.
        "i" to "I",
    )

    /**
     * The forms [CONTRACTIONS] refuses, because each is also a real word: its,
     * were, well, id, hell, shell, wed, shed, lets, whore.
     *
     * Guessing at these is what [fix] must never do. Applying them when the user
     * *drew the apostrophe* — a glide through the key that
     * `GestureSettings.apostropheKey` names — is a different question with a
     * different answer, because the stroke said which word was meant. So
     * [fixExplicit] applies this table, and [offer] only ever shows it.
     */
    private val DECLARED: Map<String, String> = mapOf(
        "id" to "I'd",
        "its" to "it's",
        "hell" to "he'll",
        "lets" to "let's",
        "shed" to "she'd",
        "shell" to "she'll",
        "wed" to "we'd",
        "well" to "we'll",
        "were" to "we're",
    )

    /**
     * German's *es* worn down to *'s* on the verb in front of it: `gehts` is
     * *geht's*, `gibts` is *gibt's*, `habs` is *hab's* (#518).
     *
     * Every list holds the fused spelling, and far more often than the one
     * with the apostrophe (`gehts` 4,057 against `geht's` 145 in the German
     * list), so the lists cannot ask for the repair any more than they can in
     * English. Swept against that list: no spelling here is a German word of
     * its own. Left out for that reason: *wies* (of weisen), *nichts*,
     * *ichs* (the ego, plural), *sos*, *ers*, *obs*. Left out because they are
     * written without the apostrophe as a rule: *aufs*, *ins*, *ans*, *ums*,
     * *fürs*, *durchs*, *vors*, *übers*.
     */
    private val GERMAN: Map<String, String> = listOf(
        "bin", "bleibt", "braucht", "fehlt", "gab", "gefällt", "geht", "gibt",
        "ging", "hab", "hat", "hilft", "hört", "ist", "kann", "klappt", "kommt",
        "kriegt", "läuft", "lohnt", "mach", "macht", "mag", "nimmt", "passt",
        "regnet", "reicht", "sag", "sieht", "soll", "steht", "stimmt", "tut", "versuch",
        "war", "wär", "will", "wird", "zeig",
    ).associate { verb -> verb + "s" to "$verb's" }

    /** The table [languageId] repairs from, or null for a language with none. */
    private fun tableFor(languageId: String): Map<String, String>? =
        when (languageId.substringBefore('-').substringBefore('_')) {
            "en" -> CONTRACTIONS
            "de" -> GERMAN
            else -> null
        }

    /**
     * Whether [languageId] has a table here: English and German.
     *
     * Each table is one language's grammar spelled out. Asked by
     * [SuggestionEngine] the way [Elisions.rulesFor] is, so the two apostrophe
     * routes are chosen in one place and a language reaches at most one of
     * them.
     */
    fun servesLanguage(languageId: String): Boolean = tableFor(languageId) != null

    /**
     * The corrected word, or null when [word] needs no fixing. The typed
     * capitalization is preserved: Dont → Don't, ARENT → AREN'T; words the
     * fix itself capitalizes (im → I'm) stay capitalized regardless.
     */
    fun fix(word: String): String? = fix(word, CONTRACTIONS)

    /** [fix] from [languageId]'s own table: `gehts` → *geht's* in German. */
    fun fix(word: String, languageId: String): String? =
        tableFor(languageId)?.let { fix(word, it) }

    /**
     * The contraction [word] spells when the user has said an apostrophe belongs
     * in it, or null when there is no such spelling.
     *
     * Reads [DECLARED] first and [CONTRACTIONS] second, so a declared
     * apostrophe reaches both the ambiguous forms and the ordinary ones. That
     * second half matters when "Fix missing apostrophes" is off: turning the
     * automatic repair off is a statement about guessing, not about a stroke the
     * user aimed through the apostrophe key.
     */
    fun fixExplicit(word: String): String? =
        fix(word, DECLARED) ?: fix(word, CONTRACTIONS)

    /**
     * The contraction [word] may have been typed for, when it is also a word
     * in its own right: id → I'd, were → we're. Null for every
     * other word.
     *
     * For the suggestion strip only, never a commit: the typed spelling is as
     * likely to be meant as the contraction, so the strip offers the
     * contraction next to it and the space bar leaves the word alone (#384).
     */
    fun offer(word: String): String? = fix(word, DECLARED)

    /** [offer] for [languageId]: only English has spellings that are also words. */
    fun offer(word: String, languageId: String): String? =
        if (tableFor(languageId) === CONTRACTIONS) offer(word) else null

    /**
     * Every spelling either table can hand back.
     *
     * A repair commits directly, ahead of autocorrect, so each of these has to
     * be a word the English list knows — otherwise the fix lands on an unknown
     * word that the next commit corrects straight back (#128). Nothing in the
     * app reads this; `EnglishApostropheEntriesTest` holds the two in step.
     */
    /**
     * Every spelling [fix] repairs, with what it repairs it to: `doesnt` →
     * `doesn't`. The ambiguous forms [offer] shows are not among them.
     */
    fun repairs(): Map<String, String> = CONTRACTIONS

    /** [repairs] for [languageId]; empty for a language with no table. */
    fun repairs(languageId: String): Map<String, String> = tableFor(languageId).orEmpty()

    fun everyFix(): List<String> = (CONTRACTIONS.values + DECLARED.values).distinct()

    private fun fix(word: String, table: Map<String, String>): String? {
        val fixed = table[word.lowercase()] ?: return null
        val result = when {
            word.codePointCount(0, word.length) > 1 && lettersAllUpper(word) -> fixed.uppercase()
            startsUpperCase(word) -> capitalizeFirst(fixed)
            else -> fixed
        }
        return if (result == word) null else result
    }
}
