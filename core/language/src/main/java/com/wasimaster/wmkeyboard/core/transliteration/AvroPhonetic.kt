package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based Avro-style phonetic transliteration from romanized Bengali
 * ("banglish") to Bengali script.
 *
 * The engine is a greedy longest-match transliterator over a rule table,
 * with three pieces of context sensitivity:
 *
 *  1. Vowels render as independent letters at a word boundary or after
 *     another vowel, and as vowel signs (kar) after a consonant.
 *  2. Consecutive consonants conjunct only where Bangla has that conjunct
 *     ([JOINS], desktop Avro's own table): "kolkata" → কলকাতা, "bondhu" →
 *     বন্ধু, but "korbo" → করব and "bolchi" → বলছি. র-ফলা, য-ফলা and
 *     ব-ফলা ("kr", "ky", "kw") join any consonant, and ন before a velar or
 *     palatal takes that nasal ("onko" → অঙ্ক, "poncho" → পঞ্ছ). The
 *     inherent vowel "o" produces no glyph but breaks the cluster, so
 *     "kolokata" stays কলকাতা whatever the pair.
 *  3. Lowercase "o" is the inherent vowel: silent after a consonant, ও after
 *     a vowel that wrote something ("amio" → আমিও), অ anywhere else. It
 *     never writes ো — that is what the capital "O" is for, exactly as on
 *     desktop Avro, so "bhalo" is ভাল and "bhalO" is ভালো.
 *  4. "rr" spells reph: it renders as a single র that conjuncts with the
 *     following consonant ("dhorrmo" → ধর্ম), except in "rri" (ঋ) or when
 *     no consonant follows. A single "r" stays apart from the consonant
 *     after it ("korbo" → করব), as on desktop Avro; the reph words people
 *     spell with one r ("dhormo" → ধর্ম) are in the fixed-spelling list.
 *  5. An "a" right after a rendered vowel glides with য় ("kiamot" →
 *     কিয়ামত, "Oasi"/"wasi" → ওয়াসি). Only after the silent
 *     inherent vowel does the independent আ survive ("kuroan" → কুরআন),
 *     and capital "A" is always explicit আ.
 *  6. A backquote breaks the cluster it lands in the middle of, and writes
 *     nothing itself: "kos" conjuncts to ক্স, "k`s" stays কস. It is also how
 *     khanda-ta is spelled — "hoThat`" → হঠাৎ — since a ত with the join taken
 *     off it is exactly what ৎ is.
 *  7. A word-final hasant collapses the ত before it into ৎ, so the same word
 *     comes out of the explicit-hasant spelling too ("hoThat,," → হঠাৎ). Only
 *     ত, and only word-finally: anywhere else a hasant is a live join still
 *     waiting for its next consonant.
 *  8. "Z" is য-ফলা spelled outright — it writes ্য wherever it lands, for the
 *     positions where the "y" rule below would have gone to য় instead.
 *  9. "w" is ব-ফলা after a consonant ("swasthyo" → স্বাস্থ্য) and ও anywhere
 *     else, where the "a" after it glides ("wasi" → ওয়াসি).
 * 10. ং, ঃ and ঁ are signs, not consonants: they hang off the syllable in
 *     front of them and join nothing, so "bangla" is বাংলা and not বাং্লা.
 *     Since only a consonant can start the next syllable, "ng" before a
 *     vowel is ঙ্গ instead — "songe" → সঙ্গে — as on desktop Avro.
 * 11. A capital that spells nothing of its own reads as its lowercase self,
 *     so a stray shift types Bengali rather than dropping a Latin letter
 *     into the middle of a word. The capitals Avro *does* use (T D N S R J,
 *     the long vowels, OI/OU/Ng/NG/Th/Dh/Sh/Rh/TH/HH) are matched first and
 *     never reach that fallback.
 * 12. At a word start "y" is ইয় and "x" এক্স ("yes" → ইয়েস), "ee" is ঈ/ী
 *     anywhere ("kee" → কী), and "aZ" is অ্যা ("aZp" → অ্যাপ), all as on
 *     desktop Avro. A "w" not after a consonant glides into the vowel after
 *     it through য় ("we" → ওয়ে, "sawal" → সাওয়াল).
 *
 * Dictionary-level corrections (e.g. "asi" → আছি rather than আসি) are the
 * suggestion engine's job, not this transliterator's.
 */
object AvroPhonetic {

    private const val HASANT = '্'
    private const val TA = 'ত'
    private const val KHANDA_TA = 'ৎ'
    private const val TA_HASANT = "$TA$HASANT"

    /** Breaks a cluster without writing anything: "k`s" → কস, not ক্স. */
    private const val BREAKER = '`'

    /** Holds র and য-ফলা apart, so "ry" draws র‍্য (র‍্যাব) and not the reph of র্য. */
    private const val ZWJ = '\u200D'

    /**
     * The consonant pairs that conjunct when typed back to back, as
     * "first letter + second letter". Any other pair is written side by side,
     * which is what desktop Avro does: "korbo" is করব, "bolchi" is বলছি, and
     * the conjunct only appears where Bangla really has one ("kt" → ক্ত,
     * "ndh" → ন্ধ). The list is Avro's own conjunct table, pair by pair, and
     * it covers every conjunct common in the dictionary except the ত্স, ত্প
     * and ত্ক that modern spelling writes with ৎ anyway.
     *
     * র, য and ব as the *second* letter are not here: র-ফলা, য-ফলা and
     * ব-ফলা join any consonant ([joins], the "y" and "w" rules), and র as
     * the *first* letter only joins as reph, which is spelled "rr".
     */
    private val JOINS: Set<String> = hashSetOf(
        "কক", "কট", "কত", "কল", "কষ", "কস",
        "গণ", "গধ", "গন", "গম", "গল",
        "ঘন",
        "ঙক", "ঙখ", "ঙগ", "ঙঘ", "ঙম", "ঙষ",
        "চচ", "চছ", "চঞ",
        "জজ", "জঝ", "জঞ",
        "ঞচ", "ঞছ", "ঞজ", "ঞঝ",
        "টট", "টম",
        "ডড",
        "ণট", "ণঠ", "ণড", "ণঢ", "ণণ", "ণন", "ণম",
        "তত", "তথ", "তন", "তম",
        "দগ", "দঘ", "দদ", "দধ", "দভ", "দম",
        "ধন", "ধম",
        "নট", "নঠ", "নড", "নত", "নথ", "নদ", "নধ", "নন", "নম", "নস",
        "পট", "পত", "পন", "পপ", "পল", "পস",
        "ফল",
        "বজ", "বদ", "বধ", "বব", "বল",
        "ভল",
        "মথ", "মন", "মপ", "মফ", "মব", "মভ", "মম", "মল",
        "লক", "লগ", "লট", "লড", "লধ", "লপ", "লব", "লভ", "লম", "লল",
        "শচ", "শছ", "শত", "শন", "শম", "শল",
        "ষক", "ষট", "ষঠ", "ষণ", "ষপ", "ষফ", "ষম",
        "সক", "সখ", "সট", "সত", "সথ", "সন", "সপ", "সফ", "সম", "সল",
        "হণ", "হন", "হম", "হল",
        "\u09DCগ", // ড়্গ
    )

    /**
     * ন takes the nasal of the class it joins, as it does in writing:
     * "nk" → ঙ্ক (অঙ্ক), "nkh" → ঙ্খ (শঙ্খ), "nc" → ঞ্চ (অঞ্চল).
     */
    private val NASAL_BEFORE: Map<Char, Char> = mapOf(
        'ক' to 'ঙ', 'খ' to 'ঙ',
        'চ' to 'ঞ', 'ছ' to 'ঞ', 'জ' to 'ঞ', 'ঝ' to 'ঞ',
    )

    /** Roman letters after which an "r" is a plain র, never র-ফলা: "rr" is রর, "xr" এক্সর. */
    private const val NO_R_PHOLA_AFTER = "ryYwWxZ"

    /**
     * [SIGN] is ং / ঃ / ঁ: written like a consonant but joined like nothing.
     * No hasant is put in front of one and none is put after it, yet the
     * syllable it closes is still a syllable — so an inherent "o" after it
     * stays silent ("rongo" → রং) where after a digit or a full stop
     * ([OTHER]) that same "o" would be a fresh অ.
     */
    private enum class Kind { CONSONANT, VOWEL, SIGN, OTHER }

    /**
     * @param match the Avro phonetic token this rule fires on
     * @param full independent form (word start or after a vowel)
     * @param kar vowel-sign form used after a consonant; null means the rule
     *        is not a vowel
     * @param kind what [match] composes into; defaults off whether [kar] is set
     */
    private data class Rule(
        val match: String,
        val full: String,
        val kar: String? = null,
        val kind: Kind = if (kar != null) Kind.VOWEL else Kind.CONSONANT,
    )

    private val rules: List<Rule> = buildList {
        // Vowels — longest patterns first within same prefix handled by sort below.
        add(Rule("OI", "ঐ", "ৈ"))
        add(Rule("OU", "ঔ", "ৌ"))
        add(Rule("rri", "ঋ", "ৃ"))
        add(Rule("a", "আ", "া"))
        add(Rule("A", "আ", "া"))
        add(Rule("i", "ই", "ি"))
        add(Rule("I", "ঈ", "ী"))
        add(Rule("ee", "ঈ", "ী")) // "kee" → কী, "free" → ফ্রী, as on desktop Avro
        add(Rule("u", "উ", "ু"))
        add(Rule("U", "ঊ", "ূ"))
        add(Rule("e", "এ", "ে"))
        add(Rule("E", "এ", "ে"))
        add(Rule("O", "ও", "ো"))
        // "o" handled specially in transliterate().

        // Common fixed conjuncts spelled by digraph in Avro (longest match wins).
        add(Rule("kkh", "ক্ষ")) // লক্ষ, ক্ষমা — "kkh" is the conventional key
        add(Rule("kx", "ক্ষ")) // Avro's other spelling of it
        add(Rule("gg", "জ্ঞ")) // জ্ঞান, আজ্ঞা — the গ্গ it displaces has one word
        add(Rule("nj", "ঞ্জ")) // পাঞ্জাবি, অঞ্জন — an n before j is ঞ, never ন

        // Aspirated / two-letter consonants first (longest match wins).
        add(Rule("kh", "খ"))
        add(Rule("gh", "ঘ"))
        add(Rule("Ng", "ঙ"))
        add(Rule("ch", "ছ"))
        add(Rule("jh", "ঝ"))
        add(Rule("NG", "ঞ"))
        add(Rule("Th", "ঠ"))
        add(Rule("Dh", "ঢ"))
        add(Rule("th", "থ"))
        add(Rule("dh", "ধ"))
        add(Rule("ph", "ফ"))
        add(Rule("bh", "ভ"))
        add(Rule("sh", "শ"))
        add(Rule("Sh", "ষ"))
        add(Rule("Rh", "ঢ়"))
        // Signs, not consonants — see [Kind.SIGN]. "TH"/"HH"/"qq"/"cb" are the
        // spellings Ridmik users reach for; desktop Avro's own "t``", ":" and
        // "^" keep working alongside them.
        add(Rule("ng", "ং", null, Kind.SIGN))
        add(Rule("TH", KHANDA_TA.toString(), null, Kind.OTHER))
        add(Rule("HH", "ঃ", null, Kind.SIGN))
        add(Rule("qq", "ঁ", null, Kind.SIGN))
        add(Rule("cb", "ঁ", null, Kind.SIGN))

        // Single consonants.
        add(Rule("k", "ক"))
        // Bangla has no q of its own. Ridmik reads it as ক, and a Latin q
        // left in the buffer mid-word looks like the word was committed out
        // from under the typist; "qq" (ঁ) is longer and still matches first.
        add(Rule("q", "ক"))
        add(Rule("g", "গ"))
        add(Rule("c", "চ"))
        add(Rule("j", "জ"))
        add(Rule("T", "ট"))
        add(Rule("D", "ড"))
        add(Rule("N", "ণ"))
        add(Rule("t", "ত"))
        add(Rule("d", "দ"))
        add(Rule("n", "ন"))
        add(Rule("p", "প"))
        add(Rule("f", "ফ"))
        add(Rule("b", "ব"))
        add(Rule("v", "ভ"))
        add(Rule("m", "ম"))
        add(Rule("z", "য"))
        add(Rule("r", "র"))
        add(Rule("l", "ল"))
        add(Rule("S", "শ"))
        add(Rule("s", "স"))
        add(Rule("h", "হ"))
        // Desktop Avro's capital J, the ja with a nukta under it. Written as
        // escapes because it has no precomposed code point, so the pair is the
        // spelling — and a literal here would be at the mercy of whatever
        // normalisation the source file has been through.
        add(Rule("J", "\u099C\u09BC"))
        add(Rule("R", "ড়"))
        // "y"/"Y" are context-sensitive (jofola vs য়), handled in transliterate().
        // "w" is context-sensitive too (ব-ফলা vs ও), handled in transliterate().
        add(Rule("x", "ক্স"))

        // Signs and digits.
        add(Rule(":", "ঃ", null, Kind.SIGN))
        add(Rule("^", "ঁ", null, Kind.SIGN))
        add(Rule(",,", HASANT.toString(), null, Kind.OTHER))
        add(Rule("...", "...", null, Kind.OTHER)) // an ellipsis stays one
        add(Rule("..", ".", null, Kind.OTHER))
        add(Rule(".", "।", null, Kind.OTHER))
        add(Rule("$", "৳", null, Kind.OTHER))
        "0123456789".forEachIndexed { index, digit ->
            add(Rule(digit.toString(), ('০' + index).toString(), null, Kind.OTHER))
        }
    }.sortedByDescending { it.match.length }

    /**
     * Rules bucketed by their first character. A rule can only match at
     * position `i` when its first char equals `input[i]`, so a per-char scan
     * need only test its own bucket — ~1-3 rules — instead of the whole ~90.
     * [rules] is already length-descending, and [groupBy] preserves order, so
     * each bucket stays length-descending: the first prefix match is still the
     * greedy longest match, identical to scanning the full list.
     */
    private val rulesByFirstChar: Map<Char, List<Rule>> = rules.groupBy { it.match[0] }

    /** Longest rule whose match is a prefix of [input] at [at], or null. */
    private fun exactRuleAt(input: String, at: Int): Rule? {
        val bucket = rulesByFirstChar[input[at]] ?: return null
        return bucket.firstOrNull { input.startsWith(it.match, at) }
    }

    /**
     * [exactRuleAt], falling back to the lowercase reading of a capital that
     * spells nothing on its own.
     *
     * Half the alphabet is shifted in Avro — T D N S R J, the long vowels —
     * and those match above, so the fallback only ever sees the letters where
     * a capital was never meant: a shift left latched from the key before, or
     * caps lock. Typing "Bangla" then produces বাংলা instead of dropping a
     * literal "B" into the buffer, which used to make the word look like it
     * had been committed out from under the user.
     *
     * The whole tail is lowered rather than the one character, so a two-letter
     * rule still matches across it ("KH" is খ, not ক্হ); the rule's match
     * length is the same either way, so the caller's advance is unaffected.
     */
    private fun ruleAt(input: String, at: Int): Rule? {
        if (at >= input.length) return null
        exactRuleAt(input, at)?.let { return it }
        if (input[at] !in 'A'..'Z') return null
        return exactRuleAt(input.substring(at).lowercase(), 0)
    }

    /**
     * Whether the consonant [next] starts with conjuncts onto the consonant
     * [out] ends with; [romanBefore] is the roman letter typed just before it.
     * A ন about to join a velar or palatal becomes that class's nasal first
     * ([NASAL_BEFORE]), which is the only edit this makes to [out].
     */
    private fun joins(out: StringBuilder, next: String, romanBefore: Char?): Boolean {
        val head = next[0]
        if (head == 'র') return romanBefore == null || romanBefore !in NO_R_PHOLA_AFTER
        val last = out.lastOrNull() ?: return false
        if (last == 'ন') {
            NASAL_BEFORE[head]?.let {
                out.setCharAt(out.length - 1, it)
                return true
            }
        }
        return "$last$head" in JOINS
    }

    /** Nothing Bengali before [out]'s end: a new word, where Avro spells "y" ইয় and "x" এক্স. */
    private fun atWordStart(out: StringBuilder): Boolean {
        val last = out.lastOrNull() ?: return true
        return !Character.isLetter(last) && Character.getType(last) != Character.NON_SPACING_MARK.toInt() &&
            Character.getType(last) != Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Transliterates one romanized word (or free text) into Bengali script. */
    fun transliterate(input: String): String {
        val out = StringBuilder()
        var prev = Kind.OTHER
        // Whether the last vowel rendered a visible glyph (kar or independent
        // letter). An "a" right after such a vowel glides with য়; only the
        // silent inherent "o" leaves আ independent ("kuroan" → কুরআন).
        var prevVowelGlyph = false
        // Set by a backquote, cleared by whatever it precedes: the one join
        // that does not happen. Only the join — a kar after the break still
        // attaches, since a vowel was never what the breaker was aimed at.
        var breakJoin = false
        // Set by "rr" before a consonant: the reph joins whatever follows,
        // pair list or not, since spelling it out was the whole request.
        var reph = false
        var i = 0
        while (i < input.length) {
            if (input[i] == BREAKER) {
                // A ত whose join has just been taken away is ৎ, which is what
                // makes "hoThat`" the spelling for হঠাৎ. Both the plain ত and
                // the explicit-hasant "t,," spelling collapse the same way.
                when {
                    out.endsWith(TA_HASANT) -> {
                        out.setLength(out.length - 2)
                        out.append(KHANDA_TA)
                        prev = Kind.OTHER
                    }
                    out.lastOrNull() == TA && prev == Kind.CONSONANT -> {
                        out.setLength(out.length - 1)
                        out.append(KHANDA_TA)
                        // ৎ carries its own hasant, so nothing conjuncts onto
                        // it and no kar can sit on it either.
                        prev = Kind.OTHER
                    }
                    else -> breakJoin = true
                }
                prevVowelGlyph = false
                i++
                continue
            }
            // "aZ" is অ্যা, the vowel of অ্যাপ and অ্যাকাউন্ট, and "oZ" the
            // অ্য it is built on. Only where a vowel would stand on its own:
            // after a consonant the "a" is a kar and "Z" its ordinary য-ফলা.
            if (prev != Kind.CONSONANT &&
                (input.startsWith("aZ", i) || input.startsWith("AZ", i) || input.startsWith("oZ", i))
            ) {
                val bare = input[i] == 'o'
                out.append(if (bare) "অ্য" else "অ্যা")
                // অ্য still wants its vowel, so it reads as a consonant.
                prev = if (bare) Kind.CONSONANT else Kind.VOWEL
                prevVowelGlyph = !bare
                breakJoin = false
                reph = false
                i += 2
                continue
            }
            // Inherent vowel: no glyph after a consonant (or after a sign, which
            // closes a consonant's syllable), অ anywhere else. Never ো — the
            // capital "O" is the only thing that writes one, so "bhalo" is ভাল
            // and "bhalO" is ভালো, exactly as on desktop Avro.
            if (input[i] == 'o' && !input.startsWith("oo", i)) {
                val carried = prev == Kind.CONSONANT || prev == Kind.SIGN
                // After a vowel that wrote something, "o" is ও: "amio" →
                // আমিও, "khaoa" → খাওয়া. Anywhere else uncarried it is অ.
                if (!carried) out.append(if (prev == Kind.VOWEL && prevVowelGlyph) 'ও' else 'অ')
                prevVowelGlyph = !carried
                prev = Kind.VOWEL
                i++
                continue
            }
            if (input.startsWith("oo", i)) {
                out.append(if (prev == Kind.CONSONANT) "ু" else "উ")
                prev = Kind.VOWEL
                prevVowelGlyph = true
                i += 2
                continue
            }

            // "a" right after a rendered vowel glides with য় — "kiamot" → কিয়ামত,
            // "piano" → পিয়ানো, "dea" → দেয়া — matching pronunciation.
            // After an inherent (silent) vowel the independent আ survives
            // ("kuroan" → কুরআন), and a capital "A" always stays explicit আ.
            if (input[i] == 'a' && prev == Kind.VOWEL && prevVowelGlyph) {
                out.append("য়া") // U+09DF (precomposed) + U+09BE
                prev = Kind.VOWEL
                prevVowelGlyph = true
                i++
                continue
            }

            // "y": jofola (্য) after a consonant — "shyam" → শ্যাম — and
            // য় elsewhere (word start, after a vowel) — "meye" → মেয়ে.
            // "Y" is always য় and never joins the running cluster, so য়
            // itself stays reachable right after a consonant ("kY" → কয়).
            // "Z" is the mirror of "Y": always jofola, including the positions
            // where the "y" rule would have gone to য় instead.
            if (input[i] == 'y' || input[i] == 'Y' || input[i] == 'Z') {
                if (input[i] == 'Z' || (input[i] == 'y' && prev == Kind.CONSONANT && !breakJoin)) {
                    // A plain র (not reph, not itself a ফলা) keeps its shape
                    // with a joiner: "rZab" → র‍্যাব. Without it the pair
                    // would draw as reph over য.
                    if (!reph && out.lastOrNull() == 'র' && out.getOrNull(out.length - 2) != HASANT) {
                        out.append(ZWJ)
                    }
                    out.append(HASANT).append('য')
                } else if (input[i] == 'y' && prev == Kind.OTHER && atWordStart(out)) {
                    // Avro opens a word's "y" with ই: "yes" → ইয়েস.
                    out.append("ই\u09DF")
                } else {
                    out.append('য়')
                }
                prev = Kind.CONSONANT
                prevVowelGlyph = false
                breakJoin = false
                reph = false
                i++
                continue
            }

            // "w" is ব-ফলা after a consonant — "swasthyo" → স্বাস্থ্য,
            // "swadhInota" → স্বাধীনতা — and ও everywhere else, where the "a"
            // after it glides through the rule above ("wasi" → ওয়াসি).
            if (input[i] == 'w' || input[i] == 'W') {
                val vowelNext = ruleAt(input, i + 1)?.kind == Kind.VOWEL ||
                    input.getOrNull(i + 1) == 'o'
                if (prev == Kind.CONSONANT && !breakJoin) {
                    out.append(HASANT).append('ব')
                    prev = Kind.CONSONANT
                    prevVowelGlyph = false
                } else if (vowelNext) {
                    // ও glides into the vowel after it through য়, which then
                    // carries that vowel as a kar: "we" → ওয়ে, "win" → ওয়িন,
                    // "sawal" → সাওয়াল.
                    out.append("ও\u09DF")
                    prev = Kind.CONSONANT
                    prevVowelGlyph = false
                } else {
                    out.append('ও')
                    prev = Kind.VOWEL
                    prevVowelGlyph = true
                }
                breakJoin = false
                reph = false
                i++
                continue
            }

            // "hs" is the hasant, the spelling Ridmik users know. Only where a
            // hasant can go at all — on a consonant — so the h and s of
            // "ahsan" are still ordinary letters.
            if (input.startsWith("hs", i) && prev == Kind.CONSONANT && !breakJoin) {
                out.append(HASANT)
                prev = Kind.OTHER
                prevVowelGlyph = false
                reph = false
                i += 2
                continue
            }

            // "ng" is ং, which hangs off the syllable in front of it and joins
            // nothing. A vowel after it is a new syllable, and only a consonant
            // can carry one — so there it is ঙ্গ instead, as on desktop Avro:
            // "bangla" → বাংলা, "songe" → সঙ্গে, "jongol" → জঙ্গল. ঙ্গ is
            // the far commoner spelling before a vowel; the bare ঙ of বাঙালি
            // or ভাঙা is "Ng", and the everyday ones are in the spelling list.
            if (input.startsWith("ng", i) &&
                (ruleAt(input, i + 2)?.kind == Kind.VOWEL || input.getOrNull(i + 2) == 'o')
            ) {
                if (prev == Kind.CONSONANT && !breakJoin && (reph || joins(out, "ঙ", input.getOrNull(i - 1)))) {
                    out.append(HASANT)
                }
                out.append("ঙ্গ")
                prev = Kind.CONSONANT
                prevVowelGlyph = false
                breakJoin = false
                reph = false
                i += 2
                continue
            }

            // "rr" is reph: a single র that conjuncts with the following
            // consonant ("borrno" → বর্ন), not a doubled র্র. "rri" (ঋ) has
            // already priority, and with no consonant following, "rr" falls
            // through to the plain r-rule.
            if (input.startsWith("rr", i) && !input.startsWith("rri", i)) {
                val next = ruleAt(input, i + 2)
                val joinsConsonant = next?.kind == Kind.CONSONANT ||
                    (i + 2 < input.length && input[i + 2] == 'y')
                if (joinsConsonant) {
                    if (prev == Kind.CONSONANT && !breakJoin && joins(out, "র", input.getOrNull(i - 1))) {
                        out.append(HASANT)
                    }
                    out.append('র')
                    prev = Kind.CONSONANT
                    prevVowelGlyph = false
                    breakJoin = false
                    reph = true
                    i += 2
                    continue
                }
            }

            val rule = ruleAt(input, i)
            if (rule == null) {
                out.append(input[i])
                prev = Kind.OTHER
                prevVowelGlyph = false
                breakJoin = false
                reph = false
                i++
                continue
            }
            when (rule.kind) {
                Kind.VOWEL -> {
                    out.append(if (prev == Kind.CONSONANT) rule.kar else rule.full)
                    prevVowelGlyph = true
                }
                Kind.CONSONANT -> {
                    if (prev == Kind.CONSONANT && !breakJoin &&
                        (reph || joins(out, rule.full, input.getOrNull(i - 1)))
                    ) {
                        out.append(HASANT)
                    } else if (rule.match == "x" && prev == Kind.OTHER && atWordStart(out)) {
                        out.append('এ') // "x" opens a word as এক্স: "xray" → এক্সরে
                    }
                    out.append(rule.full)
                    prevVowelGlyph = false
                }
                // A sign joins nothing, so no hasant goes in front of it, and
                // none goes after it either — [Kind.SIGN] on [prev] is what
                // stops the next consonant conjuncting onto ং or ঁ.
                Kind.SIGN, Kind.OTHER -> {
                    out.append(rule.full)
                    prevVowelGlyph = false
                }
            }
            prev = rule.kind
            breakJoin = false
            reph = false
            i += rule.match.length
        }
        return collapseFinalKhandaTa(out)
    }

    /**
     * Turns every word-final ত্ into ৎ, which is the whole of what khanda-ta
     * is. Word-final because that is the only place the sequence can be
     * meant: a hasant anywhere else is a join that its next consonant has not
     * arrived for yet, and mid-word "ত্" is one keystroke away from ত্র or
     * ত্ব. Done over the finished string rather than at the point the hasant
     * is written, since "is this the end of the word" is only knowable once
     * the next character is (or is not) there.
     */
    private fun collapseFinalKhandaTa(out: StringBuilder): String {
        var at = out.indexOf(TA_HASANT)
        while (at >= 0) {
            val after = at + 2
            if (after >= out.length || !out[after].isLetter()) {
                out.setCharAt(at, KHANDA_TA)
                out.deleteCharAt(at + 1)
            }
            at = out.indexOf(TA_HASANT, at + 1)
        }
        return out.toString()
    }
}
