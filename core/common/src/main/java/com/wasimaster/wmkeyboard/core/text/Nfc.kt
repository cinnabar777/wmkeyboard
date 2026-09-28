package com.wasimaster.wmkeyboard.core.text

/**
 * Unicode Normalization Form C, through the platform ICU's `Normalizer2`.
 *
 * Exists for its fast path. `java.text.Normalizer.isNormalized` on Android is
 * a wrapper that allocates on every call, and the prediction stores ask the
 * question for every word they look up, several times per keystroke (see
 * `WordKey`). `Normalizer2.spanQuickCheckYes` answers the overwhelmingly common
 * case — text that is already NFC — by scanning ICU's quick-check table in
 * place, allocating nothing; only text it cannot vouch for goes on to the real
 * normaliser.
 *
 * Off-device it is `java.text.Normalizer` throughout, which gives the same
 * answers; see [PlatformIcu].
 */
object Nfc {

    /** Whether [text] is already in NFC. Allocation-free on a phone when it is. */
    fun isNormalized(text: CharSequence): Boolean {
        if (!PlatformIcu.available) return java.text.Normalizer.isNormalized(text, java.text.Normalizer.Form.NFC)
        val nfc = android.icu.text.Normalizer2.getNFCInstance()
        // A span covering the whole text is a definite yes; a shorter one means
        // "maybe or no" from the quick check, which only the full test settles.
        return nfc.spanQuickCheckYes(text) == text.length || nfc.isNormalized(text)
    }

    /** [text] in NFC, the same instance when it already was. */
    fun normalize(text: String): String {
        if (isNormalized(text)) return text
        return if (PlatformIcu.available) {
            android.icu.text.Normalizer2.getNFCInstance().normalize(text)
        } else {
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        }
    }
}
