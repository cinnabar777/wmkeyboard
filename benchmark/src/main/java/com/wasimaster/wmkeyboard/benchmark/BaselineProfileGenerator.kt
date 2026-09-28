package com.wasimaster.wmkeyboard.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which of the app's methods run on the paths people actually use, so
 * ART can compile them at install instead of interpreting them on every cold
 * start of the keyboard process.
 *
 *     ./gradlew :app:generateBaselineProfile -Pwmkb.benchmark=true
 *
 * The rules land in app/src/main/baselineProfiles/ and are committed. The
 * hand-written wildcard rules in app/src/main/baseline-prof.txt stay: they
 * cover what a journey here does not reach, and AGP merges the two.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun keyboardAndSettings() = rule.collect(
        packageName = TARGET_PACKAGE,
        // The same journey is the startup profile: its first part is what a
        // cold start of the settings app and of the keyboard runs.
        includeInStartupProfile = true,
    ) {
        pressHome()
        useWmKeyboard()
        openKeyboardOverSearch()
        typeWords()
        glideWords()
        typeWords(words = 2)
        device.pressBack()
        pressHome()
    }
}
