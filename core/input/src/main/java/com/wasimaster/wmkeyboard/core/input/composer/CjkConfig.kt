package com.wasimaster.wmkeyboard.core.input.composer

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.input.R

/**
 * Runtime CJK input options the composers read at call time. Mirrors
 * [CjkDictionaries]: the composers stay parameter-less singletons (so
 * `composerFor` needs no new arguments and the `_uiState.copy` sites are
 * untouched), and the service pushes the user's settings in here from the same
 * `settings.collect` block that already feeds the suggestion engine.
 *
 * Every option here changes what the composers rank, so each one invalidates the
 * cached rankings on assignment — see [CjkDictionaries.generation].
 */
object CjkConfig {

    /**
     * Fuzzy Pinyin: treat commonly-confused initials/finals as equivalent
     * (zh↔z, an↔ang, …) so an imprecise spelling still finds its characters.
     */
    @Volatile
    var fuzzyPinyin: Boolean = false
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * Which of [PinyinFuzzy.PAIRS] the switch above applies. All eleven by
     * default, which is what Fuzzy Pinyin meant before the groups could be
     * picked apart.
     *
     * Sogou and Google Pinyin both let a user take an↔ang without taking n↔l,
     * and the two are not the same request: the nasal endings are a regional
     * accent, n↔l costs precision on every syllable that starts with either.
     */
    @Volatile
    var fuzzyPinyinPairs: Set<String> = PinyinFuzzy.ALL_PAIRS
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * Jianpin (简拼): a bare initial stands for any syllable that starts with
     * it, so `wm` finds 我们 and `haod` finds 好的. See [Jianpin]. On by default,
     * as it is in every shipping Chinese IME; ignored under Double Pinyin, where
     * a syllable is always exactly two keys.
     */
    @Volatile
    var jianpin: Boolean = true
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * The Double Pinyin scheme in use, or [DoublePinyinScheme.OFF] for full
     * Pinyin. When set, each syllable is two keys that the composer expands to
     * full Pinyin before the normal segmentation runs.
     */
    @Volatile
    var doublePinyin: DoublePinyinScheme = DoublePinyinScheme.OFF
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * Convert candidate output to Traditional characters. Read by [HanVariant]
     * inside each composer's ranking, never applied to a finished candidate list —
     * see [HanVariant] for why that distinction is load-bearing.
     */
    @Volatile
    var traditionalOutput: Boolean = false
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * Cantonese lazy pronunciation (懶音): match the sound mergers most Hong Kong
     * speakers have, so a syllable typed the way it is said still finds its
     * characters. See [JyutpingFuzzy].
     */
    @Volatile
    var lazyJyutping: Boolean = false
        set(value) { field = value; CjkDictionaries.invalidate() }

    /**
     * Japanese: read a plain kana typed on a kana layout as its small, dakuten or
     * handakuten form too, so かつこう finds 学校 (がっこう) without the extra
     * 小゛゜ presses. See [JapaneseComposer].
     */
    @Volatile
    var looseKanaMarks: Boolean = false
        set(value) { field = value; CjkDictionaries.invalidate() }
}

/**
 * Double Pinyin layouts: every syllable is exactly two keystrokes — an initial
 * key and a final key. [OFF] is ordinary full Pinyin. Ordinals are append-only
 * (the setting persists by [name], but keep them stable regardless).
 *
 * [displayNameRes] is a resource id rather than a string: the enum has no
 * context and no locale of its own, so the settings UI resolves the label.
 */
enum class DoublePinyinScheme(@StringRes val displayNameRes: Int) {
    OFF(R.string.core_input_double_pinyin_off_label),
    MICROSOFT(R.string.core_input_double_pinyin_microsoft_label),
    SOGOU(R.string.core_input_double_pinyin_sogou_label),
    XIAOHE(R.string.core_input_double_pinyin_xiaohe_label),
    ZIRANMA(R.string.core_input_double_pinyin_ziranma_label),
    PINYINPP(R.string.core_input_double_pinyin_pinyinpp_label),
}
