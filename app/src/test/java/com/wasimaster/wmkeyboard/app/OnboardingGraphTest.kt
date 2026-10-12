package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingGraphTest {

    private fun pages(
        persona: OnboardingSettings = OnboardingSettings(),
        enabledTools: Collection<ToolbarTool> = emptyList(),
        enabledLanguageCount: Int = 2,
        replay: Boolean = false,
        imeReady: Boolean = false,
    ) = onboardingPages(persona, enabledTools, enabledLanguageCount, replay, imeReady)

    @Test
    fun `fresh run with unanswered quiz takes the short path`() {
        assertEquals(
            listOf(
                OnboardingPage.WELCOME, OnboardingPage.PERSONA, OnboardingPage.LOOK,
                OnboardingPage.DISCOVER, OnboardingPage.TRY,
            ),
            pages(enabledLanguageCount = 1),
        )
    }

    @Test
    fun `a second enabled language brings the languages page back unasked`() {
        assertTrue(OnboardingPage.LANGUAGES in pages(enabledLanguageCount = 2))
    }

    @Test
    fun `answering only ever adds pages`() {
        val unanswered = pages(enabledLanguageCount = 1)
        for (depth in PersonaDepth.entries) {
            val answered = pages(
                persona = OnboardingSettings(personaDepth = depth),
                enabledLanguageCount = 1,
            )
            assertTrue(
                "$depth dropped a page the unanswered quiz shows",
                answered.containsAll(unanswered),
            )
        }
        assertTrue(
            pages(
                persona = OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
                enabledLanguageCount = 1,
            ).size > unanswered.size,
        )
        // The language answer is the other half of the same rule.
        assertTrue(
            pages(
                persona = OnboardingSettings(personaLanguages = PersonaLanguages.MANY),
                enabledLanguageCount = 1,
            ).containsAll(unanswered + OnboardingPage.LANGUAGES),
        )
    }

    @Test
    fun `minimal persona skips the fine-tuning pages`() {
        val result = pages(persona = OnboardingSettings(personaDepth = PersonaDepth.MINIMAL))
        assertFalse(OnboardingPage.EMOJI in result)
        assertFalse(OnboardingPage.FEEDBACK in result)
        assertFalse(OnboardingPage.GESTURES in result)
        assertFalse(OnboardingPage.TOOLS in result)
        assertFalse(OnboardingPage.TOOL_SETUP in result)
    }

    @Test
    fun `power persona gets the tools page`() {
        val result = pages(persona = OnboardingSettings(personaDepth = PersonaDepth.POWER))
        assertTrue(OnboardingPage.TOOLS in result)
        assertTrue(OnboardingPage.FEEDBACK in result)
    }

    @Test
    fun `tool setup needs power and a tool with choices`() {
        val power = OnboardingSettings(personaDepth = PersonaDepth.POWER)
        assertTrue(
            OnboardingPage.TOOL_SETUP in
                pages(persona = power, enabledTools = listOf(ToolbarTool.WEATHER)),
        )
        assertFalse(
            OnboardingPage.TOOL_SETUP in
                pages(persona = power, enabledTools = listOf(ToolbarTool.EMOJI)),
        )
        // Balanced users with the calendar tool on still skip it: the page is
        // power-user territory now.
        assertFalse(
            OnboardingPage.TOOL_SETUP in
                pages(
                    persona = OnboardingSettings(personaDepth = PersonaDepth.BALANCED),
                    enabledTools = listOf(ToolbarTool.CALENDAR),
                ),
        )
    }

    @Test
    fun `single-language persona hides the languages page until a second language exists`() {
        val one = OnboardingSettings(personaLanguages = PersonaLanguages.ONE)
        assertFalse(OnboardingPage.LANGUAGES in pages(persona = one, enabledLanguageCount = 1))
        assertTrue(OnboardingPage.LANGUAGES in pages(persona = one, enabledLanguageCount = 2))
    }

    // The emoji page is fine-tuning, not setup: the skin tone and the font
    // repair are both asked again under Languages & emoji, so only the two
    // deeper answers earn it a place in the wizard.
    @Test
    fun `emoji page needs a depth answer past keep-it-simple`() {
        for (depth in listOf(PersonaDepth.UNSET, PersonaDepth.MINIMAL)) {
            assertFalse(
                "$depth kept the emoji page",
                OnboardingPage.EMOJI in pages(persona = OnboardingSettings(personaDepth = depth)),
            )
        }
        for (depth in listOf(PersonaDepth.BALANCED, PersonaDepth.POWER)) {
            assertTrue(
                "$depth lost the emoji page",
                OnboardingPage.EMOJI in pages(persona = OnboardingSettings(personaDepth = depth)),
            )
        }
    }

    @Test
    fun `replay hides welcome only while the keyboard is ready`() {
        assertFalse(OnboardingPage.WELCOME in pages(replay = true, imeReady = true))
        assertTrue(OnboardingPage.WELCOME in pages(replay = true, imeReady = false))
        assertTrue(OnboardingPage.WELCOME in pages(replay = false, imeReady = true))
    }

    @Test
    fun `app language page leads the wizard only when asked for`() {
        assertFalse(OnboardingPage.APP_LANGUAGE in pages())
        val asked = onboardingPages(
            OnboardingSettings(), emptyList(), 1, replay = false, imeReady = false, askAppLanguage = true,
        )
        assertEquals(OnboardingPage.APP_LANGUAGE, asked.first())
        assertEquals(OnboardingPage.WELCOME, asked[1])
    }

    @Test
    fun `a restored backup leaves only the welcome page`() {
        val power = OnboardingSettings(personaDepth = PersonaDepth.POWER, personaLanguages = PersonaLanguages.MANY)
        val restored = onboardingPages(
            power, ToolbarTool.entries, 3, replay = false, imeReady = false, askAppLanguage = true, restored = true,
        )
        assertEquals(listOf(OnboardingPage.WELCOME), restored)
    }

    @Test
    fun `resolvePageIndex finds the current page`() {
        val list = pages()
        assertEquals(2, resolvePageIndex(list, OnboardingPage.LANGUAGES))
    }

    @Test
    fun `resolvePageIndex falls back to the nearest earlier survivor`() {
        val list = listOf(
            OnboardingPage.WELCOME, OnboardingPage.PERSONA,
            OnboardingPage.LOOK, OnboardingPage.TRY,
        )
        // GESTURES vanished; LOOK is the nearest earlier page still present.
        assertEquals(2, resolvePageIndex(list, OnboardingPage.GESTURES))
        // Nothing earlier survives: land on the first page.
        val tail = listOf(OnboardingPage.LOOK, OnboardingPage.TRY)
        assertEquals(0, resolvePageIndex(tail, OnboardingPage.WELCOME))
    }

    @Test
    fun `every page has an accent`() {
        for (page in OnboardingPage.entries) {
            assertTrue("$page has no accent", OnboardingPageAccents.containsKey(page))
        }
    }
}
