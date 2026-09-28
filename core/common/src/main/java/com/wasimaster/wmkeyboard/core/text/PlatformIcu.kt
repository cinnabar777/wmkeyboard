package com.wasimaster.wmkeyboard.core.text

/**
 * Whether the platform's ICU (`android.icu`, API 24+) can actually be called.
 *
 * On a phone it always can. The question exists for the JVM: `:app`'s unit
 * tests compile against `android.jar`, whose `android.icu` classes are stubs
 * that throw "not mocked" (or return null) the moment one is used, and most of
 * the code that reaches the helpers in this package is tested there. Rather
 * than make every one of those tests Robolectric, each helper falls back to
 * the JDK's own `java.text` implementation off-device — which on the JDK 21
 * the tests run on implements the same Unicode rules, and which is what these
 * call sites used before `android.icu` was.
 *
 * Probed once, by constructing the objects the helpers need; the answer cannot
 * change for the life of the process.
 */
internal object PlatformIcu {

    /** Tests flip this to exercise the fallback on a runtime that has ICU. */
    @Volatile
    var available: Boolean = probe()

    private fun probe(): Boolean = runCatching {
        android.icu.text.Normalizer2.getNFCInstance() != null &&
            android.icu.text.BreakIterator.getCharacterInstance() != null
    }.getOrDefault(false)
}
