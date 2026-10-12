package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MinimalTools
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.PersonaPrivacy
import com.wasimaster.wmkeyboard.core.settings.PowerTools
import com.wasimaster.wmkeyboard.core.settings.RecommendedTools
import com.wasimaster.wmkeyboard.core.settings.SpaceSwipeAction
import com.wasimaster.wmkeyboard.core.settings.ToolTopUps
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Every wizard step, in the order they're asked. Which ones actually appear is
 * decided per device, per persona and per run — see [onboardingPages].
 */
internal enum class OnboardingPage {
    APP_LANGUAGE, WELCOME, PERSONA, LANGUAGES, LOOK, EMOJI, FEEDBACK, GESTURES,
    DISCOVER, TOOLS, TOOL_SETUP, TRY,
}

/** Tools with a first-run choice worth asking about on the setup page. */
internal val ToolSetupTools = setOf(
    ToolbarTool.CALENDAR, ToolbarTool.WEATHER, ToolbarTool.COMPASS,
)

/**
 * The wizard's live page list.
 *
 * The persona answers *grow* it. An unanswered quiz is the short path — the
 * same one "keep it simple" gets — because the progress row at the top is the
 * first thing anyone sees, and eleven steps on a screen where nothing has been
 * asked yet is the whole wizard reading as a chore. Answering "a good middle"
 * or "show me everything" adds the pages that answer earns, which the progress
 * row animates in.
 *
 * The emoji page is one of those the depth answer earns. Both halves of it —
 * the default skin tone and the emoji-font repair — are appearance questions
 * about a panel the short path hasn't opened yet, and both are asked again
 * under Languages & emoji; the tone in particular is only worth a wizard page
 * to someone who came here to tune things. On a replay the welcome page only
 * appears when the keyboard actually needs setting up again.
 *
 * The app-language page comes before everything, welcome included, because
 * every page after it is words: someone who reads Bengali first should read
 * the rest of the wizard in Bengali. [askAppLanguage] is decided by the phone's
 * region (see [shouldAskAppLanguage]), so it never shows where English is the
 * first language, and never on a build that has only English to offer.
 *
 * [restored] is a run where the welcome page's restore brought a backup in:
 * the welcome page is all that is left.
 */
internal fun onboardingPages(
    persona: OnboardingSettings,
    enabledTools: Collection<ToolbarTool>,
    enabledLanguageCount: Int,
    replay: Boolean,
    imeReady: Boolean,
    askAppLanguage: Boolean = false,
    restored: Boolean = false,
): List<OnboardingPage> = OnboardingPage.entries.filter { page ->
    // A restored backup answered every question already: only the step no
    // backup can carry, turning the keyboard on, is left.
    if (restored) return@filter page == OnboardingPage.WELCOME
    when (page) {
        OnboardingPage.APP_LANGUAGE -> askAppLanguage
        OnboardingPage.WELCOME -> !(replay && imeReady)
        // Unanswered means one language, the same as answering "just one":
        // most people type in one, the page seeds itself from the phone's own
        // languages anyway, and "two or more" brings the page back. A second
        // language already enabled overrides all of that — there is something
        // to manage, so the page has something to say.
        OnboardingPage.LANGUAGES ->
            persona.personaLanguages == PersonaLanguages.MANY || enabledLanguageCount > 1
        OnboardingPage.EMOJI, OnboardingPage.FEEDBACK, OnboardingPage.GESTURES ->
            persona.personaDepth == PersonaDepth.BALANCED ||
                persona.personaDepth == PersonaDepth.POWER
        OnboardingPage.TOOLS -> persona.personaDepth == PersonaDepth.POWER
        OnboardingPage.TOOL_SETUP ->
            persona.personaDepth == PersonaDepth.POWER &&
                enabledTools.any { it in ToolSetupTools }
        else -> true
    }
}

/**
 * Where [current] sits in [pages]. A page can disappear under the user (a
 * persona re-answer hiding the page they are on, or the last of two languages
 * being turned off); fall back to the nearest earlier page that survived
 * rather than snapping to the start.
 */
internal fun resolvePageIndex(pages: List<OnboardingPage>, current: OnboardingPage): Int =
    pages.indexOf(current).let { found ->
        if (found >= 0) found else pages.indexOfLast { it < current }.coerceAtLeast(0)
    }

/**
 * Accent colour per page, matching the route colours the settings screens use
 * ([SettingsRouteColors]) so the wizard reads as part of the same app.
 */
internal val OnboardingPageAccents: Map<OnboardingPage, Color> = mapOf(
    OnboardingPage.APP_LANGUAGE to Color(0xFF5C6BC0),
    OnboardingPage.WELCOME to Color(0xFF42A5F5),
    OnboardingPage.PERSONA to Color(0xFF7E57C2),
    OnboardingPage.LANGUAGES to Color(0xFF66BB6A),
    OnboardingPage.LOOK to Color(0xFFEC407A),
    OnboardingPage.EMOJI to Color(0xFFFFB300),
    OnboardingPage.FEEDBACK to Color(0xFF7E57C2),
    OnboardingPage.GESTURES to Color(0xFF26C6DA),
    OnboardingPage.DISCOVER to Color(0xFF00ACC1),
    OnboardingPage.TOOLS to Color(0xFFFF7043),
    OnboardingPage.TOOL_SETUP to Color(0xFFFF7043),
    OnboardingPage.TRY to Color(0xFF26A69A),
)

/**
 * One combined choice for the two spacebar-swipe slots, so the wizard asks
 * a single question; the two independent settings stay available under
 * Typing → Gestures.
 */
internal enum class SpacebarChoice(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val short: SpaceSwipeAction,
    val long: SpaceSwipeAction,
) {
    CURSOR_THEN_LANGUAGE(
        R.string.onboarding_spacebar_cursor_language_title,
        R.string.onboarding_spacebar_cursor_language_subtitle,
        SpaceSwipeAction.CURSOR, SpaceSwipeAction.LANGUAGE,
    ),
    LANGUAGE_THEN_CURSOR(
        R.string.onboarding_spacebar_language_cursor_title,
        R.string.onboarding_spacebar_language_cursor_subtitle,
        SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.CURSOR,
    ),
    LANGUAGE_ONLY(
        R.string.onboarding_spacebar_language_only_title,
        R.string.onboarding_spacebar_language_only_subtitle,
        SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.LANGUAGE,
    ),
    CURSOR_ONLY(
        R.string.onboarding_spacebar_cursor_only_title,
        R.string.onboarding_spacebar_cursor_only_subtitle,
        SpaceSwipeAction.CURSOR, SpaceSwipeAction.CURSOR,
    ),
    OFF(
        R.string.onboarding_spacebar_off_title,
        R.string.onboarding_spacebar_off_subtitle,
        SpaceSwipeAction.NONE, SpaceSwipeAction.NONE,
    ),
}

/**
 * Which spacebar choice the gestures page badges as recommended. Follows the
 * persona: with several languages both swipes went to language switching, with
 * one language there is nothing to switch, and an unanswered quiz gets the
 * shipped default.
 */
internal fun recommendedSpacebarChoice(persona: OnboardingSettings): SpacebarChoice =
    when (persona.personaLanguages) {
        PersonaLanguages.MANY -> SpacebarChoice.LANGUAGE_ONLY
        PersonaLanguages.ONE -> SpacebarChoice.CURSOR_ONLY
        PersonaLanguages.UNSET -> SpacebarChoice.LANGUAGE_THEN_CURSOR
    }

/**
 * One settings write a persona answer stands for. Kept as data rather than
 * done inline so the answer-to-write mapping is a pure function tests can
 * check, and the page applies whatever the mapping says.
 */
internal sealed interface PresetWrite {
    data class SpaceSwipes(val short: SpaceSwipeAction, val long: SpaceSwipeAction) : PresetWrite
    data class GlobeAsEmoji(val value: Boolean) : PresetWrite
    data class LearnFromTyping(val value: Boolean) : PresetWrite
    data class ClipboardHistory(val value: Boolean) : PresetWrite
    data class TypingStats(val value: Boolean) : PresetWrite
}

/**
 * The defaults a language answer applies. With several languages both swipe
 * slots switch language (the persona page says so in a notice); with one, the
 * swipes go to the cursor and the globe key becomes an emoji key, because a
 * language switcher with nothing to switch is a dead key.
 */
internal fun presetsFor(answer: PersonaLanguages): List<PresetWrite> = when (answer) {
    PersonaLanguages.MANY -> listOf(
        PresetWrite.SpaceSwipes(SpaceSwipeAction.LANGUAGE, SpaceSwipeAction.LANGUAGE),
        PresetWrite.GlobeAsEmoji(false),
    )
    PersonaLanguages.ONE -> listOf(
        PresetWrite.SpaceSwipes(SpaceSwipeAction.CURSOR, SpaceSwipeAction.CURSOR),
        PresetWrite.GlobeAsEmoji(true),
    )
    PersonaLanguages.UNSET -> emptyList()
}

/**
 * The tool set a persona lands, or null while the depth question is still
 * unanswered and there is nothing to seed.
 *
 * Derived from the whole persona rather than from the depth answer alone,
 * because two of the three answers move it: depth picks the set, and strict
 * privacy adds incognito to whichever set that is. Recomputing from the
 * combined answers on every change is what makes the pair order-independent —
 * an additive write would be undone by a later `setEnabledTools`.
 *
 * [isSupported] drops what this build or device cannot run, and the set is
 * topped up when that (or a device without Google services) shortens it — see
 * [ToolTopUps].
 */
internal fun starterTools(
    persona: OnboardingSettings,
    playServices: Boolean,
    isSupported: (ToolbarTool) -> Boolean,
): Set<ToolbarTool>? {
    val base = when (persona.personaDepth) {
        PersonaDepth.MINIMAL -> MinimalTools
        PersonaDepth.BALANCED -> RecommendedTools
        PersonaDepth.POWER -> PowerTools
        PersonaDepth.UNSET -> return null
    }
    val supported = base.filterTo(LinkedHashSet(), isSupported)
    val topped =
        if (supported.size < base.size || !playServices) {
            supported + ToolTopUps.filter(isSupported)
        } else {
            supported
        }
    // The persona that reaches for incognito is the one that asked for strict
    // privacy; it is the one tool that belongs in every set for that answer.
    return if (persona.personaPrivacy == PersonaPrivacy.STRICT) {
        topped + ToolbarTool.INCOGNITO
    } else {
        topped
    }
}

/**
 * What the strict privacy answer turns off. Incognito in private fields is
 * already on for everyone, so it is not repeated here; STANDARD writes nothing
 * because the defaults already are the standard behaviour.
 */
internal fun presetsFor(answer: PersonaPrivacy): List<PresetWrite> = when (answer) {
    PersonaPrivacy.STRICT -> listOf(
        PresetWrite.LearnFromTyping(false),
        PresetWrite.ClipboardHistory(false),
        PresetWrite.TypingStats(false),
    )
    PersonaPrivacy.STANDARD, PersonaPrivacy.UNSET -> emptyList()
}

/**
 * The tool set to land when the wizard closes, or null when the enabled set
 * needs no touch. Skipping from the first page used to leave every one of the
 * 70+ tools enabled, because only the tools page applied the starter set; now
 * finishing over the untouched everything-on default lands the persona's set
 * no matter which page the wizard closed from.
 *
 * An unanswered quiz gets the middle set, which is also the page order's
 * assumption everywhere else.
 */
internal fun toolsAfterFinish(
    settings: KeyboardSettings,
    playServices: Boolean,
    isSupported: (ToolbarTool) -> Boolean,
): Set<ToolbarTool>? {
    if (settings.enabledTools.toSet() != ToolbarTool.entries.toSet()) return null
    val persona = settings.onboarding.takeIf { it.personaDepth != PersonaDepth.UNSET }
        ?: settings.onboarding.copy(personaDepth = PersonaDepth.BALANCED)
    return starterTools(persona, playServices, isSupported)
}
