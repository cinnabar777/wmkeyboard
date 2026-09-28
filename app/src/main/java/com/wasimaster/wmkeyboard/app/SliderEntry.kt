package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import java.text.DecimalFormatSymbols
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextDown
import kotlin.math.nextUp
import com.wasimaster.wmkeyboard.common.R as CommonR

/** The number inside a slider's readout, and where it sits in the text. */
internal data class ReadoutNumber(val value: Double, val start: Int, val end: Int)

/**
 * The first number in [text]: "300 ms", "35%", "×1.2", "+1 day", "2,000
 * characters", in any script's digits.
 *
 * A separator followed by a digit is a decimal point when it is the locale's
 * own, a thousands separator when exactly three digits follow it, and a decimal
 * point otherwise. So "1,5" reads as one and a half in German and "1.5" still
 * does too, while "2,000" in English and "2.000" in German are both two
 * thousand. The same reading serves the readouts, which the app formats in the
 * locale, and what the user types, which may be in either convention.
 */
internal fun readoutNumber(
    text: String,
    symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(),
): ReadoutNumber? {
    val first = text.indexOfFirst { Character.digit(it, 10) >= 0 }
    if (first < 0) return null
    val negative = first > 0 && text[first - 1] in "-−"
    val digits = StringBuilder(if (negative) "-" else "")
    var decimal = false
    var i = first
    while (i < text.length) {
        val c = text[i]
        val digit = Character.digit(c, 10)
        if (digit >= 0) {
            digits.append('0' + digit)
            i++
            continue
        }
        val separator = c == '.' || c == ',' || c == symbols.decimalSeparator || c == symbols.groupingSeparator
        if (!separator || decimal) break
        var run = 0
        while (i + 1 + run < text.length && Character.digit(text[i + 1 + run], 10) >= 0) run++
        if (run == 0) break
        when {
            c == symbols.decimalSeparator -> decimal = true
            run == 3 -> {}
            c == '.' || c == ',' -> decimal = true
            else -> break
        }
        if (decimal) digits.append('.')
        i++
    }
    val value = digits.toString().toDoubleOrNull() ?: return null
    return ReadoutNumber(value, if (negative) first - 1 else first, i)
}

/**
 * Turns a typed number back into a slider position, through the row's own
 * `display`, so every [SliderSetting] can take a typed value without its
 * caller saying how the readout relates to the value underneath: "35%" may be
 * 0.35 or 35, "1.5 s" may be 1500, a count may snap to steps of 50.
 *
 * The number typed is the number the readout shows. A slider whose readout
 * changes unit along the track ("12 hours", then "1 day") cannot be typed into
 * this way, and its row turns typing off.
 */
internal class SliderEntry(
    private val range: ClosedFloatingPointRange<Float>,
    private val display: (Float) -> String,
    private val symbols: DecimalFormatSymbols = DecimalFormatSymbols.getInstance(),
) {
    /** The track at [SAMPLES] even steps, each with the number its readout shows. */
    private val samples: List<Pair<Float, Double>> = (0..SAMPLES).mapNotNull { step ->
        val at = if (step == SAMPLES) {
            range.endInclusive
        } else {
            range.start + (range.endInclusive - range.start) * step / SAMPLES
        }
        readoutNumber(display(at), symbols)?.let { at to it.value }
    }

    /** The slider position whose readout shows the smallest number, null when none shows one. */
    val lowest: Float? = samples.minByOrNull { it.second }?.first

    /** The slider position whose readout shows the largest number. */
    val highest: Float? = samples.maxByOrNull { it.second }?.first

    /** Whether any readout has digits after the point, so the field needs one. */
    val fractional: Boolean = samples.any { it.second % 1.0 != 0.0 }

    /** Whether any readout is below zero, so the field needs a minus sign. */
    val negative: Boolean = samples.any { it.second < 0 }

    /**
     * The slider position for [entry], or null when no readout on the track
     * reaches it. An exact value is preferred: 35 on a percentage row is 0.35,
     * not the middle of the stretch that happens to read 35%, so the stored
     * setting is the round number the user typed. A value the track steps over
     * lands on the nearest step it has.
     */
    fun valueFor(entry: Double): Float? {
        if (samples.isEmpty()) return null
        val low = samples.minOf { it.second }
        val high = samples.maxOf { it.second }
        if (entry < low && !same(entry, low) || entry > high && !same(entry, high)) return null
        for (scale in SCALES) {
            val guess = (entry * scale).toFloat()
            for (at in floatArrayOf(guess, guess.nextUp(), guess.nextDown())) {
                if (at !in range) continue
                val shown = readoutNumber(display(at), symbols)?.value ?: continue
                if (same(shown, entry)) return at
            }
        }
        // No clean value reads as [entry]: the middle of the first stretch of
        // the track whose readout comes closest, so a readout that rounds or
        // truncates the value underneath still lands on it.
        val best = samples.minOf { abs(it.second - entry) }
        val from = samples.indexOfFirst { abs(it.second - entry) == best }
        var to = from
        while (to + 1 < samples.size && abs(samples[to + 1].second - entry) == best) to++
        return (samples[from].first + samples[to].first) / 2
    }

    private fun same(a: Double, b: Double) = abs(a - b) <= 1e-9 * max(1.0, abs(b))

    private companion object {
        const val SAMPLES = 1000

        /** How a readout's number usually relates to the value: as is, a percentage, or seconds of milliseconds. */
        val SCALES = doubleArrayOf(1.0, 0.01, 100.0, 1000.0, 0.001, 10.0, 0.1)
    }
}

/**
 * The dialog behind a slider's readout: the number, typed. Opens with the
 * current number selected so typing replaces it, carries the readout's unit
 * beside the field, and names the slider's ends under it.
 */
@Composable
internal fun SliderEntryDialog(
    title: String,
    readout: String,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onDismiss: () -> Unit,
    onEnter: (Float) -> Unit,
) {
    val entry = remember(range) { SliderEntry(range, display) }
    val shown = remember(readout) { readoutNumber(readout) }
    val prefix = shown?.let { readout.substring(0, it.start).trim() }.orEmpty()
    val suffix = shown?.let { readout.substring(it.end).trim() }.orEmpty()
    var field by remember {
        val number = shown?.let { readout.substring(it.start, it.end) }.orEmpty()
        mutableStateOf(TextFieldValue(number, TextRange(0, number.length)))
    }
    val value = readoutNumber(field.text)?.let { entry.valueFor(it.value) }
    val wrong = field.text.isNotBlank() && value == null
    val low = entry.lowest?.let(display).orEmpty()
    val high = entry.highest?.let(display).orEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val enter = { if (value != null) onEnter(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                prefix = if (prefix.isEmpty()) null else ({ Text("$prefix ") }),
                suffix = if (suffix.isEmpty()) null else ({ Text(" $suffix") }),
                isError = wrong,
                supportingText = {
                    Text(
                        stringResource(
                            if (wrong) CommonR.string.common_value_range_error else CommonR.string.common_value_range,
                            low,
                            high,
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        // No signed number keypad exists; a minus needs the letters.
                        entry.negative -> KeyboardType.Text
                        entry.fractional -> KeyboardType.Decimal
                        else -> KeyboardType.Number
                    },
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { enter() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = enter, enabled = value != null) {
                Text(stringResource(CommonR.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
