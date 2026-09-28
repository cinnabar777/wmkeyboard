package com.wasimaster.wmkeyboard.ime

/**
 * Names of the trace sections the keyboard marks with androidx.tracing, so a
 * system trace shows its own work by name instead of as an anonymous stretch
 * of `Choreographer#doFrame`.
 *
 * Record one with `scripts/perfetto.sh` and open it at ui.perfetto.dev; the
 * sections sit on the thread that ran them, under the app's process. A section
 * costs a native flag check when nobody is tracing, which is why they can stay
 * in the release build, and why they mark whole operations rather than the
 * inner loops of one.
 *
 * All share the `WM:` prefix so a single `name like 'WM:%'` query finds them.
 */
internal object ImeTrace {
    /** One key press, from the grid's callback to the field edit it causes. */
    const val KEY = "WM:key"

    /** A strip refresh's main-thread half: gates, chips, launching the pass. */
    const val REFRESH_SUGGESTIONS = "WM:refreshSuggestions"

    /** The engine's ranked word list for the word being typed (background thread). */
    const val SUGGEST = "WM:suggest"

    /** One glide stroke decoded to candidate words (background thread). */
    const val GLIDE_DECODE = "WM:glideDecode"

    /** The editor told the keyboard its caret or composing span moved. */
    const val UPDATE_SELECTION = "WM:updateSelection"

    /** The keyboard window's Compose root being built. */
    const val CREATE_INPUT_VIEW = "WM:createInputView"

    /** A field gaining the keyboard: field classification, layout, strip reset. */
    const val START_INPUT_VIEW = "WM:startInputView"
}
