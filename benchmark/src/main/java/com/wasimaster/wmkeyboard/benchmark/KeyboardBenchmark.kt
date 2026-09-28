package com.wasimaster.wmkeyboard.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.TraceSectionMetric.Mode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard itself: how long it takes to come up in a process that was not
 * running, and what typing and gliding cost per frame and per keystroke.
 *
 * The per-keystroke numbers come from the keyboard's own trace sections
 * (feature/ime/.../ime/ImeTrace.kt), summed over each iteration, so a
 * regression shows up as the section that grew rather than only as more jank.
 *
 *     ./gradlew :benchmark:connectedFullIntlBenchmarkReleaseAndroidTest \
 *         -Pwmkb.benchmark=true \
 *         -Pandroid.testInstrumentationRunnerArguments.class=com.wasimaster.wmkeyboard.benchmark.KeyboardBenchmark
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class KeyboardBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /** The keyboard's first show in a fresh process: its window, then the field. */
    @Test
    fun coldShow() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            TraceSectionMetric("WM:createInputView", Mode.First),
            TraceSectionMetric("WM:startInputView", Mode.First),
            FrameTimingMetric(),
        ),
        compilationMode = PROFILED,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            useWmKeyboard()
        },
    ) {
        openKeyboardOverSearch()
    }

    /** Tapping out words: frame times, and the keystroke and strip sections. */
    @Test
    fun typing() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("WM:key", Mode.Sum),
            TraceSectionMetric("WM:refreshSuggestions", Mode.Sum),
            TraceSectionMetric("WM:suggest", Mode.Sum),
        ),
        compilationMode = PROFILED,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            useWmKeyboard()
            openKeyboardOverSearch()
        },
    ) {
        typeWords()
    }

    /** Gliding: frame times and the decoder's own time. */
    @Test
    fun gliding() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("WM:glideDecode", Mode.Sum),
        ),
        compilationMode = PROFILED,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            useWmKeyboard()
            openKeyboardOverSearch()
        },
    ) {
        glideWords(strokes = 4)
    }

    private companion object {
        const val ITERATIONS = 5

        /** What an install from a store gets: the baseline profile, compiled. */
        val PROFILED = CompilationMode.Partial(BaselineProfileMode.Require)
    }
}
