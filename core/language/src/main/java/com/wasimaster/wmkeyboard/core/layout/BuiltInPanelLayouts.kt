package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.repeats

/**
 * The panel layouts the keyboard ships with: what every panel draws until the
 * user edits it, and what the editor's Reset goes back to.
 *
 * Each is the panel as it looked before it became a layout, written down. The
 * emoji panel's bottom row of abc / space / backspace was a hand-built row of
 * three cells at 1.5 / 7 / 1.5 of a ten-wide grid; that is the row here, and
 * it is now three ordinary keys, which is the whole point.
 *
 * Grid weight is 10 throughout so a panel's rows centre the way a typing
 * grid's do and a key here is the same width as a key on the board.
 */
object BuiltInPanelLayouts {

    private fun field(kind: PanelFieldKind, width: Float, rowSpan: Int = 1) =
        Key(label = "", action = KeyAction.Field(kind), width = width, rowSpan = rowSpan)

    private fun edit(
        op: TextEditAction,
        width: Float,
        rowSpan: Int = 1,
        hold: TextEditAction? = null,
    ): Key {
        // A hold on a repeating key repeats; an alternate there would never open.
        val alternates = hold?.takeUnless { op.repeats }
            ?.let { listOf(KeyAlternate(KeyAction.Edit(it))) }
            .orEmpty()
        return Key(
            label = "",
            action = KeyAction.Edit(op),
            width = width,
            rowSpan = rowSpan,
            actionAlternates = alternates,
        )
    }

    /** abc / space / backspace, as the emoji panel has always ended. */
    val bottomRow: List<Key> = listOf(
        Key("ABC", action = KeyAction.Letters, width = 1.5f),
        Key(" ", action = KeyAction.Space, width = 7f),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    )

    /**
     * [bottomRow] with the emoji / GIF / sticker switch between abc and space
     * (issue #366). The GIF and sticker panels draw this same row under their
     * grids, so the switch and the keys either side of it do not move when one
     * panel gives way to another.
     */
    val mediaBottomRow: List<Key> = listOf(
        Key("ABC", action = KeyAction.Letters, width = 1.5f),
        field(PanelFieldKind.MEDIA_TABS, 3f),
        Key(" ", action = KeyAction.Space, width = 4f),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    )

    /**
     * The emoji panel with its switch to GIFs and stickers where [switcher]
     * says (issue #366): in the bottom row, at the end of the tab strip, or
     * not at all. Like the Numpad's digit order, the setting reaches only the
     * shipped panel; one the user laid out is drawn as laid out.
     *
     * Search pill and category tabs across the top, the grid, then the bottom
     * row. The strip row is fixed at 0.7 of a key (the tabs are 32 dp icons),
     * the bottom row at a full key, and the grid takes everything between.
     */
    fun emoji(switcher: MediaSwitcher): PanelLayoutSpec {
        val strip = if (switcher == MediaSwitcher.TOP) {
            listOf(
                field(PanelFieldKind.EMOJI_SEARCH, 1f),
                field(PanelFieldKind.EMOJI_TABS, 7f),
                field(PanelFieldKind.MEDIA_TABS, 2f),
            )
        } else {
            listOf(field(PanelFieldKind.EMOJI_SEARCH, 1f), field(PanelFieldKind.EMOJI_TABS, 9f))
        }
        return PanelLayoutSpec(
            panel = PanelKind.EMOJI,
            grid = LayerSpec(
                rows = listOf(
                    strip,
                    listOf(field(PanelFieldKind.EMOJI_GRID, 10f)),
                    if (switcher == MediaSwitcher.BOTTOM) mediaBottomRow else bottomRow,
                ),
                rowHeights = listOf(0.7f, 3f, 1f),
            ),
        )
    }

    /** The emoji panel as it ships: the switch in the bottom row. */
    val EMOJI: PanelLayoutSpec = emoji(MediaSwitcher.BOTTOM)

    /**
     * The clipboard: search pill and the grid / list switch, the fragment
     * chips pulled out of the history, the history itself. [bottomRow] adds the abc / space / backspace
     * row the old `clipboard.bottomRow` setting used to switch on; the
     * settings store seeds a user's layout from this when that flag was set.
     */
    fun clipboard(bottomRow: Boolean = false): PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.CLIPBOARD,
        grid = LayerSpec(
            rows = buildList {
                add(listOf(field(PanelFieldKind.CLIPBOARD_SEARCH, 9f), field(PanelFieldKind.CLIPBOARD_VIEW, 1f)))
                add(listOf(field(PanelFieldKind.CLIPBOARD_ENTITIES, 10f)))
                add(listOf(field(PanelFieldKind.CLIPBOARD_LIST, 10f)))
                if (bottomRow) add(this@BuiltInPanelLayouts.bottomRow)
            },
            // The search pill is a key tall; the fragment strip is a caption
            // over a row of two-line chips, about a key and a half, and it
            // collapses to nothing while no clip has a fragment in it.
            rowHeights = buildList {
                add(0.8f)
                add(1.6f)
                add(3f)
                if (bottomRow) add(1f)
            },
        ),
    )

    val CLIPBOARD: PanelLayoutSpec = clipboard()

    /**
     * The text-editing pad as it has always looked — Gboard's cluster: tall
     * left and right arrows either side of up / Select / down, the clipboard
     * three stacked on the right, home / end / backspace along the bottom.
     * Widths add up to 4.4 on every row, the proportion the hand-built pad
     * used. No field, so every row flexes and the pad fills the key area.
     *
     * Home and End do not repeat, so their holds are free: the shipped pairing
     * sends them to the ends of the *text* rather than the line.
     */
    val TEXT_EDIT: PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.TEXT_EDIT,
        grid = LayerSpec(
            rows = listOf(
                listOf(
                    edit(TextEditAction.LEFT, width = 0.8f, rowSpan = 3),
                    edit(TextEditAction.UP, width = 1.4f),
                    edit(TextEditAction.RIGHT, width = 0.8f, rowSpan = 3),
                    edit(TextEditAction.SELECT_ALL, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.SELECT, width = 1.4f),
                    edit(TextEditAction.COPY, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.DOWN, width = 1.4f),
                    edit(TextEditAction.PASTE, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.HOME, width = 1.47f, hold = TextEditAction.DOC_START),
                    edit(TextEditAction.END, width = 1.47f, hold = TextEditAction.DOC_END),
                    edit(TextEditAction.BACKSPACE, width = 1.46f),
                ),
            ),
        ),
    )

    /** The trackpad surface over a bottom row (issue #39). */
    val TRACKPAD: PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.TRACKPAD,
        grid = LayerSpec(
            rows = listOf(
                listOf(field(PanelFieldKind.TRACKPAD, 10f)),
                bottomRow,
            ),
            rowHeights = listOf(3f, 1f),
        ),
    )

    /**
     * The Numpad tool's pad as it was drawn before it became a layout: a
     * dialer's 1 2 3 on top, the common numeric punctuation down the right,
     * backspace and enter where the thumb finds them. Four rows of four on a
     * grid of four, so each key is a quarter of the width.
     */
    val NUMPAD: PanelLayoutSpec = numpad(calculator = false)

    /**
     * The shipped pad in either digit order: [calculator] puts 7 8 9 on top
     * the way a desk keypad does, which is what the Numpad tool's
     * "Calculator-style layout" setting has always flipped. The setting only
     * reaches the shipped pad; a pad the user laid out is drawn as laid out.
     */
    fun numpad(calculator: Boolean): PanelLayoutSpec {
        val digits = if (calculator) listOf(
            "7",
            "8",
            "9",
            "4",
            "5",
            "6",
            "1",
            "2",
            "3",
        ) else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        return PanelLayoutSpec(
            panel = PanelKind.NUMPAD,
            grid = LayerSpec(
                rows = listOf(
                    listOf(Key(digits[0]), Key(digits[1]), Key(digits[2]), Key("⌫", action = KeyAction.Delete)),
                    listOf(Key(digits[3]), Key(digits[4]), Key(digits[5]), Key("+", longPress = listOf("*", "/", "%", "="))),
                    listOf(Key(digits[6]), Key(digits[7]), Key(digits[8]), Key("-", longPress = listOf("(", ")", "^"))),
                    listOf(
                        Key(".", longPress = listOf(":")),
                        Key("0"),
                        Key(",", longPress = listOf(";")),
                        Key("⏎", action = KeyAction.Enter),
                    ),
                ),
            ),
        )
    }

    val byKind: Map<PanelKind, PanelLayoutSpec> = mapOf(
        PanelKind.EMOJI to EMOJI,
        PanelKind.CLIPBOARD to CLIPBOARD,
        PanelKind.TEXT_EDIT to TEXT_EDIT,
        PanelKind.TRACKPAD to TRACKPAD,
        PanelKind.NUMPAD to NUMPAD,
    )

    fun default(kind: PanelKind): PanelLayoutSpec = byKind.getValue(kind)
}
