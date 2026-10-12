package com.wasimaster.wmkeyboard.core.settings

/**
 * The settings as they apply on a television.
 *
 * Two different kinds of change live here, and the difference is the whole
 * design:
 *
 * ## Capabilities, forced
 *
 * A TV has no touchscreen. Glide typing, the split and floating boards, the
 * one-handed rail and the press-and-hold preview bubble are not preferences
 * there — they are features with no input device behind them. A stored `true`
 * for any of them is a value the user set on their phone, arriving here through
 * the same account backup, and honouring it would mean a keyboard that waits for
 * a finger that will never land. Those are overridden outright.
 *
 * Haptics go the same way, for a plainer reason: there is nothing to vibrate.
 * `HapticPlayer` already asks `hasVibrator()` before it buzzes, so this only
 * saves the work — but it also keeps the settings screen honest about what the
 * device is doing.
 *
 * ## Sizing, as a default
 *
 * Key height, board width and the digit row are ordinary preferences with a bad
 * phone-shaped default on a 16:9 screen you sit three metres from, so they
 * follow `DeviceFormDefaults`' rule instead: applied only where the user has
 * never chosen for themselves, evidenced by the absence of its DataStore key. A
 * TV user who sizes the board by hand keeps their number.
 *
 * The layout is not chosen here at all: whichever one the user is on is reflowed
 * into an even grid at resolve time, the way a tablet's is widened. See
 * `gridForTelevision`.
 *
 * Every value returns the receiver unchanged off a television, so phones and
 * tablets pay one boolean for this file's existence.
 *
 * Runs immediately after [applyDeviceForm] in the overlay chain, for the same
 * reason that one runs first: these are what the device would have shipped with,
 * and every overlay below — modes, themes, direct boot, power saving — is
 * entitled to beat them.
 */
fun KeyboardSettings.applyTelevision(television: Boolean): KeyboardSettings {
    if (!television) return this

    // Sizing: only where the user has never chosen. `TvKeyHeightDp` is shorter
    // than a phone's 48 because a TV shows the digit row by default (a layer
    // switch costs a D-pad press, and the remote has no shift key to hold), and
    // six rows of 48 dp on a 540 dp-tall 1080p screen is more than half the
    // picture.
    val sizing = layoutBehavior.keyHeightUntouched
    val digits = layoutBehavior.numberRowUntouched && !numberRow
    val width = layoutBehavior.keyboardWidthUntouched

    return copy(
        keyHeightDp = if (sizing) TvKeyHeightDp else keyHeightDp,
        numberRowHeightDp = if (sizing) TvNumberRowHeightDp else numberRowHeightDp,
        numberRow = if (digits) true else numberRow,
        // Full-bleed keys on a 960 dp-wide screen are 96 dp each and the ring
        // has to cross all of them; narrower keys in the middle of the screen
        // are both closer together and well inside the overscan area that older
        // sets still crop.
        keyboardWidthPercent = if (width) TvKeyboardWidthPercent else keyboardWidthPercent,
        // Capabilities, not preferences — see the note above.
        gestureTyping = false,
        splitKeyboard = false,
        floatingKeyboard = false,
        floatingKeyboardLandscape = false,
        oneHandedMode = OneHandedMode.OFF,
        haptics = haptics.copy(enabled = false),
        popup = popup.copy(enabled = false),
        hardwareKeyboard = hardwareKeyboard.copy(
            // The remote *is* the keyboard here: without the ring there is no
            // way to reach a key at all. Still a setting, so a TV user with a
            // Bluetooth keyboard who wants their arrow keys back to the app can
            // switch it off.
            dpadKeyNavigation = hardwareKeyboard.dpadKeyNavigationUntouched ||
                hardwareKeyboard.dpadKeyNavigation,
        ),
    )
}

/** Key height a television starts at; see [applyTelevision]. */
const val TvKeyHeightDp = 40

/** Digit-row height to match [TvKeyHeightDp]. */
const val TvNumberRowHeightDp = 36

/**
 * How much of a television's width the board fills by default. 50% of a 960 dp
 * screen is 480 dp — a key every 48 dp, which is a phone's key size at a
 * television's viewing distance, and a board that clears the 5% overscan margin
 * on every side. Gboard's TV card is narrower still (about 40–45%); half the
 * screen keeps room for the toolbar's row of tools, which Gboard does not have.
 */
const val TvKeyboardWidthPercent = 50
