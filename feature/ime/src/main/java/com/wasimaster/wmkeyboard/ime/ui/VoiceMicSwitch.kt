package com.wasimaster.wmkeyboard.ime.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bluetooth
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HeadsetMic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Usb
import com.wasimaster.wmkeyboard.core.voice.VoiceMics
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.VoiceStatus
import com.wasimaster.wmkeyboard.ime.VoiceUi
import com.wasimaster.wmkeyboard.ime.clipBased
import kotlinx.coroutines.delay

/**
 * The microphone switch every voice surface carries (#581), after FUTO's
 * Bluetooth toggle: an icon button showing the kind of microphone in use,
 * filled while a picked microphone is the one listening and outlined while
 * Android's own choice is. A press moves to the next microphone plugged in
 * ([com.wasimaster.wmkeyboard.ime.VoiceBarAction.CycleMic]); which one is in
 * use is said by [rememberVoiceMicNotice].
 */

/**
 * The microphones the switch can move between: automatic, then each kind
 * plugged in that the session's engine can be pointed at.
 */
internal fun KeyboardUiState.voiceMicChoices(): List<String> =
    VoiceMics.choices(voice.mics, ownCapture = voice.clipBased || settings.whisper.engine != "system")

/**
 * The switch is drawn when there is a microphone to switch to, or a pick
 * already made that the user may want to undo; never in a password field, or
 * before the microphone may be used at all.
 */
internal fun KeyboardUiState.voiceMicSwitchVisible(): Boolean =
    !secureField &&
        voice.status != VoiceStatus.UNAVAILABLE &&
        voice.status != VoiceStatus.NEED_PERMISSION &&
        (voiceMicChoices().size > 1 || settings.voiceBar.mic != VoiceMics.AUTO)

/** The kind shown: the one listening, or before any session the one picked. */
private fun KeyboardUiState.shownMicKind(): String =
    voice.micKind.ifEmpty { settings.voiceBar.mic }

/** "Bluetooth mic (X7)": the kind, and the device's own name where it gives one. */
@Composable
internal fun voiceMicName(voice: VoiceUi, fallbackKind: String): String {
    val kind = stringResource(voiceMicLabelRes(voice.micKind.ifEmpty { fallbackKind }))
    return if (voice.micName.isBlank()) kind else stringResource(R.string.ime_voice_mic_named, kind, voice.micName)
}

/**
 * "Using Bluetooth mic (X7)" for a couple of seconds after the service asks
 * for it ([VoiceUi.micNoticeAt]), then null. Only while the switch is drawn: a
 * keyboard with one microphone has nothing to announce.
 */
@Composable
internal fun rememberVoiceMicNotice(state: KeyboardUiState): String? {
    val at = state.voice.micNoticeAt
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        val left = at + VOICE_MIC_NOTICE_MS - SystemClock.uptimeMillis()
        if (at == 0L || left <= 0) {
            showing = false
            return@LaunchedEffect
        }
        showing = true
        delay(left)
        showing = false
    }
    if (!showing || !state.voiceMicSwitchVisible()) return null
    return stringResource(R.string.ime_voice_mic_using, voiceMicName(state.voice, state.settings.voiceBar.mic))
}

/**
 * The switch itself. [size] is the touch target; the outline sits inside it.
 *
 * A press turns the old microphone's icon away and the next one in after it,
 * clockwise when the press moved forward through [voiceMicChoices] (wrapping
 * round included) and back the other way otherwise, while the fill eases in or
 * out with the pick. Under reduce motion the icon and the fill just change.
 */
@Composable
internal fun VoiceMicSwitch(
    state: KeyboardUiState,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val kb = LocalKbTheme.current
    val animate = kb.motion.any
    val feedback = LocalKeyPressFeedback.current
    val picked = state.settings.voiceBar.mic
    // Filled while a microphone the user picked is the one listening, as
    // FUTO fills its Bluetooth button while the headset is in use.
    val active = picked != VoiceMics.AUTO && picked in state.voice.mics
    val shape = kb.toolShape()
    val description = stringResource(R.string.ime_voice_mic_desc, voiceMicName(state.voice, picked))
    val switchLabel = stringResource(R.string.ime_voice_mic_switch)
    val fill by animateColorAsState(
        targetValue = if (active) kb.toolCircleActive else Color.Transparent,
        animationSpec = if (animate) tween(MIC_SWAP_MS) else snap(),
        label = "voiceMicFill",
    )
    val tint by animateColorAsState(
        targetValue = if (active) kb.toolCircleActiveIcon else kb.toolbarIcon,
        animationSpec = if (animate) tween(MIC_SWAP_MS) else snap(),
        label = "voiceMicTint",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .clickable(onClickLabel = switchLabel, role = Role.Button) {
                feedback()
                onCycle()
            }
            .semantics { contentDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size - 8.dp)
                .clip(shape)
                .background(fill)
                .then(if (active) Modifier else Modifier.border(1.5.dp, kb.toolCircleActive, shape)),
        ) {
            val kind = state.shownMicKind()
            if (!animate) {
                MicIcon(kind, tint)
            } else {
                val choices = state.voiceMicChoices()
                MicIconSwap(kind, choices, tint)
            }
        }
    }
}

@Composable
private fun MicIcon(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Icon(voiceMicIcon(kind), contentDescription = null, modifier = modifier.size(18.dp), tint = tint)
}

/**
 * The icon turning over to [kind]: the old one rotates and shrinks out, the
 * new one rotates in from the other side and grows to size. Which way is read
 * off [choices], so cycling forward always turns the same way.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun MicIconSwap(kind: String, choices: List<String>, tint: Color) {
    // The kind shown before this one, read once per change: 1 turns
    // clockwise, -1 back.
    val before = remember { MicSeen(kind) }
    val turn = remember(kind) {
        val way = micTurn(choices, from = before.kind, to = kind)
        before.kind = kind
        way
    }
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            fadeIn(tween(MIC_SWAP_MS, delayMillis = MIC_IN_DELAY_MS)) +
                scaleIn(tween(MIC_SWAP_MS, easing = FastOutSlowInEasing), MIC_SWAP_SCALE) togetherWith
                fadeOut(tween(MIC_OUT_MS)) + scaleOut(tween(MIC_OUT_MS), MIC_SWAP_SCALE) using null
        },
        contentAlignment = Alignment.Center,
        label = "voiceMicIcon",
    ) { shown ->
        val angle by transition.animateFloat(
            transitionSpec = { tween(MIC_SWAP_MS, easing = FastOutSlowInEasing) },
            label = "voiceMicTurn",
        ) { phase ->
            when (phase) {
                EnterExitState.PreEnter -> -MIC_TURN_DEGREES * turn
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> MIC_TURN_DEGREES * turn
            }
        }
        MicIcon(shown, tint, Modifier.graphicsLayer { rotationZ = angle })
    }
}

/** The switch's wording for a [VoiceMics] kind. */
internal fun voiceMicLabelRes(kind: String): Int = when (kind) {
    VoiceMics.PHONE -> R.string.ime_voice_mic_phone
    VoiceMics.WIRED -> R.string.ime_voice_mic_wired
    VoiceMics.USB -> R.string.ime_voice_mic_usb
    VoiceMics.BLUETOOTH -> R.string.ime_voice_mic_bluetooth
    else -> R.string.ime_voice_mic_auto
}

internal fun voiceMicIcon(kind: String): ImageVector = when (kind) {
    VoiceMics.PHONE -> Icons.Outlined.Smartphone
    VoiceMics.WIRED -> Icons.Outlined.HeadsetMic
    VoiceMics.USB -> Icons.Outlined.Usb
    VoiceMics.BLUETOOTH -> Icons.Outlined.Bluetooth
    else -> Icons.Outlined.Mic
}

/** The kind the switch last showed; a plain field, read and written once per change. */
private class MicSeen(var kind: String)

/**
 * 1 for a move forward through [choices], wrapping from the last back to the
 * first included, -1 for one back, and 1 when either end is not among them.
 */
private fun micTurn(choices: List<String>, from: String, to: String): Float {
    val a = choices.indexOf(from)
    val b = choices.indexOf(to)
    if (a < 0 || b < 0) return 1f
    val forward = b > a || (b == 0 && a == choices.lastIndex)
    return if (forward) 1f else -1f
}

/** How long a surface says which microphone is in use; FUTO's toast stays 2.5 s. */
private const val VOICE_MIC_NOTICE_MS = 2500L

/** The icon turning over: the new one in, the old one out a little faster, and how far each turns. */
private const val MIC_SWAP_MS = 200
private const val MIC_OUT_MS = 140
private const val MIC_IN_DELAY_MS = 40
private const val MIC_SWAP_SCALE = 0.5f
private const val MIC_TURN_DEGREES = 90f
