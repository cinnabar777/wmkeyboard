package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Typing a slider's value (#415): the number the readout shows goes back
 * through the row's own `display` to the value underneath.
 */
class SliderEntryTest {

    private val english = DecimalFormatSymbols.getInstance(Locale.US)
    private val german = DecimalFormatSymbols.getInstance(Locale.GERMANY)

    private fun number(text: String, symbols: DecimalFormatSymbols = english) =
        readoutNumber(text, symbols)?.value

    private fun entry(range: ClosedFloatingPointRange<Float>, display: (Float) -> String) =
        SliderEntry(range, display, english)

    @Test
    fun `reads the number out of a readout and keeps its span`() {
        assertEquals(ReadoutNumber(300.0, 0, 3), readoutNumber("300 ms", english))
        assertEquals(ReadoutNumber(1.2, 1, 4), readoutNumber("×1.2", english))
        assertEquals(35.0, number("35%"))
        assertEquals(-2.0, number("-2 days"))
        assertEquals(-2.0, number("−2 days"))
        assertEquals(1.0, number("+1 day"))
        assertNull(number("Off"))
    }

    @Test
    fun `a separator before three digits groups, any other is a point`() {
        assertEquals(2000.0, number("2,000 characters"))
        assertEquals(1.25, number("1.250"))
        assertEquals(1.5, number("1,5", german))
        assertEquals(1.5, number("1.5", german))
        assertEquals(2000.0, number("2.000", german))
        // A trailing full stop ends the number, it does not start a fraction.
        assertEquals(12.0, number("12."))
    }

    @Test
    fun `digits in any script count`() {
        assertEquals(123.0, number("١٢٣"))
        assertEquals(45.0, number("৪৫ ms"))
    }

    @Test
    fun `a readout that is the value lands on it exactly`() {
        val ms = entry(100f..800f) { "${it.toInt()} ms" }
        assertEquals(347f, ms.valueFor(347.0))
        assertEquals(100f, ms.valueFor(100.0))
        assertEquals(800f, ms.valueFor(800.0))
    }

    @Test
    fun `a percentage lands on the fraction`() {
        val percent = entry(0f..1f) { "${(it * 100).toInt()}%" }
        assertEquals(0.35f, percent.valueFor(35.0))
    }

    @Test
    fun `seconds shown with a point land on the milliseconds`() {
        val seconds = entry(0f..5000f) { "%.1f s".format(Locale.US, it / 1000f) }
        assertEquals(1500f, seconds.valueFor(1.5))
        assertTrue(seconds.fractional)
    }

    @Test
    fun `a value between steps lands on the nearest step`() {
        val stepped = entry(0f..1000f) { "${(it / 50f).roundToInt() * 50} ms" }
        val at = stepped.valueFor(310.0)!!
        assertEquals(300, (at / 50f).roundToInt() * 50)
    }

    @Test
    fun `a scaled readout with no clean inverse still reads back`() {
        // The key sound's 0..255 volume shown as a percentage.
        val volume = entry(0f..255f) { "${it.roundToInt() * 100 / 255}%" }
        val at = volume.valueFor(50.0)!!
        assertEquals(50, at.roundToInt() * 100 / 255)
    }

    @Test
    fun `beyond either end is refused`() {
        val ms = entry(100f..800f) { "${it.toInt()} ms" }
        assertNull(ms.valueFor(99.0))
        assertNull(ms.valueFor(801.0))
    }

    @Test
    fun `a word at one end is left to the thumb`() {
        val gap = entry(0f..20f) { if (it.toInt() == 0) "Off" else "${it.toInt()} dp" }
        assertEquals(1f, gap.lowest!!.toInt().toFloat())
        assertNull(gap.valueFor(0.0))
        assertEquals(20f, gap.highest)
        assertFalse(gap.fractional)
    }

    @Test
    fun `a negative track asks for a minus`() {
        val days = entry(-2f..2f) { "${it.roundToInt()} days" }
        assertTrue(days.negative)
        assertEquals(-1f, days.valueFor(-1.0))
    }
}
