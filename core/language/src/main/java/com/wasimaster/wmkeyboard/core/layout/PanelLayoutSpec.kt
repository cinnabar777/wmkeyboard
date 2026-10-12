package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.util.JsonPretty
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray

/**
 * A tool panel whose grid the user can lay out: the surfaces that take the key
 * area over and used to be fixed structures.
 *
 * One layout per panel, edited in place and reset to the shipped one — the
 * semantics editing a built-in typing layout already has. Serialized by the
 * lowercase name so a stored list reads `"panel":"emoji"`.
 *
 * [shipped] is whether the panel's tool exists in this build. Handwriting is
 * the one that can be missing: lite builds have no recognizer, so its panel
 * hides from the gallery and the layout editor's tabs there. The flag also lets
 * the next panel define its layout ahead of its tool, as the trackpad's was
 * (issue #39).
 */
@Serializable
enum class PanelKind(val shipped: Boolean = true) {
    @SerialName("emoji") EMOJI,
    /** The GIF panel (#538): its browser plus the keys the user puts around it. */
    @SerialName("gif") GIF,
    /** The sticker panel, laid out like the GIF panel. */
    @SerialName("sticker") STICKER,
    @SerialName("clipboard") CLIPBOARD,
    @SerialName("text_edit") TEXT_EDIT,
    @SerialName("trackpad") TRACKPAD,
    /** The Numpad tool's pad: keys only, like the text-editing pad. */
    @SerialName("numpad") NUMPAD,
    /** The handwriting panel (#568): the writing canvas plus the keys around it. */
    @SerialName("handwriting") HANDWRITING(shipped = BuildConfig.ENABLE_ML_KIT_HANDWRITING),
    ;

    /**
     * The key under which a typing layout carries its own grid for this
     * panel in [LayoutSpec.layers] (issue #63, the per-layout half): a layout
     * can override the shared panel layout the way it overrides the shipped
     * symbols page. Prefixed so it can never collide with a [LayoutLayer.key].
     */
    val layerKey: String get() = "panel_" + name.lowercase()

    /**
     * The one component a layout of this panel cannot do without — the panel
     * *is* that component — or null for a panel that is only keys.
     */
    val requiredField: PanelFieldKind?
        get() = when (this) {
            EMOJI -> PanelFieldKind.EMOJI_GRID
            GIF -> PanelFieldKind.GIF_BROWSER
            STICKER -> PanelFieldKind.STICKER_BROWSER
            CLIPBOARD -> PanelFieldKind.CLIPBOARD_LIST
            TRACKPAD -> PanelFieldKind.TRACKPAD
            HANDWRITING -> PanelFieldKind.HANDWRITING_CANVAS
            TEXT_EDIT, NUMPAD -> null
        }
}

/**
 * The layout of one tool panel: a grid of ordinary keys plus the
 * [KeyAction.Field] cells that host the panel's components.
 *
 * Its own type rather than a [LayoutSpec] wearing a marker. A typing layout
 * is a language plus layers plus a place in the enabled list, and every reader
 * of one — the registry, the compiler's letters fallback, the enable gate, the
 * gallery, the tablet expansion, proximity — would have needed a guard. What a
 * panel shares with a typing layout is exactly [LayerSpec]: rows of [Key],
 * per-row heights, a label size. So that is what it holds.
 *
 * [grid]'s `numberRow` is meaningless here and ignored. Rows holding a field
 * that starts in them are *flex* rows and share the panel's spare height; rows
 * of plain keys stay at key height — see `PanelRows.kt`. When no row holds a
 * field (the text-editing pad) every row flexes, which is how that pad has
 * always been drawn.
 */
@Serializable
data class PanelLayoutSpec(
    val panel: PanelKind,
    val grid: LayerSpec,
    val appearance: LayoutAppearance? = null,
    val version: Int = CurrentPanelLayoutVersion,
)

/** Format revision of [PanelLayoutSpec]; there has only been one. */
const val CurrentPanelLayoutVersion: Int = 1

/**
 * Reads and writes the user's panel layouts. The same JSON configuration the
 * typing layouts use, so a field key or an edit key means the same thing in
 * both, and an action from a newer build decodes to [KeyAction.Unknown] here too.
 */
object PanelLayoutCodec {

    fun encodeList(layouts: List<PanelLayoutSpec>): String = layoutJson.encodeToString(layouts)

    /**
     * Decoded one element at a time, on purpose: a [PanelKind] this build does
     * not have is an enum *value*, not a field, so `coerceInputValues` cannot
     * save it — the element throws. Decoding the array in one call would let
     * one such element cost the user the other three panels.
     */
    fun decodeList(json: String): List<PanelLayoutSpec> {
        if (json.isBlank()) return emptyList()
        val array: JsonArray = runCatching { layoutJson.parseToJsonElement(json).jsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { layoutJson.decodeFromJsonElement(PanelLayoutSpec.serializer(), element) }
                .getOrNull()
        }
    }

    fun encode(layout: PanelLayoutSpec): String = layoutJson.encodeToString(layout)

    /** [encode], without the fields that hold their default value; for the raw-JSON editor. */
    fun encodeForEditing(layout: PanelLayoutSpec): String =
        JsonPretty.print(layoutEditorJson.encodeToJsonElement(layout))

    fun decode(json: String): PanelLayoutSpec? {
        if (json.isBlank()) return null
        return runCatching {
            layoutJson.decodeFromString<PanelLayoutSpec>(json.firstJsonDocument())
        }.getOrNull()
    }
}

/** The panel a [LayoutSpec.layers] key names, or null for a typing layer or an unknown key. */
fun panelKindForLayerKey(key: String): PanelKind? = PanelKind.entries.firstOrNull { it.layerKey == key }

/** This layout's own grid for [kind], or null when it uses the shared panel layout. */
fun LayoutSpec.panelLayer(kind: PanelKind): LayerSpec? = layers[kind.layerKey]

/** Every panel this layout carries a grid of its own for. */
val LayoutSpec.panelLayers: Map<PanelKind, LayerSpec>
    get() = layers.entries.mapNotNull { (key, grid) -> panelKindForLayerKey(key)?.let { it to grid } }.toMap()

/**
 * The panel layout the keyboard draws for [kind] while [layout] is the one
 * typing: the layout's own grid first, then the user's shared one, then the
 * shipped grid — the same fallback chain a typing layer has. The layout's
 * appearance rides along so a panel inherits the layout's font.
 */
fun resolvePanelLayout(kind: PanelKind, layout: LayoutSpec, custom: List<PanelLayoutSpec>): PanelLayoutSpec =
    layout.panelLayer(kind)?.let { PanelLayoutSpec(kind, it, appearance = layout.appearance) }
        ?: resolvePanelLayout(kind, custom)

/**
 * The layout the keyboard draws for [kind]: the user's own when they have one,
 * the shipped grid otherwise. The last stored one wins, so a duplicate written
 * by two racing edits resolves to the newer.
 */
fun resolvePanelLayout(kind: PanelKind, custom: List<PanelLayoutSpec>): PanelLayoutSpec =
    custom.lastOrNull { it.panel == kind } ?: BuiltInPanelLayouts.default(kind)

/** [resolvePanelLayout] for every panel at once. */
fun resolvePanelLayouts(custom: List<PanelLayoutSpec>): Map<PanelKind, PanelLayoutSpec> =
    PanelKind.entries.associateWith { resolvePanelLayout(it, custom) }
