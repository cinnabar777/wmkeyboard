package com.wasimaster.wmkeyboard.benchmark

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/*
 * Driving the keyboard from a benchmark: switching the device to it, bringing
 * it up over a text field of the app's own, and typing on it.
 *
 * The keys are found by position rather than by name. They carry an
 * accessibility label only while a screen reader is on (touch exploration
 * changes how a key has to behave), so there is nothing for UiAutomator to look
 * up by text. Instead the keyboard's window is located through accessibility,
 * which works for any build and layout, and taps land at fractions of it that
 * sit on letter keys whether or not a number row is showing.
 */

internal const val TARGET_PACKAGE = "com.wasimaster.wmkeyboard"
private const val IME_ID = "$TARGET_PACKAGE/.ime.WMKeyboardService"
private const val WAIT_MS = 5_000L

/** Makes WM Keyboard the enabled, current input method. Idempotent. */
internal fun MacrobenchmarkScope.useWmKeyboard() {
    device.executeShellCommand("ime enable $IME_ID")
    device.executeShellCommand("ime set $IME_ID")
}

/**
 * Opens the settings app on its search field, which takes focus and so brings
 * the keyboard up. A fresh install lands on onboarding first, and is skipped
 * past it.
 */
internal fun MacrobenchmarkScope.openKeyboardOverSearch() {
    startActivityAndWait()
    device.findObject(By.text("Skip"))?.click()
    val search = device.wait(Until.findObject(By.desc("Search settings")), WAIT_MS)
        ?: error("No \"Search settings\" button: is the settings home screen showing?")
    search.click()
    checkNotNull(awaitKeyboard()) { "The keyboard did not come up over the search field" }
}

/** Waits for the input method window and returns its bounds on screen, or null. */
internal fun awaitKeyboard(): Rect? {
    val deadline = SystemClock.uptimeMillis() + WAIT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        keyboardBounds()?.let { return it }
        SystemClock.sleep(50)
    }
    return null
}

private fun keyboardBounds(): Rect? {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    // Other windows than the focused one are only listed with this flag.
    val info = automation.serviceInfo
    if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }
    val window = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?: return null
    return Rect().also(window::getBoundsInScreen).takeIf { !it.isEmpty }
}

/**
 * Letter positions as fractions of the keyboard window: two rows well inside
 * the letter block, avoiding the strip on top and the space row underneath.
 */
private val LETTER_ROWS = floatArrayOf(0.46f, 0.64f)
private val LETTER_COLUMNS = floatArrayOf(0.15f, 0.35f, 0.55f, 0.75f, 0.85f, 0.25f, 0.45f, 0.65f)
private const val SPACE_ROW = 0.93f

/** Types [words] words of [letters] taps each, a space after every one. */
internal fun MacrobenchmarkScope.typeWords(words: Int = 4, letters: Int = 5) {
    val bounds = checkNotNull(awaitKeyboard()) { "The keyboard is not showing" }
    var n = 0
    repeat(words) {
        repeat(letters) {
            val row = LETTER_ROWS[n % LETTER_ROWS.size]
            val column = LETTER_COLUMNS[n % LETTER_COLUMNS.size]
            device.click(at(bounds, column, row).x, at(bounds, column, row).y)
            n++
        }
        device.click(at(bounds, 0.5f, SPACE_ROW).x, at(bounds, 0.5f, SPACE_ROW).y)
    }
    device.waitForIdle()
}

/** Draws [strokes] glide strokes across the letter rows, zig-zagging like a word would. */
internal fun MacrobenchmarkScope.glideWords(strokes: Int = 2) {
    val bounds = checkNotNull(awaitKeyboard()) { "The keyboard is not showing" }
    repeat(strokes) { i ->
        val points = arrayOf(
            at(bounds, 0.12f + i * 0.05f, 0.46f),
            at(bounds, 0.40f, 0.64f),
            at(bounds, 0.62f, 0.46f),
            at(bounds, 0.84f - i * 0.05f, 0.64f),
        )
        // ~10 ms per step: a glide at a brisk but human pace.
        device.swipe(points, 12)
        device.waitForIdle()
    }
}

private fun at(bounds: Rect, x: Float, y: Float) =
    Point(bounds.left + (bounds.width() * x).toInt(), bounds.top + (bounds.height() * y).toInt())
