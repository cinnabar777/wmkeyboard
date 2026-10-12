package com.wasimaster.wmkeyboard.core.settings

/**
 * The floating keyboard button (issue #374): a small round button drawn over
 * every app while the keyboard is closed, which brings the keyboard up when
 * tapped, whether or not anything on screen is asking for text.
 *
 * Grouped for the reason every family here is (see [CameraSettings]): one
 * top-level slot for the whole feature. The DataStore keys stay flat
 * (`summon_button`, `summon_button_x`, `summon_button_y`).
 */
data class SummonButtonSettings(
    /**
     * Draw the button. Needs Android's "Display over other apps" grant as
     * well; with the switch on and no grant, nothing is drawn and the settings
     * row says what is missing.
     */
    val enabled: Boolean = false,
    /**
     * Where the button rests, as fractions of the room the screen leaves it
     * (0 = left edge, 1 = right edge). Only ever written by dragging it, and a
     * drag always ends against one side, so in practice this is 0 or 1.
     */
    val xFraction: Float = 1f,
    /** The same for the vertical axis (0 = top, 1 = bottom). */
    val yFraction: Float = 0.6f,
)
