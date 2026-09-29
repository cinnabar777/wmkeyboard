package com.wasimaster.wmkeyboard.core.selection

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats

/**
 * What a selection turned out to be, which decides the macros offered for it.
 *
 * [TEXT] is the fallback and never an entity: it is prose, a word, a paragraph,
 * anything that is not one whole address, link or number. The three real kinds
 * are deliberately *whole-selection* readings — a sentence with a phone number
 * buried in it is [TEXT], because the user selected the sentence. Pulling the
 * fragment out of a longer span is the clipboard panel's job (see
 * `ClipEntities`), which has the room to show what it found and where.
 */
enum class SelectionKind { PHONE, EMAIL, URL, TEXT }

/**
 * One action offered for the current selection.
 *
 * The four `CASE_*` members are not offered on the bar itself: they are what
 * [SelectionMacro.FORMAT] opens on a plain-text selection, so they never appear
 * in [SelectionMacros.configurable] and have no settings switch of their own.
 */
enum class SelectionMacro {
    /**
     * Put back the last rewrite made to this selection. Pinned first on the
     * row whenever there is one; listed so it can be switched off.
     */
    UNDO,
    /**
     * Widen the selection to the whole field.
     *
     * A long press picks one word, and somebody selecting in order to copy or
     * share usually meant all of it. Left off once the whole field is
     * selected, where it has nothing left to take.
     */
    SELECT_ALL,
    COPY,
    CUT,
    /** Paste over the selection. Only offered while the clipboard holds text. */
    PASTE,
    DELETE,
    SHARE,
    /**
     * Rewrite the selection in place. What that means follows the kind: a phone
     * number takes the user's own mask, a link loses its tracking parameters, an
     * address is lower-cased, and plain text opens the case ladder below.
     */
    FORMAT,
    /** Jump to the next occurrence of the selection in the field. */
    FIND,
    /** Open the Find and replace panel with the selection as the query. */
    REPLACE,
    LINES_SORT,
    LINES_DEDUPE,
    LINES_NUMBER,
    LINES_BULLET,
    SEARCH,
    TRANSLATE,
    /** Correct the selection with the grammar checker, in place. */
    GRAMMAR_FIX,
    /**
     * Rewrite the selection with DeepL Write, in place. Only offered once the
     * user has set DeepL up with a key and switched Write on (#331).
     */
    DEEPL_WRITE,
    /** Open the AI tool on the selection. Direct buttons for single actions ride beside it. */
    AI,
    TO_BANGLA,
    TO_BANGLISH,
    /** Romanized Hindi in the selection rewritten in Devanagari, as the Hindi phonetic layout would have typed it. */
    TO_HINDI,
    /** Devanagari in the selection written back in Latin letters. */
    TO_HINGLISH,
    /** Digits of another script rewritten as `0-9`. */
    DIGITS_LATIN,
    /** A colour code: a swatch on the chip, and a ladder of its other spellings. */
    COLOUR,
    JSON_FORMAT,
    BASE64_DECODE,
    URL_DECODE,
    /**
     * Take the tracking parameters off a link, in place.
     *
     * Only offered on a link that carries one, so it never shows up to do
     * nothing. Format on a link does the same and adds a missing scheme; this
     * is the chip that says what it is for, and the one that leaves a bare
     * domain bare.
     */
    STRIP_TRACKERS,
    CHAT_BOLD,
    CHAT_ITALIC,
    CHAT_STRIKE,
    CHAT_MONO,
    READ_ALOUD,
    /** The selected time shown in the user's own zones, each a chip that replaces it. */
    TIME_ZONES,
    CALL,
    SMS,
    WHATSAPP,
    /** Compose a new message to the selected address. */
    EMAIL,
    /** Hand the selection to whichever app claims it: a browser, a mail client. */
    OPEN,
    /** Turn the link into a QR code with the generator tool. */
    QR,
    ADD_CONTACT,
    MAP,
    CALENDAR,
    /**
     * Open the Fancy Text styles for the selection, and rewrite it in the one
     * picked.
     *
     * A door rather than an action, the way [FORMAT] is on plain text: a style
     * is a look, and there is no answering "which one" without showing them.
     * The ladder is drawn from `FancyStyles` rather than from members here —
     * there are thirty-odd styles, they are data, and the bar writes each chip
     * in its own style, which no enum could carry.
     */
    FANCY,
    CASE_LOWER,
    CASE_TITLE,
    CASE_UPPER,
    CASE_SENTENCE,
    /** The programmer's cases: inside the Format ladder, each with a switch of its own. */
    CASE_CAMEL,
    CASE_SNAKE,
    CASE_KEBAB,
    CASE_CONSTANT,
    ;

    /** The chip's own word, and its accessibility label. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            UNDO -> CommonR.string.common_undo
            SELECT_ALL -> CommonR.string.common_select_all
            COPY -> R.string.core_content_selection_macro_copy
            CUT -> CommonR.string.common_cut
            PASTE -> CommonR.string.common_paste
            DELETE -> CommonR.string.common_delete
            SHARE -> R.string.core_content_selection_macro_share
            FORMAT -> R.string.core_content_selection_macro_format
            FIND -> R.string.core_content_selection_macro_find
            REPLACE -> R.string.core_content_selection_macro_replace
            LINES_SORT -> R.string.core_content_selection_macro_lines_sort
            LINES_DEDUPE -> R.string.core_content_selection_macro_lines_dedupe
            LINES_NUMBER -> R.string.core_content_selection_macro_lines_number
            LINES_BULLET -> R.string.core_content_selection_macro_lines_bullet
            SEARCH -> R.string.core_content_selection_macro_search
            TRANSLATE -> R.string.core_content_selection_macro_translate
            GRAMMAR_FIX -> R.string.core_content_selection_macro_grammar
            DEEPL_WRITE -> R.string.core_content_selection_macro_deepl_write
            AI -> R.string.core_content_selection_macro_ai
            TO_BANGLA -> R.string.core_content_selection_macro_to_bangla
            TO_BANGLISH -> R.string.core_content_selection_macro_to_banglish
            TO_HINDI -> R.string.core_content_selection_macro_to_hindi
            TO_HINGLISH -> R.string.core_content_selection_macro_to_hinglish
            DIGITS_LATIN -> R.string.core_content_selection_macro_digits_latin
            COLOUR -> R.string.core_content_selection_macro_colour
            JSON_FORMAT -> R.string.core_content_selection_macro_json
            BASE64_DECODE -> R.string.core_content_selection_macro_base64
            URL_DECODE -> R.string.core_content_selection_macro_url_decode
            STRIP_TRACKERS -> R.string.core_content_selection_macro_strip_trackers
            CHAT_BOLD -> R.string.core_content_selection_macro_chat_bold
            CHAT_ITALIC -> R.string.core_content_selection_macro_chat_italic
            CHAT_STRIKE -> R.string.core_content_selection_macro_chat_strike
            CHAT_MONO -> R.string.core_content_selection_macro_chat_mono
            READ_ALOUD -> R.string.core_content_selection_macro_read_aloud
            TIME_ZONES -> R.string.core_content_selection_macro_time_zones
            CALL -> R.string.core_content_selection_macro_call
            SMS -> R.string.core_content_selection_macro_sms
            WHATSAPP -> R.string.core_content_selection_macro_whatsapp
            EMAIL -> R.string.core_content_selection_macro_email
            OPEN -> R.string.core_content_selection_macro_open
            QR -> R.string.core_content_selection_macro_qr
            ADD_CONTACT -> R.string.core_content_selection_macro_add_contact
            MAP -> R.string.core_content_selection_macro_map
            CALENDAR -> R.string.core_content_selection_macro_calendar
            FANCY -> R.string.core_content_selection_macro_fancy
            CASE_LOWER -> R.string.core_content_selection_macro_case_lower
            CASE_TITLE -> R.string.core_content_selection_macro_case_title
            CASE_UPPER -> R.string.core_content_selection_macro_case_upper
            CASE_SENTENCE -> R.string.core_content_selection_macro_case_sentence
            CASE_CAMEL -> R.string.core_content_selection_macro_case_camel
            CASE_SNAKE -> R.string.core_content_selection_macro_case_snake
            CASE_KEBAB -> R.string.core_content_selection_macro_case_kebab
            CASE_CONSTANT -> R.string.core_content_selection_macro_case_constant
        }

    /** The group the settings screen lists the macro under. */
    val category: MacroCategory
        get() = when (this) {
            UNDO, SELECT_ALL, COPY, CUT, PASTE, DELETE, FIND, REPLACE -> MacroCategory.EDITING
            LINES_SORT, LINES_DEDUPE, LINES_NUMBER, LINES_BULLET -> MacroCategory.LINES
            FORMAT, FANCY, CHAT_BOLD, CHAT_ITALIC, CHAT_STRIKE, CHAT_MONO,
            CASE_LOWER, CASE_TITLE, CASE_UPPER, CASE_SENTENCE,
            CASE_CAMEL, CASE_SNAKE, CASE_KEBAB, CASE_CONSTANT -> MacroCategory.FORMAT
            DIGITS_LATIN, COLOUR, JSON_FORMAT, BASE64_DECODE, URL_DECODE, STRIP_TRACKERS -> MacroCategory.CONVERT
            TRANSLATE, GRAMMAR_FIX, DEEPL_WRITE, AI, TO_BANGLA, TO_BANGLISH, TO_HINDI, TO_HINGLISH -> MacroCategory.LANGUAGE
            SEARCH, READ_ALOUD, TIME_ZONES -> MacroCategory.LOOKUP
            SHARE, CALL, SMS, WHATSAPP, EMAIL, OPEN, QR, ADD_CONTACT, MAP, CALENDAR -> MacroCategory.OPEN_IN
        }

    /** Whether the macro leaves the keyboard for another app. */
    val leavesApp: Boolean
        get() = this == SHARE || this == CALL || this == SMS || this == WHATSAPP ||
            this == EMAIL || this == OPEN || this == ADD_CONTACT || this == MAP || this == CALENDAR

    /**
     * Whether the macro can run before the first unlock. Anything that starts
     * an activity cannot; nor can a paste (the clipboard is behind the lock)
     * or reading aloud (a speech engine is another app to bind).
     */
    val directBootSafe: Boolean
        get() = !leavesApp && this != PASTE && this != READ_ALOUD

    /** A door rather than an action: the bar opens a ladder for it and the service never sees the tap. */
    val opensLadder: Boolean
        get() = this == FORMAT || this == FANCY || this == COLOUR || this == TIME_ZONES
}

/**
 * Selection macros: a row of one-tap actions for whatever is selected.
 *
 * Selecting `01712345678` should not mean opening the dialler by hand and
 * typing it back in, and selecting a link should not mean copying it, leaving
 * the app and pasting it into a browser. The keyboard already knows what is
 * selected, so it offers the two or three things anybody would want to do with
 * that particular shape of text and leaves the rest alone.
 *
 * Everything here is pure and synchronous. [detect] reads a selection, [offer]
 * turns that reading plus the user's switches into the row, and the `format*`
 * helpers do the rewriting that [SelectionMacro.FORMAT] commits. The actions
 * themselves (an intent, a clipboard write, a tool opened) belong to the
 * keyboard service, which is the only thing that can run them.
 */
object SelectionMacros {

    /**
     * Longest selection that can still *be* an entity.
     *
     * E.164 caps a number at 15 digits, addresses are capped at 254 octets by
     * RFC 5321, and a link long enough to pass this is one nobody is going to
     * read off a chip anyway. Past it the reading is plain text without running
     * a single regex, which is what keeps a selected paragraph cheap.
     */
    private const val MAX_ENTITY_LENGTH = 320

    /** Below this a selection is too short to be worth acting on at all. */
    private const val MIN_LENGTH = 1

    /**
     * A whole selection that is one address. Deliberately stricter than the
     * shapes a mail server will accept: quoted local parts and bracketed IP
     * domains exist, and a selection that looks like one is far more likely to
     * be code than an address somebody wants to write to.
     */
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63})*\.[A-Za-z]{2,24}""")

    /** A whole selection that is one link, with or without its scheme. */
    private val URL = Regex(
        """(?:[a-z][a-z0-9+.\-]{1,15}://|www\.)[^\s<>"'`]{2,}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A bare domain typed without a scheme, which is how links are written in
     * ordinary prose. Kept apart from [URL] because it needs a known ending to
     * be a link at all: without one, `Mr.Smith` and `file.txt` are links.
     */
    private val BARE_DOMAIN = Regex(
        """[A-Za-z0-9\-]{1,63}(?:\.[A-Za-z0-9\-]{1,63})*\.([A-Za-z]{2,24})(?:[/?#][^\s]*)?""",
    )

    /**
     * Endings a bare domain is allowed to have.
     *
     * There are over a thousand top-level domains and several of them are also
     * ordinary file extensions (`.zip`, `.mov`, `.sh`), so a full list would
     * turn every selected filename into a link. This is the short list of
     * endings that are almost never anything else.
     */
    private val COMMON_TLDS = setOf(
        "com", "org", "net", "edu", "gov", "int", "mil", "io", "co", "dev", "app", "ai",
        "info", "biz", "me", "tv", "xyz", "online", "site", "shop", "store", "blog",
        "uk", "us", "ca", "au", "in", "bd", "pk", "de", "fr", "nl", "it", "es", "se",
        "no", "fi", "dk", "pl", "ru", "cn", "jp", "kr", "br", "mx", "za", "ng", "ke",
    )

    /**
     * A whole selection that is one phone number.
     *
     * The optional bracket after the optional `+` is for the one shape people
     * really do copy whole: `(555) 123-4567`. Anything else leading is not a
     * number, which is what keeps a bulleted line or a quoted figure out.
     */
    private val PHONE = Regex("""\+?\(?\d[\d ().\-]{5,18}\d""")

    /** Separators a number may be written with, ignored when it is reformatted. */
    private const val PHONE_SEPARATORS = " -().[]/"

    /**
     * Query parameters stripped by [stripTrackers]: campaign trackers and the
     * per-click ids the big platforms staple on. Matched case-insensitively,
     * and the campaign families by prefix, because they keep growing.
     *
     * Only names that are never about the page itself. `ref` and `source`
     * stay: plenty of sites route on them, and a cleaned link that no longer
     * opens the right page is worse than a tracked one.
     */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "igshid", "igsh", "ttclid", "twclid", "yclid", "si", "ref_src", "ref_url",
        "_openstat", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id", "spm",
        "_hsenc", "_hsmi", "mkt_tok", "srsltid", "epik", "rb_clickid", "s_cid", "sc_cid",
        "pk_campaign", "pk_kwd", "pk_source", "pk_medium", "pk_content",
    )

    /** Google Analytics, Matomo and HubSpot ad campaigns: a family each. */
    private val TRACKING_PREFIXES = listOf("utm_", "mtm_", "hsa_")

    /** The four fixed cases: never configurable, always inside the Format ladder. */
    val fixedCaseMacros: List<SelectionMacro> = listOf(
        SelectionMacro.CASE_LOWER,
        SelectionMacro.CASE_TITLE,
        SelectionMacro.CASE_UPPER,
        SelectionMacro.CASE_SENTENCE,
    )

    /** Configurable, but never on the row: they live inside the Format ladder. */
    val ladderOnly: Set<SelectionMacro> = setOf(
        SelectionMacro.CASE_CAMEL,
        SelectionMacro.CASE_SNAKE,
        SelectionMacro.CASE_KEBAB,
        SelectionMacro.CASE_CONSTANT,
    )

    /** The macros a settings screen can switch. */
    val configurable: List<SelectionMacro> = SelectionMacro.entries - fixedCaseMacros.toSet()

    /**
     * The row as shipped. Only bar-capable macros, in the order they read
     * when the user has not moved anything: Undo, Select all, then what the
     * entity is for (absent on plain text, so they cost it nothing), then the
     * generic edits and everything content-gated after them.
     */
    val defaultOrder: List<SelectionMacro> = listOf(
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL,
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
        SelectionMacro.OPEN, SelectionMacro.STRIP_TRACKERS, SelectionMacro.QR, SelectionMacro.ADD_CONTACT,
        SelectionMacro.COPY, SelectionMacro.CUT, SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE,
        SelectionMacro.FORMAT, SelectionMacro.FIND, SelectionMacro.REPLACE,
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE, SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET,
        SelectionMacro.GRAMMAR_FIX, SelectionMacro.DEEPL_WRITE, SelectionMacro.TRANSLATE, SelectionMacro.AI,
        SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH,
        SelectionMacro.TO_HINDI, SelectionMacro.TO_HINGLISH,
        SelectionMacro.DIGITS_LATIN, SelectionMacro.COLOUR, SelectionMacro.FANCY,
        SelectionMacro.CHAT_BOLD, SelectionMacro.CHAT_ITALIC, SelectionMacro.CHAT_STRIKE, SelectionMacro.CHAT_MONO,
        SelectionMacro.JSON_FORMAT, SelectionMacro.BASE64_DECODE, SelectionMacro.URL_DECODE,
        SelectionMacro.TIME_ZONES, SelectionMacro.CALENDAR, SelectionMacro.MAP, SelectionMacro.READ_ALOUD,
        SelectionMacro.SEARCH,
    )

    /**
     * The shipped set. Search is off because it is a round trip to a network
     * service that spends a small daily quota; DeepL Write is one too, but it
     * is on because its gate already waits for the user to set DeepL up and
     * switch Write on. Translate is on (#434): it only opens the Translate
     * tool, which may translate on the device, and a selection in a language
     * the user cannot read is the one they could not have typed into the
     * tool by hand. The programmer's cases, chat markup, speech, maps,
     * calendars and the decoders are off because each is a taste rather than
     * a need, and the row is long enough already.
     */
    val defaultMacros: Set<SelectionMacro> = setOf(
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL, SelectionMacro.COPY, SelectionMacro.CUT,
        SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE, SelectionMacro.FORMAT,
        SelectionMacro.FIND, SelectionMacro.REPLACE,
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE, SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET,
        SelectionMacro.GRAMMAR_FIX, SelectionMacro.DEEPL_WRITE, SelectionMacro.TRANSLATE, SelectionMacro.AI,
        SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH,
        SelectionMacro.TO_HINDI, SelectionMacro.TO_HINGLISH,
        SelectionMacro.DIGITS_LATIN, SelectionMacro.COLOUR,
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP, SelectionMacro.EMAIL,
        SelectionMacro.OPEN, SelectionMacro.QR, SelectionMacro.ADD_CONTACT, SelectionMacro.FANCY,
        SelectionMacro.STRIP_TRACKERS,
    )

    /**
     * Bumped when [defaultMacros] or [defaultOrder] change in a way every
     * existing user should receive. A stored list under an older version is
     * read as unset, which is the shipped list.
     */
    const val LIST_VERSION = 2

    /** The case ladder [SelectionMacro.FORMAT] opens on a plain-text selection. */
    fun caseLadder(allowed: Set<SelectionMacro>): List<SelectionMacro> =
        fixedCaseMacros + ladderOnly.filter { it in allowed }

    /**
     * What [selection] is, as a whole.
     *
     * [phoneFormats] are the user's own masks, as they are stored (see
     * `PhoneFormats`). When the list is not empty a number has to match one of
     * them, which is what stops an order total or a row of figures from
     * offering to dial itself. With no masks set the shape rules stand alone,
     * as they do in the clipboard panel.
     */
    fun detect(selection: String, phoneFormats: List<String> = emptyList()): SelectionKind {
        val trimmed = selection.trim()
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_ENTITY_LENGTH) return SelectionKind.TEXT
        // A line break means the user swept up more than one thing, whatever
        // the pieces look like on their own.
        if (trimmed.any { it == '\n' || it == '\r' }) return SelectionKind.TEXT
        if (EMAIL.matches(trimmed)) return SelectionKind.EMAIL
        if (isUrl(trimmed)) return SelectionKind.URL
        if (isPhone(trimmed, phoneFormats)) return SelectionKind.PHONE
        return SelectionKind.TEXT
    }

    private fun isUrl(trimmed: String): Boolean {
        // A link with a scheme says so itself and needs no ending check.
        if (URL.matches(trimmed)) return true
        val bare = BARE_DOMAIN.matchEntire(trimmed) ?: return false
        return bare.groupValues[1].lowercase() in COMMON_TLDS
    }

    private fun isPhone(trimmed: String, phoneFormats: List<String>): Boolean {
        if (!PHONE.matches(trimmed)) return false
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return false
        // Punctuation that never appears in a phone number rules one out even
        // where the digit run would have passed.
        if (trimmed.any { !it.isDigit() && it != '+' && it !in PHONE_SEPARATORS }) return false
        return PhoneFormats.matches(trimmed, PhoneFormats.parseAll(phoneFormats))
    }

    private const val MIN_PHONE_DIGITS = 7
    private const val MAX_PHONE_DIGITS = 15

    /**
     * The row for [kind]: the user's [order] (anything it does not name comes
     * after it in shipped order, so a new macro appears instead of vanishing)
     * filtered to what [allowed] turns on, what the kind can carry and what
     * [gates] say the device and the text allow. Undo, when there is one, is
     * first whatever the order says.
     */
    fun offer(
        kind: SelectionKind,
        allowed: Set<SelectionMacro>,
        gates: MacroGates = MacroGates(),
        order: List<SelectionMacro> = defaultOrder,
    ): List<SelectionMacro> {
        val sequence = order.filter { it !in ladderOnly } + defaultOrder.filter { it !in order }
        val row = sequence.filter { it in allowed && eligible(it, kind) && passes(it, gates) }
        return if (SelectionMacro.UNDO in row) listOf(SelectionMacro.UNDO) + (row - SelectionMacro.UNDO) else row
    }

    /** Every macro [kind] can carry, in shipped order: the eligibility, not the gates. */
    fun macrosFor(kind: SelectionKind): List<SelectionMacro> = defaultOrder.filter { eligible(it, kind) }

    /**
     * Which kinds a macro belongs to. The entity actions follow their entity;
     * the edits and the doors follow every selection; everything else is for
     * prose, where a styled link is not a link and a numbered phone number is
     * nonsense.
     */
    private fun eligible(macro: SelectionMacro, kind: SelectionKind): Boolean = when (macro) {
        SelectionMacro.CALL, SelectionMacro.SMS, SelectionMacro.WHATSAPP -> kind == SelectionKind.PHONE
        SelectionMacro.EMAIL -> kind == SelectionKind.EMAIL
        SelectionMacro.QR, SelectionMacro.STRIP_TRACKERS -> kind == SelectionKind.URL
        SelectionMacro.OPEN -> kind == SelectionKind.URL || kind == SelectionKind.EMAIL
        SelectionMacro.ADD_CONTACT -> kind == SelectionKind.PHONE || kind == SelectionKind.EMAIL
        SelectionMacro.URL_DECODE -> kind == SelectionKind.URL || kind == SelectionKind.TEXT
        SelectionMacro.UNDO, SelectionMacro.SELECT_ALL, SelectionMacro.COPY, SelectionMacro.CUT,
        SelectionMacro.PASTE, SelectionMacro.DELETE, SelectionMacro.SHARE, SelectionMacro.FORMAT,
        SelectionMacro.FIND, SelectionMacro.REPLACE, SelectionMacro.READ_ALOUD -> true
        else -> kind == SelectionKind.TEXT
    }

    private fun passes(macro: SelectionMacro, gates: MacroGates): Boolean = when (macro) {
        SelectionMacro.WHATSAPP -> gates.whatsAppInstalled
        SelectionMacro.QR -> gates.qrAvailable
        SelectionMacro.FORMAT -> gates.formattable
        SelectionMacro.SELECT_ALL -> !gates.wholeField
        // A whole-field or multi-line selection has no "next" worth jumping to.
        SelectionMacro.FIND -> !gates.wholeField && !gates.content.multiLine
        SelectionMacro.PASTE -> gates.clipboardHasText
        SelectionMacro.UNDO -> gates.undoAvailable
        SelectionMacro.LINES_SORT, SelectionMacro.LINES_DEDUPE,
        SelectionMacro.LINES_NUMBER, SelectionMacro.LINES_BULLET -> gates.content.multiLine
        SelectionMacro.GRAMMAR_FIX -> gates.grammarAvailable
        SelectionMacro.DEEPL_WRITE -> gates.deeplWriteAvailable
        SelectionMacro.AI -> gates.aiAvailable
        SelectionMacro.TO_BANGLA -> gates.bengaliLoaded && gates.content.hasLatin && !gates.content.hasBengali
        SelectionMacro.TO_BANGLISH -> gates.content.hasBengali
        // Both ways need Hindi to be a language this keyboard is typing: Devanagari
        // is a dozen languages' script, and a Marathi selection is not asking to
        // be read as Hindi by someone who never enabled it.
        SelectionMacro.TO_HINDI -> gates.hindiLoaded && gates.content.hasLatin && !gates.content.hasDevanagari
        SelectionMacro.TO_HINGLISH -> gates.hindiLoaded && gates.content.hasDevanagari
        SelectionMacro.DIGITS_LATIN -> gates.content.hasForeignDigits
        SelectionMacro.COLOUR -> gates.content.colour != null
        SelectionMacro.MAP -> gates.content.place != null
        SelectionMacro.CALENDAR -> gates.content.dateTime != null
        SelectionMacro.TIME_ZONES -> gates.content.dateTime?.hasTime == true
        SelectionMacro.CHAT_BOLD, SelectionMacro.CHAT_ITALIC, SelectionMacro.CHAT_STRIKE -> gates.chatSyntax != null
        SelectionMacro.CHAT_MONO -> gates.chatSyntax?.mono != null
        SelectionMacro.JSON_FORMAT -> gates.content.jsonShape != JsonReformat.Shape.NONE
        SelectionMacro.BASE64_DECODE -> gates.content.base64
        SelectionMacro.URL_DECODE -> gates.content.urlEncoded
        SelectionMacro.STRIP_TRACKERS -> gates.content.hasTrackers
        SelectionMacro.READ_ALOUD -> gates.ttsAvailable
        else -> true
    }

    /**
     * The selection rewritten for its kind, or null when there is nothing to
     * do. Plain text is not handled here: its Format opens the case ladder
     * instead, and [applyCase] is what commits one of those.
     */
    fun format(selection: String, kind: SelectionKind, phoneFormats: List<String> = emptyList()): String? {
        val trimmed = selection.trim()
        val formatted = when (kind) {
            SelectionKind.PHONE -> formatPhone(trimmed, phoneFormats)
            SelectionKind.EMAIL -> trimmed.lowercase()
            SelectionKind.URL -> formatUrl(trimmed)
            SelectionKind.TEXT -> null
        } ?: return null
        return formatted.takeIf { it != trimmed }
    }

    /**
     * [number] written the way the user's own mask writes it.
     *
     * The mask is rendered rather than described: its separators are copied
     * across and its digit slots filled, so the result is the shape the user
     * typed into the formats list and not a shape this file invented. A mask
     * that names a country code adds it back when the number was written
     * without one, which is the whole point of the action for anybody who
     * stores numbers internationally and copies them nationally.
     *
     * With no matching mask the fallback is E.164 when the number already
     * declares a country code, and the bare digit run otherwise. Both are
     * tidier than a number carrying whatever spacing it was pasted with.
     */
    fun formatPhone(number: String, phoneFormats: List<String>): String? {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val explicit = number.trimStart().startsWith("+")
        val raw = phoneFormats.firstOrNull { mask ->
            PhoneFormats.parse(mask)?.let { PhoneFormats.matches(number, listOf(it)) } == true
        } ?: return if (explicit) "+$digits" else digits
        val mask = PhoneFormats.parse(raw) ?: return null
        val national = digits.takeLast(mask.national.length)
        if (national.length < mask.national.length) return null
        return renderMask(raw, mask.countryCode.orEmpty() + national)
    }

    /**
     * Fills [mask]'s digit slots with [digits], copying everything else across.
     *
     * A slot is a digit or an [PhoneFormats.ANY] in the mask; a separator, a
     * bracket or the leading `+` is decoration and travels unchanged. Null when
     * the two do not line up, which the caller has already ruled out but which
     * a mask edited between the match and the render could still cause.
     */
    private fun renderMask(mask: String, digits: String): String? {
        val out = StringBuilder(mask.length)
        var next = 0
        for (ch in mask.trim()) {
            if (ch.isDigit() || ch == PhoneFormats.ANY || ch in MASK_WILDCARDS) {
                if (next >= digits.length) return null
                out.append(digits[next])
                next++
            } else {
                out.append(ch)
            }
        }
        return if (next == digits.length) out.toString() else null
    }

    /** How a hand-written mask can spell "any digit"; canonical form is `X`. */
    private const val MASK_WILDCARDS = "x#"

    /** [url] with its tracking parameters removed, and a scheme added when it had none. */
    fun formatUrl(url: String): String? {
        val withScheme = if (url.contains("://")) url else "https://$url"
        return stripTrackers(withScheme) ?: withScheme
    }

    /**
     * [url] without its tracking parameters, or null when it carries none.
     *
     * Only the query is touched. Rewriting a path, a fragment or a host is how
     * a "clean this link" feature quietly breaks the link, and the parameters
     * are the part that is provably not about where the page is. Nor is a
     * scheme added: the link keeps the shape it was written in.
     */
    fun stripTrackers(url: String): String? {
        val trimmed = url.trim()
        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = trimmed.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) {
            trimmed.substring(queryStart + 1)
        } else {
            trimmed.substring(queryStart + 1, fragmentStart)
        }
        val fragment = if (fragmentStart < 0) "" else trimmed.substring(fragmentStart)
        val parts = query.split('&')
        val kept = parts.filter { part ->
            val name = part.substringBefore('=').lowercase()
            name.isNotEmpty() && TRACKING_PREFIXES.none { name.startsWith(it) } && name !in TRACKING_PARAMS
        }
        // Empty pieces (`a=1&&b=2`, a trailing `?`) go too, but on their own
        // they are not trackers and do not make a link worth offering to clean.
        if (kept.size == parts.count { it.substringBefore('=').isNotEmpty() }) return null
        val base = trimmed.substring(0, queryStart)
        return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
    }

    /**
     * [selection] in the case [macro] names, or null when it is already there.
     *
     * Title case capitalises every word and sentence case only the first, which
     * is the distinction people actually reach for. Neither touches a word that
     * is already all capitals: an acronym in the middle of a sentence is not a
     * casing mistake, and lower-casing it is the one edit that cannot be undone
     * by another tap on this same row.
     *
     * That rule is suspended when the *whole* selection is capitals, which is
     * the case somebody selecting SHOUTED TEXT and reaching for Title case
     * actually has. Reading every word there as an acronym would leave the
     * selection exactly as it was and make both chips look broken.
     */
    fun applyCase(selection: String, macro: SelectionMacro): String? {
        // Nothing lower-cased anywhere, and at least one letter to judge by.
        val allCaps = selection.any { it.isLetter() } && selection.none { it.isLowerCase() }
        val result = when (macro) {
            SelectionMacro.CASE_LOWER -> selection.lowercase()
            SelectionMacro.CASE_UPPER -> selection.uppercase()
            SelectionMacro.CASE_TITLE -> recase(selection, everyWord = true, keepAcronyms = !allCaps)
            SelectionMacro.CASE_SENTENCE -> recase(selection, everyWord = false, keepAcronyms = !allCaps)
            SelectionMacro.CASE_CAMEL -> CodeCases.camel(selection) ?: return null
            SelectionMacro.CASE_SNAKE -> CodeCases.snake(selection) ?: return null
            SelectionMacro.CASE_KEBAB -> CodeCases.kebab(selection) ?: return null
            SelectionMacro.CASE_CONSTANT -> CodeCases.constant(selection) ?: return null
            else -> return null
        }
        return result.takeIf { it != selection }
    }

    /**
     * The one walk behind Title case and Sentence case.
     *
     * [everyWord] capitalises each word rather than only the first of each
     * sentence; [keepAcronyms] leaves an all-capitals word alone. Words are
     * runs of letters plus the apostrophes inside them, so "don't" is one word
     * and its `t` is not a fresh one to capitalise.
     */
    private fun recase(text: String, everyWord: Boolean, keepAcronyms: Boolean): String {
        val out = StringBuilder(text.length)
        var startOfSentence = true
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (!ch.isLetter()) {
                out.append(ch)
                if (ch in SENTENCE_ENDS) startOfSentence = true
                index++
                continue
            }
            var end = index
            while (end < text.length && (text[end].isLetter() || text[end] in WORD_INNER)) end++
            // An apostrophe is only inside a word while a letter follows it.
            while (end > index && text[end - 1] in WORD_INNER) end--
            val word = text.substring(index, end)
            val acronym = keepAcronyms && word.none { it.isLowerCase() }
            out.append(
                when {
                    acronym -> word
                    everyWord || startOfSentence -> word[0].uppercaseChar() + word.substring(1).lowercase()
                    else -> word.lowercase()
                },
            )
            startOfSentence = false
            index = end
        }
        return out.toString()
    }

    /** Characters that sit inside a word without ending it. */
    private const val WORD_INNER = "'’"

    private const val SENTENCE_ENDS = ".!?।؟"

    /**
     * The number to dial, as `tel:` and `wa.me` want it: digits only, with the
     * dial code when the selection or a mask supplies one.
     */
    fun dialDigits(number: String, phoneFormats: List<String>): String {
        val digits = number.filter { it.isDigit() }
        if (number.trimStart().startsWith("+")) return digits
        val mask = PhoneFormats.parseAll(phoneFormats)
            .firstOrNull { PhoneFormats.matches(number, listOf(it)) }
        val code = mask?.countryCode ?: return digits
        return code + digits.takeLast(mask.national.length)
    }

    /** The link as something a browser will take: a bare domain gains `https://`. */
    fun openableUrl(url: String): String =
        if (url.contains("://")) url else "https://${url.removePrefix("//")}"

    /**
     * What [text] holds, for the content-gated macros.
     *
     * Runs on every selection change over up to 4,000 characters, so the
     * order is cost: one pass over the characters answers the script and
     * line questions, and every detector after it is behind a cheap shape
     * check (a colour starts with `#`, JSON with a brace) or behind
     * [options], which only asks for dates and places while a macro that
     * needs them is switched on.
     */
    fun detectContent(text: String, options: DetectOptions = DetectOptions()): ContentFlags {
        var hasLatin = false
        var hasBengali = false
        var hasDevanagari = false
        var hasForeignDigits = false
        var hasDigit = false
        var lines = 0
        var lineHasContent = false
        for (c in text) {
            when {
                c == '\n' -> {
                    if (lineHasContent) lines++
                    lineHasContent = false
                }
                c.isWhitespace() -> {}
                else -> {
                    lineHasContent = true
                    when {
                        c in 'a'..'z' || c in 'A'..'Z' -> hasLatin = true
                        c in '0'..'9' -> hasDigit = true
                        c.code in 0x0900..0x0963 || c.code in 0x0970..0x097F -> hasDevanagari = true
                        c.code in 0x0980..0x09FF -> {
                            hasBengali = true
                            if (c in '০'..'৯') {
                                hasForeignDigits = true
                                hasDigit = true
                            }
                        }
                        !hasForeignDigits && DigitScripts.digitValue(c) >= 0 -> {
                            hasForeignDigits = true
                            hasDigit = true
                        }
                    }
                }
            }
        }
        if (lineHasContent) lines++
        val trimmed = text.trim()
        val single = lines <= 1 && !trimmed.contains('\n')
        val colour = if (single && trimmed.length <= ColourCodes.MAX_LENGTH) ColourCodes.parse(trimmed) else null
        val jsonShape = if (JsonReformat.looksStructured(trimmed)) JsonReformat.shape(trimmed) else JsonReformat.Shape.NONE
        val base64 = single && trimmed.length >= 8 && TextCodecs.looksBase64(trimmed)
        val urlEncoded = TextCodecs.isUrlEncoded(trimmed)
        val hasTrackers = single && trimmed.contains('?') && stripTrackers(trimmed) != null
        val couldBeMoment = single && trimmed.length <= DateTimes.MAX_LENGTH &&
            (hasDigit || trimmed.firstOrNull()?.isLetter() == true)
        val dateTime = if (options.dateTime && couldBeMoment) {
            DateTimes.parse(trimmed, options.nowMillis, options.zone, options.locale)
        } else {
            null
        }
        val couldBePlace = single && trimmed.length <= Places.MAX_ADDRESS_LENGTH && hasDigit
        val place = if (options.place && couldBePlace) {
            Places.detect(trimmed) { candidate ->
                dateTime != null ||
                    (!options.dateTime && DateTimes.parse(candidate, options.nowMillis, options.zone, options.locale) != null)
            }
        } else {
            null
        }
        return ContentFlags(
            multiLine = lines >= 2,
            hasLatin = hasLatin,
            hasBengali = hasBengali,
            hasDevanagari = hasDevanagari,
            hasForeignDigits = hasForeignDigits,
            colour = colour,
            dateTime = dateTime,
            place = place,
            jsonShape = jsonShape,
            base64 = base64,
            urlEncoded = urlEncoded,
            hasTrackers = hasTrackers,
        )
    }
}
