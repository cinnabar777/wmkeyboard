package com.wasimaster.wmkeyboard.core.transliteration

/**
 * The key map a phonetic layout's settings page draws: which roman keys type
 * which letter. Reference only, nothing reads it to transliterate. It lives
 * beside the engines so `PhoneticKeyMapTest` can run every entry through the
 * engine it describes, which is what stops the two drifting apart.
 */
data class PhoneticKeyMap(
    val vowels: List<KeyMapEntry>,
    val consonants: List<KeyMapEntry>,
    val signs: List<KeyMapEntry>,
)

/**
 * @param keys every spelling that types [text], the usual one first
 * @param text what the keys write
 * @param sign a vowel's form after a consonant; empty for the inherent vowel,
 *        which writes nothing there; null for anything that is not a vowel
 * @param where the context [text] needs, when the same keys write something
 *        else elsewhere
 * @param example a word that shows the entry at work, and its spelling in the
 *        script, exactly as the rules alone write it
 */
data class KeyMapEntry(
    val keys: List<String>,
    val text: String,
    val sign: String? = null,
    val where: KeyMapWhere? = null,
    val example: Pair<String, String>? = null,
)

/** When an entry only holds in some places; see [KeyMapEntry.where]. */
enum class KeyMapWhere {
    AFTER_CONSONANT, ELSEWHERE, BEFORE_CONSONANT, BEFORE_VOWEL, AFTER_T, BETWEEN_CONSONANTS, WORD_START,
}

object PhoneticKeyMaps {

    /** The map for [langId]'s phonetic layout, or null when it has none. */
    fun forLanguage(langId: String): PhoneticKeyMap? = when (langId) {
        "bn" -> avro
        "hi" -> hindi
        else -> null
    }

    private fun e(vararg keys: String, text: String) = KeyMapEntry(keys.toList(), text)

    private fun v(vararg keys: String, text: String, sign: String) = KeyMapEntry(keys.toList(), text, sign)

    /**
     * [AvroPhonetic]. ড় ঢ় য় are escapes because the engine writes their
     * precomposed code points, which an editor's normalisation can quietly
     * split into letter + nukta.
     */
    val avro = PhoneticKeyMap(
        vowels = listOf(
            v("o", text = "অ", sign = ""),
            v("a", "A", text = "আ", sign = "া"),
            v("i", text = "ই", sign = "ি"),
            v("I", "ee", text = "ঈ", sign = "ী"),
            v("u", "oo", text = "উ", sign = "ু"),
            v("U", text = "ঊ", sign = "ূ"),
            v("rri", text = "ঋ", sign = "ৃ"),
            v("e", "E", text = "এ", sign = "ে"),
            v("OI", text = "ঐ", sign = "ৈ"),
            v("O", text = "ও", sign = "ো"),
            v("OU", text = "ঔ", sign = "ৌ"),
        ),
        consonants = listOf(
            e("k", "q", text = "ক"), e("kh", text = "খ"), e("g", text = "গ"), e("gh", text = "ঘ"), e("Ng", text = "ঙ"),
            e("c", text = "চ"), e("ch", text = "ছ"), e("j", text = "জ"), e("jh", text = "ঝ"), e("NG", text = "ঞ"),
            e("T", text = "ট"), e("Th", text = "ঠ"), e("D", text = "ড"), e("Dh", text = "ঢ"), e("N", text = "ণ"),
            e("t", text = "ত"), e("th", text = "থ"), e("d", text = "দ"), e("dh", text = "ধ"), e("n", text = "ন"),
            e("p", text = "প"), e("ph", "f", text = "ফ"), e("b", text = "ব"), e("bh", "v", text = "ভ"),
            e("m", text = "ম"), e("z", text = "য"), e("r", text = "র"), e("l", text = "ল"),
            e("sh", "S", text = "শ"), e("Sh", text = "ষ"), e("s", text = "স"), e("h", text = "হ"),
            e("R", text = "\u09DC"), e("Rh", text = "\u09DD"), e("Y", text = "\u09DF"), e("J", text = "\u099C\u09BC"),
            e("kkh", "kx", text = "ক্ষ"), e("gg", text = "জ্ঞ"), e("nj", text = "ঞ্জ"),
        ),
        signs = listOf(
            KeyMapEntry(listOf("y"), "্য", where = KeyMapWhere.AFTER_CONSONANT, example = "shyam" to "শ্যাম"),
            KeyMapEntry(listOf("y"), "ই\u09DF", where = KeyMapWhere.WORD_START, example = "yes" to "ই\u09DFেস"),
            KeyMapEntry(listOf("y"), "\u09DF", where = KeyMapWhere.ELSEWHERE, example = "meye" to "মে\u09DFে"),
            KeyMapEntry(listOf("w"), "্ব", where = KeyMapWhere.AFTER_CONSONANT, example = "swasthyo" to "স্বাস্থ্য"),
            KeyMapEntry(listOf("w"), "ও", where = KeyMapWhere.ELSEWHERE, example = "wasi" to "ও\u09DFাসি"),
            KeyMapEntry(listOf("rr"), "র্", where = KeyMapWhere.BEFORE_CONSONANT, example = "dhorrmo" to "ধর্ম"),
            KeyMapEntry(listOf("x"), "এক্স", where = KeyMapWhere.WORD_START, example = "xre" to "এক্সরে"),
            KeyMapEntry(listOf("x"), "ক্স", where = KeyMapWhere.ELSEWHERE, example = "bax" to "বাক্স"),
            KeyMapEntry(listOf("aZ", "AZ"), "অ্যা", example = "aZp" to "অ্যাপ"),
            KeyMapEntry(listOf("ng"), "ং", example = "bangla" to "বাংলা"),
            KeyMapEntry(listOf("ng"), "ঙ্গ", where = KeyMapWhere.BEFORE_VOWEL, example = "songe" to "সঙ্গে"),
            KeyMapEntry(listOf("^", "qq", "cb"), "ঁ", example = "ca^d" to "চাঁদ"),
            KeyMapEntry(listOf(":", "HH"), "ঃ", example = "du:kho" to "দুঃখ"),
            KeyMapEntry(listOf(",,", "hs"), "্", where = KeyMapWhere.AFTER_CONSONANT, example = "m,," to "ম্"),
            KeyMapEntry(listOf("TH"), "ৎ", example = "hoThaTH" to "হঠাৎ"),
            KeyMapEntry(listOf("`"), "ৎ", where = KeyMapWhere.AFTER_T, example = "hoThat`" to "হঠাৎ"),
            KeyMapEntry(listOf("`"), "", where = KeyMapWhere.BETWEEN_CONSONANTS, example = "k`s" to "কস"),
            e(".", text = "।"),
            e("$", text = "৳"),
        ),
    )

    /** [HindiPhonetic]. */
    val hindi = PhoneticKeyMap(
        vowels = listOf(
            v("a", text = "अ", sign = ""),
            v("aa", "A", text = "आ", sign = "ा"),
            v("i", text = "इ", sign = "ि"),
            v("ee", "ii", "I", text = "ई", sign = "ी"),
            v("u", text = "उ", sign = "ु"),
            v("oo", "uu", "U", text = "ऊ", sign = "ू"),
            v("e", text = "ए", sign = "े"),
            v("ai", text = "ऐ", sign = "ै"),
            v("o", text = "ओ", sign = "ो"),
            v("au", "ou", text = "औ", sign = "ौ"),
            v("RRi", text = "ऋ", sign = "ृ"),
        ),
        consonants = listOf(
            e("k", "q", text = "क"), e("kh", text = "ख"), e("g", text = "ग"), e("gh", text = "घ"),
            e("ch", "c", text = "च"), e("chh", text = "छ"), e("j", text = "ज"), e("jh", text = "झ"),
            e("T", text = "ट"), e("Th", text = "ठ"), e("D", text = "ड"), e("Dh", text = "ढ"), e("N", text = "ण"),
            e("t", text = "त"), e("th", text = "थ"), e("d", text = "द"), e("dh", text = "ध"), e("n", text = "न"),
            e("p", text = "प"), e("ph", "f", text = "फ"), e("b", text = "ब"), e("bh", text = "भ"), e("m", text = "म"),
            e("y", text = "य"), e("r", text = "र"), e("l", text = "ल"), e("v", "w", text = "व"),
            e("sh", text = "श"), e("Sh", text = "ष"), e("s", text = "स"), e("h", text = "ह"),
            e("z", text = "ज़"), e("R", text = "ड़"), e("Rh", text = "ढ़"),
            e("ksh", text = "क्ष"), e("gy", "jn", text = "ज्ञ"), e("x", text = "क्स"),
        ),
        signs = listOf(
            KeyMapEntry(listOf("M"), "ं", example = "saMsaar" to "संसार"),
            KeyMapEntry(listOf("n", "m"), "ं", where = KeyMapWhere.BEFORE_CONSONANT, example = "hindi" to "हिंदी"),
            KeyMapEntry(listOf("H"), "ः", example = "duHkh" to "दुःख"),
        ),
    )
}
