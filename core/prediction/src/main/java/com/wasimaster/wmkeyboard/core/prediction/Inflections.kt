package com.wasimaster.wmkeyboard.core.prediction

/**
 * English verb forms, read from the rest of the verb (#395).
 *
 * The bundled English list is a ranked subset of the language, and it holds
 * `corrected` without `corrects` or `correcting`, `fixed` without `fixes` —
 * hundreds of verbs with a form that never made the cut. Unknown means
 * correctable, and the cheapest fix for `corrects` is the one-letter deletion
 * to `correct`, so "the autocorrect corrects" lost its `s` behind the user's
 * back.
 *
 * A spelling is such a verb form when taking off the ending leaves a listed
 * word that the list also knows as a verb, because it holds another of its
 * forms. That witness is what keeps a typo that happens to end like an
 * inflection correctable: `thes` leaves `the`, which is listed but has no
 * `theed` or `theing`. For `-ed` and `-ing` the witness has to be the other
 * participle, never a plural, since nouns take `-s` too: `beds` says nothing
 * about `beding`.
 *
 * Just as important is what this must not protect: a misspelling of a form
 * the list does hold. So nothing is vouched for when any correct spelling of
 * the same form is listed (`admited` beside `admitted`, `fixs` beside
 * `fixes`), when a short verb left out the consonant it doubles (`stoped`),
 * or when the verb is irregular and has no `-ed` at all (`begined`).
 */
internal object Inflections {

    /** Shortest stem worth reading as a verb; `hes`, `ifs` are not. */
    private const val MIN_STEM = 3

    /**
     * Whether [lower] is the `-s`, `-ed` or `-ing` form of a verb [known]
     * holds in its other forms. [known] answers for lower-case spellings.
     */
    fun isVerbForm(lower: String, known: (String) -> Boolean): Boolean {
        val n = lower.length
        // Cheap string tests first: this runs on every unknown word a space
        // commits, and almost none of them get past here.
        if (n <= MIN_STEM) return false
        return when (lower[n - 1]) {
            's' -> lower[n - 2] != 's' && lettersOnly(lower) && isThirdPerson(lower, n, known)
            'd' -> n > MIN_STEM + 1 && lower[n - 2] == 'e' && lettersOnly(lower) && isPast(lower, n, known)
            'g' -> n > MIN_STEM + 2 && lower.endsWith("ing") && lettersOnly(lower) && isParticiple(lower, n, known)
            else -> false
        }
    }

    private fun lettersOnly(lower: String): Boolean {
        for (ch in lower) if (ch !in 'a'..'z') return false
        return true
    }

    /** walks, fixes, tries. */
    private fun isThirdPerson(lower: String, n: Int, known: (String) -> Boolean): Boolean {
        fun fits(stem: String) = stem.length >= MIN_STEM && known(stem) &&
            !hasThirdPerson(stem, known) && (hasPast(stem, known) || hasParticiple(stem, known))
        val s = lower.substring(0, n - 1)
        // Plain `-s` only where English writes it: not `fixs`, not `trys`.
        if (!takesEs(s) && !endsConsonantY(s) && fits(s)) return true
        if (lower[n - 2] != 'e') return false
        val es = lower.substring(0, n - 2)
        if (takesEs(es) && fits(es)) return true
        return lower[n - 3] == 'i' && fits(lower.substring(0, n - 3) + "y")
    }

    /** walked, hoped, stopped, tried. */
    private fun isPast(lower: String, n: Int, known: (String) -> Boolean): Boolean {
        fun fits(stem: String, doubled: Boolean) = stem.length >= MIN_STEM && known(stem) &&
            stem !in IRREGULAR && (doubled || !mustDouble(stem)) &&
            !hasPast(stem, known) && hasParticiple(stem, known)
        val ed = lower.substring(0, n - 2)
        // Plain `-ed` only where English writes it: not `hopeed`, not `tryed`.
        if (ed.last() != 'e' && !endsConsonantY(ed) && fits(ed, doubled = false)) return true
        if (fits(lower.substring(0, n - 1), doubled = false)) return true
        if (undoubled(ed)?.let { fits(it, doubled = true) } == true) return true
        return ed.last() == 'i' && fits(ed.substring(0, ed.length - 1) + "y", doubled = false)
    }

    /** walking, hoping, stopping, trying. */
    private fun isParticiple(lower: String, n: Int, known: (String) -> Boolean): Boolean {
        fun fits(stem: String, doubled: Boolean) = stem.length >= MIN_STEM && known(stem) &&
            (doubled || !mustDouble(stem)) && !hasParticiple(stem, known) && hasPast(stem, known)
        val ing = lower.substring(0, n - 3)
        // A silent e drops before `-ing` (hoping), a doubled one stays
        // (agreeing, seeing): `hopeing` is no spelling of anything.
        if (!dropsE(ing) && fits(ing, doubled = false)) return true
        if (ing.last() !in VOWELS && fits(ing + "e", doubled = false)) return true
        return undoubled(ing)?.let { fits(it, doubled = true) } == true
    }

    private fun hasThirdPerson(stem: String, known: (String) -> Boolean): Boolean =
        known(stem + "s") || known(stem + "es") ||
            (stem.last() == 'y' && known(stem.substring(0, stem.length - 1) + "ies"))

    private fun hasPast(stem: String, known: (String) -> Boolean): Boolean {
        val last = stem.last()
        return known(stem + "ed") ||
            (last == 'e' && known(stem + "d")) ||
            (last == 'y' && known(stem.substring(0, stem.length - 1) + "ied")) ||
            (last !in VOWELS && known(stem + last + "ed"))
    }

    private fun hasParticiple(stem: String, known: (String) -> Boolean): Boolean {
        val last = stem.last()
        return known(stem + "ing") ||
            (last == 'e' && known(stem.substring(0, stem.length - 1) + "ing")) ||
            (last !in VOWELS && known(stem + last + "ing"))
    }

    /**
     * Whether [stem] is one syllable ending consonant-vowel-consonant, the
     * shape that always doubles before an ending: stop, beg, plan. Longer
     * verbs double by stress (admit, but visit), which spelling cannot tell.
     */
    private fun mustDouble(stem: String): Boolean {
        val k = stem.length
        val last = stem[k - 1]
        if (last in VOWELS || last == 'w' || last == 'x') return false
        if (stem[k - 2] !in VOWELS || stem[k - 3] in VOWELS) return false
        for (i in 0 until k - 2) if (stem[i] in VOWELS) return false
        return true
    }

    /** `stopp` → `stop`: the consonant a short verb doubles before an ending. */
    private fun undoubled(stem: String): String? {
        val k = stem.length
        if (k < MIN_STEM + 1) return null
        val last = stem[k - 1]
        return if (last == stem[k - 2] && last !in VOWELS) stem.substring(0, k - 1) else null
    }

    private fun endsConsonantY(stem: String): Boolean =
        stem.length >= 2 && stem.last() == 'y' && stem[stem.length - 2] !in VOWELS

    private fun dropsE(stem: String): Boolean =
        stem.length >= 2 && stem.last() == 'e' && stem[stem.length - 2] !in "eoy"

    /** `-es` goes after a hiss or an o: fixes, watches, echoes. */
    private fun takesEs(stem: String): Boolean {
        if (stem.length < MIN_STEM) return false
        val last = stem.last()
        return last == 's' || last == 'x' || last == 'z' || last == 'o' ||
            stem.endsWith("ch") || stem.endsWith("sh")
    }

    private const val VOWELS = "aeiouy"

    /**
     * Irregular verbs of three letters or more, whose `-ed` is a misspelling
     * (`begined`, `thinked`): the list can hold their `-ing` without an `-ed`
     * to go with it, which is exactly the witness a regular verb gives.
     */
    private val IRREGULAR: Set<String> = hashSetOf(
        "arise", "awake", "bear", "beat", "become", "begin", "bend", "bet", "bind", "bite", "bleed",
        "blow", "break", "breed", "bring", "build", "burst", "buy", "cast", "catch", "choose", "cling",
        "come", "cost", "creep", "cut", "deal", "dig", "draw", "drink", "drive", "eat", "fall", "feed",
        "feel", "fight", "find", "flee", "fling", "fly", "forbid", "forget", "forgive", "freeze", "get",
        "give", "grind", "grow", "hang", "have", "hear", "hide", "hit", "hold", "hurt", "keep", "kneel",
        "know", "lay", "lead", "leave", "lend", "let", "lie", "light", "lose", "make", "mean", "meet",
        "mistake", "overcome", "pay", "put", "quit", "read", "ride", "ring", "rise", "run", "say", "see",
        "seek", "sell", "send", "set", "shake", "shed", "shine", "shoot", "shrink", "shut", "sing", "sink",
        "sit", "slay", "sleep", "slide", "sling", "slit", "speak", "speed", "spend", "spin", "spit",
        "split", "spread", "spring", "stand", "steal", "stick", "sting", "stink", "stride", "strike",
        "string", "strive", "swear", "sweep", "swim", "swing", "take", "teach", "tear", "tell", "think",
        "throw", "thrust", "tread", "understand", "undertake", "undo", "upset", "wake", "wear", "weave",
        "weep", "win", "wind", "withdraw", "wring", "write",
    )
}
