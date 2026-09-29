package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeymanTarget
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.repair

/**
 * Turns a Keyman touch layout into a [LayoutSpec].
 *
 * The grid, and every key's Keyman identity. What each key *types* comes from
 * the keyboard's rules, which is why an ordinary key converts to
 * [KeyAction.KeymanKey] carrying the key the rules know it as rather than to a
 * text key carrying its cap: the cap and the output agree on a positional
 * keyboard and disagree on a mnemonic one, and only the engine knows which this
 * is. The same goes for the keys behind a long press, a flick or a repeated tap — in Keyman
 * those are keys too, with their own ids and layers.
 *
 * ## Layers
 *
 * Every layer is kept, under the name [KeymanLayers.specKey] gives it: Keyman's
 * `default`, `numeric` and `symbol` become our letters and two symbol pages,
 * and everything else keeps its own id behind a prefix, so a keyboard that
 * calls a layer `symbols` or `number` cannot land on one of ours.
 *
 * Keyman's `shift` and `caps` layers stay whole rather than being folded into
 * per-key shift labels. A shift layer is its own set of keys — in a good third
 * of the corpus with different ids, layers, long presses and flicks from the
 * keys under them — so the keyboard shows the author's shift grid while shift
 * is on, and our shift and caps-lock keys are what reach it.
 */
object TouchLayoutConverter {

    /**
     * Converts [doc], binding the result to [keyboardId] so the runtime can find
     * the rules. Never throws; an unusable document comes back as a fault.
     */
    fun convert(
        doc: KeymanTouchLayout,
        keyboardId: String,
        displayName: String,
    ): KeymanResult<ConvertedKeymanLayout> {
        val platform = doc.preferred()
            ?: return KeymanResult.Failure(KeymanFault.TOUCH_LAYOUT_EMPTY)
        if (platform.layer.isEmpty()) return KeymanResult.Failure(KeymanFault.TOUCH_LAYOUT_EMPTY)

        val report = Report()
        val converted = LinkedHashMap<String, LayerSpec>()
        val layerNames = platform.layer.map { it.id.lowercase() }.toSet() + MODIFIER_LAYER_NAMES
        for (layer in platform.layer) {
            val name = KeymanLayers.specKey(layer.id)
            // A second layer under one id is a file error upstream; the first
            // one is what KeymanWeb finds when it looks the id up.
            if (name in converted) continue
            val rows = layer.row.map { row -> convertRow(row, layer.id, layerNames, report) }
                .filter { it.isNotEmpty() }
            if (rows.isEmpty()) continue
            converted[name] = LayerSpec(rows = rows, keymanFrames = framesOf(layer))
        }
        if (LayoutLayer.LETTERS.key !in converted) {
            // Everything downstream assumes a letters layer exists: `compile`
            // falls back to it and `repair` guarantees delete, space and enter
            // on it. A Keyman layout whose base layer is called something else
            // would otherwise convert into a grid with no way in.
            return KeymanResult.Failure(KeymanFault.TOUCH_LAYOUT_EMPTY)
        }

        val spec = LayoutSpec(
            id = "asset_kmn_$keyboardId",
            name = displayName.ifBlank { keyboardId },
            langId = "",
            layers = retargetShiftKeys(converted),
        )

        // Repair here rather than leaving it to the loader, the same way
        // `ForeignLayouts.finish` does. Some Keyman keyboards genuinely ship a
        // symbols layer with no key back to the letters, and a few ship no
        // spacebar; both are fine under Keyman's own renderer, which always
        // draws its own frame, and both strand the user under ours. Running the
        // existing pass reuses rules that are already tested instead of
        // reimplementing them, and it makes "a converted layout never needs
        // repair" true by construction — which is the invariant every committed
        // asset is held to.
        val repaired = spec.repair()
        return KeymanResult.Success(
            ConvertedKeymanLayout(repaired.spec, report.toReport(), repaired.repairNotes),
        )
    }

    /**
     * Turns the keys that switch to Keyman's `shift` and `caps` layers into our
     * own shift and caps-lock keys, and the keys on those layers that lead back
     * to the letters into our shift key.
     *
     * Shift here is state, not a place: the service draws the `shift` grid
     * while shift is on, releases it after a key the way every other layout
     * does, and locks it on a double tap. A Keyman layer switch left pointing at
     * `shift` would bypass all of that and strand the user on a grid our shift
     * key does not know it is on.
     */
    private fun retargetShiftKeys(layers: Map<String, LayerSpec>): Map<String, LayerSpec> {
        val hasShift = KeymanLayers.SHIFT in layers
        val hasCaps = KeymanLayers.CAPS in layers
        if (!hasShift && !hasCaps) return layers
        return layers.mapValues { (name, spec) ->
            val onShiftGrid = name == KeymanLayers.SHIFT || name == KeymanLayers.CAPS
            spec.copy(
                rows = spec.rows.map { row ->
                    row.map { key ->
                        val target = when (val action = key.action) {
                            is KeyAction.KeymanKey -> action.nextLayer.takeIf { action.isLayerSwitch }
                            KeyAction.Letters -> LayoutLayer.LETTERS.key
                            else -> null
                        }
                        when {
                            target == KeymanLayers.SHIFT && hasShift ->
                                key.copy(action = KeyAction.Shift, label = key.label.ifBlank { "⇧" })
                            target == KeymanLayers.CAPS && hasCaps ->
                                key.copy(action = KeyAction.CapsLock, label = key.label.ifBlank { "⇪" })
                            onShiftGrid && target == LayoutLayer.LETTERS.key ->
                                key.copy(action = KeyAction.Shift, label = key.label.ifBlank { "⇧" })
                            else -> key
                        }
                    }
                },
            )
        }
    }

    private fun convertRow(
        row: TouchRow,
        layerId: String,
        layerNames: Set<String>,
        report: Report,
    ): List<Key> {
        val out = ArrayList<Key>(row.key.size + 1)
        for (k in row.key) {
            // Keyman expresses a leading gap as padding on the next key; we have
            // no padding, so it becomes a spacer of the same width. The default
            // inter-key gap is 5%, so only padding beyond that is real.
            val pad = k.pad ?: 0f
            if (pad > DEFAULT_PAD * 2) {
                out += Key(label = "", action = KeyAction.None, width = (pad - DEFAULT_PAD) / 100f)
            }
            out += convertKey(k, layerId, layerNames, report) ?: continue
        }
        return out
    }

    /**
     * [convertShape], and the count of repeated-tap cycles it could not keep.
     * Counted here rather than on each path because most paths lose a cycle
     * without meaning to: a frame key or a layer switch returns before the
     * cycle is looked at. Those are mostly `*123*` keys that cycle through the
     * symbol pages, which KeymanWeb credits to the first key even after its tap
     * has put another page under the finger, and ours cannot.
     */
    private fun convertKey(k: TouchKey, layerId: String, layerNames: Set<String>, report: Report): Key? {
        val key = convertShape(k, layerId, layerNames, report)
        if (k.multitap.isNotEmpty() && key?.multitap.isNullOrEmpty()) report.droppedMultitaps++
        return key
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun convertShape(k: TouchKey, layerId: String, layerNames: Set<String>, report: Report): Key? {
        // Some authors cap a layer-switch key with the layer's own id, so the
        // key reads "rightalt" or "default". Those are internal identifiers, not
        // something to print on a key.
        val capIsLayerName = k.text?.lowercase()?.let { it in layerNames } == true
        val width = ((k.width ?: 100f) / 100f).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val nextLayer = k.nextlayer?.takeIf { it.isNotBlank() }?.let(KeymanLayers::specKey)

        // A key that holds space rather than typing: both draw as a gap, and
        // KeyAction.None swallows taps on it.
        if (KeymanKeySp.of(k.sp) in HOLLOW_KEYS) {
            return Key(label = "", action = KeyAction.None, width = width)
        }

        // Frame keys are recognised by id before text, because the text is not
        // reliably there: `basic_kbdus` writes its spacebar as `K_SPACE` with an
        // empty cap and `sp: 0`, which by text alone reads as an ordinary key
        // and leaves the converted grid with no spacebar at all. Shift and caps
        // lock that name a layer of their own are switches, handled below.
        //
        // Giving these their own actions rather than routing them through the
        // engine is deliberate. A keyboard whose rules do fire on space or
        // backspace still gets them: the IME seam offers the engine first
        // refusal on both, and only falls back to the ordinary handler when no
        // rule matches. Converting them to engine keys instead would cost
        // long-press repeat on backspace, auto-space and double-space-period.
        // KeymanWeb reads key ids upper-cased, so `K_Enter` is the enter key.
        val upperId = k.id.trim().uppercase()
        // A spacebar the author pressed with other modifiers than its layer's —
        // a second space on the letters page typing a joiner under
        // `[SHIFT K_SPACE]`, a plain space on a right-Alt page — is a key for the
        // rules, not our spacebar: our spacebar has no modifiers of its own.
        val ownSpace = upperId == "K_SPACE" && !k.layer.isNullOrBlank() &&
            KeymanLayers.modifiers(k.layer) != KeymanLayers.modifiers(layerId)
        FRAME_KEYS[upperId]?.takeIf { !ownSpace && (nextLayer == null || upperId !in LAYER_FRAME_IDS) }?.let { frame ->
            // A shift or caps key that names no layer does nothing in KeymanWeb:
            // a touch layout's shift is a layer switch, and one with nowhere to
            // go switches nowhere. It keeps its cap and its long press.
            val inert = frame.action == KeyAction.Shift || frame.action == KeyAction.CapsLock
            return Key(
                label = k.text
                    ?.takeIf { it.isNotBlank() && !it.startsWith("*") && !capIsLayerName }
                    ?: frame.label,
                action = if (inert) KeyAction.None else frame.action,
                width = width,
            )
        }

        // A key that switches layers, handled before anything else can lose it.
        //
        // Keyman puts the layer switch on `nextlayer` and lets the id and cap be
        // anything: Khmer Angkor returns from its numeric layer with
        // `id="K_LCONTROL" text="១២ឥ" sp=2 nextlayer="default"`. Treating that as
        // an ordinary key — which is what happens when the id is looked up first
        // and does not resolve — drops the switch and strands the user on a
        // layer with no way back, the exact failure `repair`'s way-back rules
        // exist to prevent.
        //
        // The two switches our frame already has become our own actions, so
        // they keep working with no rules loaded and `repair` can see them as a
        // way back; every other target is a Keyman layer switch the service
        // resolves by name.
        val label = k.text?.takeIf { it.isNotBlank() && !it.startsWith("*") && !capIsLayerName }
        if (nextLayer != null && isFrame(k) && !types(k)) {
            val action = when {
                nextLayer == LayoutLayer.LETTERS.key -> KeyAction.Letters
                nextLayer == LayoutLayer.SYMBOLS.key && layerId == KeymanLayers.DEFAULT -> KeyAction.Symbols
                else -> KeyAction.KeymanKey(vkey = 0, nextLayer = nextLayer)
            }
            // Never the layer's own name: "rightalt" and "default" are
            // internal identifiers, and printing one on a key puts a word
            // where the user expects a character.
            val special = k.text?.let(::specialFor)?.takeIf { it.action != null }
            val fallback = when {
                special != null -> special.label
                action == KeyAction.Letters -> "ABC"
                action == KeyAction.Symbols -> "?123"
                else -> "⌨"
            }
            return Key(label = label ?: fallback, action = action, width = width)
        }

        // `*Shift*`, `*BkSp*`, `*123*` and the rest of the frame caps. A cap
        // that stands for a character (`*ZWNJ*`, `*NBSp*`) is only a cap: what
        // the key types is still its id's business, so it drops through with
        // the special's label and its character as the no-rules fallback.
        val special = k.text?.let(::specialFor)
        // A frame cap (`*BkSp*`, `*123*`, `*Ctrl*`) is only a picture when the
        // key's id can type: KeymanWeb runs a `T_BKSP` through the rules like
        // any other key, and with no rule for it, it does nothing at all. So
        // the cap becomes our own key only on an id that types nothing.
        if (special != null && special.action != KeyAction.Text && types(k)) {
            val target = keymanTarget(k, layerId, nextLayer)
            if (target != null) {
                return Key(
                    label = if (special.action == null) switchCap(k.text) else special.label,
                    // Nothing to type with no rules loaded but a U_ key's own text.
                    output = unicodeOutput(k.id).orEmpty(),
                    action = target,
                    width = width,
                )
            }
        }
        if (special != null && special.action != KeyAction.Text) {
            if (special.action == null) {
                // `*Ctrl*`, `*AltGr*`: nothing of ours types these, but with a
                // `nextlayer` the key is simply a way to that layer.
                if (nextLayer != null) {
                    return Key(label = switchCap(k.text), action = KeyAction.KeymanKey(vkey = 0, nextLayer = nextLayer), width = width)
                }
                report.droppedSpecial++
                return Key(label = "", action = KeyAction.None, width = width)
            }
            // A layer cap with no layer named goes nowhere in KeymanWeb, so it
            // goes nowhere here: it keeps its face and does nothing.
            val switches = special.action == KeyAction.Symbols || special.action == KeyAction.Letters ||
                special.action == KeyAction.Shift || special.action == KeyAction.CapsLock
            val action = if (switches && nextLayer == null) KeyAction.None else special.action
            return Key(label = special.label, output = special.output, action = action, width = width)
        }

        val longPress = convertGestures(k.sk, layerId, report)
        val flick = convertFlicks(k, layerId, report)

        val target = keymanTarget(k, layerId, nextLayer)
        val unicode = unicodeOutput(k.id)
        val cap = when {
            special != null -> special.label
            capIsLayerName -> ""
            else -> k.text.orEmpty()
        }
        val shown = cap.ifEmpty { unicode.orEmpty() }
        if (target == null) {
            // No id at all. KeymanWeb sends such a key nowhere; a cap is still
            // worth typing here rather than leaving a dead key on the grid.
            if (shown.isEmpty()) {
                report.droppedUnknown++
                return null
            }
            report.unmappedIds++
            return Key(label = shown, width = width, longPress = longPress.labels, flick = flick.labels)
        }
        // A U_ key types its own code points whatever its cap says.
        val fallbackOutput = unicode ?: special?.output
        val multitap = convertMultitap(k.multitap, layerId, report)
        return Key(
            label = shown,
            output = fallbackOutput?.takeIf { it != shown },
            action = target.copy(longPress = longPress.targets, flick = flick.targets, multitap = multitap.targets),
            width = width,
            longPress = longPress.labels,
            flick = flick.labels,
            multitap = multitap.labels,
        )
    }

    /**
     * What this layer's space, backspace and enter say beyond being ours: the
     * modifiers their `layer` gives them where that differs from the layer's
     * own, and their `nextlayer`. Null when none says anything, which is most.
     */
    private fun framesOf(layer: TouchLayer): Map<String, KeymanTarget>? {
        val own = KeymanLayers.modifiers(layer.id)
        val frames = LinkedHashMap<String, KeymanTarget>()
        for (k in layer.row.flatMap { it.key }) {
            val id = k.id.trim().uppercase()
            if (id !in FRAME_IDS || id in frames) continue
            val modifiers = KeymanLayers.modifiers(k.layer?.takeIf { it.isNotBlank() } ?: layer.id)
            // A spacebar with modifiers of its own is a rules key; see convertKey.
            if (id == "K_SPACE" && modifiers != own) continue
            val nextLayer = k.nextlayer?.takeIf { it.isNotBlank() }?.let(KeymanLayers::specKey)
            if (modifiers == own && nextLayer == null) continue
            frames[id] = KeymanTarget(vkey = VirtualKeys.byName(id) ?: 0, modifiers = modifiers, nextLayer = nextLayer)
        }
        return frames.takeIf { it.isNotEmpty() }
    }

    /** The names a cap may not be printed as, for [convertKeyForTest]. */
    internal fun layerNamesOf(platform: TouchPlatform): Set<String> =
        platform.layer.map { it.id.lowercase() }.toSet() + MODIFIER_LAYER_NAMES

    /** One touch key as [convert] makes it, before the shift retargeting. For the KeymanWeb conformance harness. */
    internal fun convertKeyForTest(k: TouchKey, layerId: String, layerNames: Set<String>): Key? =
        convertKey(k, layerId, layerNames, Report())

    /** A layer's frame-key metadata as [convert] records it. For the KeymanWeb conformance harness. */
    internal fun framesForTest(layer: TouchLayer): Map<String, KeymanTarget> = framesOf(layer).orEmpty()

    /** One long-press or flick subkey as [convert] makes it. For the KeymanWeb conformance harness. */
    internal fun convertGestureForTest(sub: TouchKey, layerId: String): Pair<String, KeymanTarget?>? =
        gestureKey(sub, layerId)

    /**
     * Whether pressing [k] can type anything in KeymanWeb, which runs every key
     * through the rules and its default output before any layer switch. A `U_`
     * key types its code points and a `T_` key whatever a rule says, so either
     * can be a key that types and then switches — `U_0073` with a `nextlayer`
     * back to the letters. Only a `K_` key with no US character (shift, the
     * option keys, the layer-switch codes) is a layer switch and nothing else.
     */
    private fun types(k: TouchKey): Boolean {
        val id = k.id.trim().uppercase()
        if (id.isEmpty()) return false
        val vkey = VirtualKeys.byName(id) ?: return true
        return VirtualKeys.toChar(vkey, 0) != '\u0000'
    }

    /** True for the `sp` values KeymanWeb draws as frame keys. */
    private fun isFrame(k: TouchKey): Boolean {
        val sp = KeymanKeySp.of(k.sp)
        return sp == KeymanKeySp.SPECIAL || sp == KeymanKeySp.SPECIAL_ACTIVE ||
            k.id.trim().uppercase() in LAYER_FRAME_IDS ||
            (VirtualKeys.byName(k.id.trim()) ?: 0) >= VirtualKeys.FIRST_FRAME_CODE
    }

    /**
     * The Keyman key [k] is, as the rules will see it: a US virtual key, or the
     * author's own `T_`/`U_` name for the engine to look up. The modifiers are
     * those of the key's `layer` attribute, else of the layer it sits on — both
     * read the way KeymanWeb reads a layer name. Null for a key with no id.
     */
    private fun keymanTarget(k: TouchKey, layerId: String, nextLayer: String?): KeyAction.KeymanKey? {
        val id = k.id.trim()
        if (id.isEmpty()) return null
        val modifiers = KeymanLayers.modifiers(k.layer?.takeIf { it.isNotBlank() } ?: layerId)
        val vkey = VirtualKeys.byName(id.uppercase())
        return if (vkey != null) {
            KeyAction.KeymanKey(vkey = vkey, modifiers = modifiers, nextLayer = nextLayer)
        } else {
            KeyAction.KeymanKey(vkey = 0, modifiers = modifiers, nextLayer = nextLayer, id = id.uppercase())
        }
    }

    private class LongPress(val labels: List<String>, val targets: List<KeymanTarget>)

    private class Flicks(val labels: Map<FlickDirection, String>, val targets: Map<FlickDirection, KeymanTarget>)

    /**
     * Long-press subkeys, each as the text to show and the key it is. Entries
     * with nothing to show are dropped together with their key, so the two
     * lists stay parallel — that alignment is how a pick finds its key.
     */
    private fun convertGestures(sk: List<TouchKey>, layerId: String, report: Report): LongPress {
        val labels = ArrayList<String>(sk.size)
        val targets = ArrayList<KeymanTarget>(sk.size)
        val seen = HashSet<Pair<String, KeymanTarget?>>()
        for (sub in sk) {
            val (label, target) = gestureKey(sub, layerId) ?: continue
            if (!seen.add(label to target)) continue
            labels += label
            targets += target ?: KeymanTarget(text = label).also { report.unmappedIds++ }
        }
        return LongPress(labels, targets)
    }

    /**
     * A key's repeated-tap cycle, each step as the text to show and the key it
     * is, parallel like [convertGestures]. All or nothing: a step with nothing
     * to show dropped on its own would move every later step onto the wrong
     * tap, so the whole cycle goes, and [convertKey] counts it.
     */
    private fun convertMultitap(steps: List<TouchKey>, layerId: String, report: Report): LongPress {
        if (steps.isEmpty()) return LongPress(emptyList(), emptyList())
        val labels = ArrayList<String>(steps.size)
        val targets = ArrayList<KeymanTarget>(steps.size)
        var unmapped = 0
        for (sub in steps) {
            val (label, target) = gestureKey(sub, layerId) ?: return LongPress(emptyList(), emptyList())
            labels += label
            targets += target ?: KeymanTarget(text = label).also { unmapped++ }
        }
        report.unmappedIds += unmapped
        return LongPress(labels, targets)
    }

    /**
     * Keyman has eight flick directions and [FlickDirection] has four, so the
     * diagonals are dropped and counted rather than rounded to a neighbour —
     * a flick the user aims north-east landing on the north key's output is a
     * worse outcome than the gesture doing nothing.
     */
    private fun convertFlicks(k: TouchKey, layerId: String, report: Report): Flicks {
        if (k.flick.isEmpty()) return Flicks(emptyMap(), emptyMap())
        val labels = LinkedHashMap<FlickDirection, String>()
        val targets = LinkedHashMap<FlickDirection, KeymanTarget>()
        for ((direction, sub) in k.flick) {
            val mapped = when (direction.lowercase()) {
                "n" -> FlickDirection.UP
                "s" -> FlickDirection.DOWN
                "e" -> FlickDirection.RIGHT
                "w" -> FlickDirection.LEFT
                else -> null
            }
            if (mapped == null) {
                report.droppedFlicks++
                continue
            }
            val (label, target) = gestureKey(sub, layerId) ?: continue
            labels[mapped] = label
            targets[mapped] = target ?: KeymanTarget(text = label).also { report.unmappedIds++ }
        }
        return Flicks(labels, targets)
    }

    /**
     * A gesture's subkey as (what to show, the key it is). The key carries its
     * own no-rules fallback in [KeymanTarget.text] where that is not simply what
     * it shows, since a popup entry has no [Key.output] of its own. Null when
     * there is nothing to show.
     */
    private fun gestureKey(sub: TouchKey, layerId: String): Pair<String, KeymanTarget?>? {
        val special = sub.text?.let(::specialFor)
        val unicode = unicodeOutput(sub.id)
        val nextLayer = sub.nextlayer?.takeIf { it.isNotBlank() }?.let(KeymanLayers::specKey)
        val label = when {
            // A modifier cap with somewhere to go is a way to that layer.
            special != null && special.action == null && nextLayer != null ->
                return switchCap(sub.text) to KeymanTarget(nextLayer = nextLayer)
            special != null && special.action == null -> return null
            special != null -> special.label
            !sub.text.isNullOrEmpty() -> sub.text
            unicode != null -> unicode
            else -> return null
        }
        val target = keymanTarget(sub, layerId, nextLayer)?.let { key ->
            KeymanTarget(
                vkey = key.vkey,
                modifiers = key.modifiers,
                nextLayer = key.nextLayer,
                id = key.id,
                // Only where it differs from what the entry shows: a popup
                // entry already types its label when nothing else is said.
                text = (unicode ?: special?.output)?.takeIf { it != label },
            )
        }
        return label to target
    }

    /** What a modifier cap we have no key for reads as on a layer switch: `*AltGr*` as "AltGr". */
    private fun switchCap(text: String?): String =
        text?.trim('*')?.takeIf { it.isNotBlank() && it.lowercase() !in MODIFIER_LAYER_NAMES } ?: "⌨"

    /** `U_0259` or `U_0041_0301` to the text it emits, or null. */
    private fun unicodeOutput(id: String): String? {
        if (!id.startsWith("U_") && !id.startsWith("u_")) return null
        val parts = id.substring(2).split('_')
        if (parts.isEmpty()) return null
        val sb = StringBuilder()
        for (part in parts) {
            val cp = part.toIntOrNull(16) ?: return null
            // KeymanWeb refuses the C0 and C1 controls here, and so do we.
            if (cp !in 0..0x10FFFF || cp in 0..0x1F || cp in 0x80..0x9F) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * The [Special] a cap names, whether or not the author starred it.
     *
     * The format documents these as `*ZWNJ*`, but a good number of keyboards in
     * the corpus write plain `ZWNJ`, `rlm` or `LRM` instead. Unrecognised, those
     * become the key's visible label — a word sitting on a key where a character
     * belongs, and no zero-width joiner actually typed.
     */
    private fun specialFor(text: String): Special? {
        SPECIAL_KEYS[text]?.let { return it }
        val bare = text.trim('*')
        if (bare.isEmpty()) return null
        return SPECIAL_KEYS["*$bare*"] ?: SPECIAL_BY_BARE_NAME[bare.lowercase()]
    }

    private class Special(
        val label: String,
        val action: KeyAction?,
        /** What the key commits, when that differs from its cap. */
        val output: String? = null,
    )

    private class Frame(val label: String, val action: KeyAction)

    /** The frame keys a layer may say more about; see [framesOf]. */
    private val FRAME_IDS = setOf("K_SPACE", "K_BKSP", "K_ENTER")

    /** Frame keys that are a layer switch when the author gave them a target. */
    private val LAYER_FRAME_IDS = setOf("K_SHIFT", "K_CAPS", "K_LOPT", "K_ROPT", "K_NUMLOCK")

    /**
     * Keys whose identity is their id, whatever cap the author gave them. These
     * are the ones `repair` insists a layout has, so getting one wrong does not
     * produce a slightly odd keyboard — it produces one that cannot be enabled.
     */
    private val FRAME_KEYS: Map<String, Frame> = mapOf(
        "K_SPACE" to Frame(" ", KeyAction.Space),
        "K_BKSP" to Frame("⌫", KeyAction.Delete),
        "K_ENTER" to Frame("⏎", KeyAction.Enter),
        "K_SHIFT" to Frame("⇧", KeyAction.Shift),
        "K_CAPS" to Frame("⇪", KeyAction.CapsLock),
        "K_LOPT" to Frame("🌐", KeyAction.LanguageSwitch),
        "K_TAB" to Frame("⇥", KeyAction.SendKey(KEYCODE_TAB)),
    )

    /** `KeyEvent.KEYCODE_TAB`, spelled out so this module needs no Android import. */
    private const val KEYCODE_TAB = 61

    /** Layer ids Keyman defines that a key may also be capped with. */
    private val MODIFIER_LAYER_NAMES = setOf(
        "default", "shift", "caps", "ctrl", "alt", "ctrlshift", "altshift",
        "ctrlalt", "ctrlaltshift", "rightalt", "rightalt-shift", "numeric", "symbol",
    )

    /**
     * The `*Special*` labels, which Keyman renders from a private-use icon font
     * we do not ship. Each maps to our own action and our own cap, so a
     * converted keyboard's shift key looks like every other shift key here.
     *
     * A null action means we have no equivalent; the key becomes a spacer and
     * is counted, rather than being drawn with a label the user cannot act on.
     */
    private val SPECIAL_KEYS: Map<String, Special> = buildMap {
        for (t in listOf("*Shift*", "*Shifted*")) put(t, Special("⇧", KeyAction.Shift))
        for (t in listOf("*ShiftLock*", "*ShiftedLock*", "*Caps*")) {
            put(t, Special("⇪", KeyAction.CapsLock))
        }
        for (t in listOf("*BkSp*", "*LTRBkSp*", "*RTLBkSp*")) put(t, Special("⌫", KeyAction.Delete))
        for (t in listOf("*Enter*", "*LTREnter*", "*RTLEnter*")) put(t, Special("⏎", KeyAction.Enter))
        put("*Menu*", Special("🌐", KeyAction.LanguageSwitch))
        put("*ABC*", Special("ABC", KeyAction.Letters))
        put("*abc*", Special("abc", KeyAction.Letters))
        put("*123*", Special("?123", KeyAction.Symbols))
        put("*Numeral*", Special("?123", KeyAction.Symbols))
        put("*Symbol*", Special("=\\<", KeyAction.Symbols))
        put("*Currency*", Special("$", KeyAction.Symbols))
        // Invisible and width-varying characters. Each commits its own code
        // point, not a plain space: an earlier version routed every one of
        // these to KeyAction.Space, so a no-break space key typed U+0020 and the
        // distinction the key exists for was silently lost.
        //
        // Caps follow the convention the shipped layouts already use for
        // joiners — a dotted circle either side of the mark — rather than
        // spelling the name out, which reads as a word on a key.
        put("*Sp*", Special(" ", KeyAction.Space))
        put("*NBSp*", Special("⍽", KeyAction.Text, output = "\u00A0"))
        put("*NarNBSp*", Special("⍽", KeyAction.Text, output = "\u202F"))
        put("*EnQ*", Special("␣", KeyAction.Text, output = "\u2000"))
        put("*EmQ*", Special("␣", KeyAction.Text, output = "\u2001"))
        put("*EnSp*", Special("␣", KeyAction.Text, output = "\u2002"))
        put("*EmSp*", Special("␣", KeyAction.Text, output = "\u2003"))
        put("*PunctSp*", Special("␣", KeyAction.Text, output = "\u2008"))
        put("*ThSp*", Special("␣", KeyAction.Text, output = "\u2009"))
        put("*HSp*", Special("␣", KeyAction.Text, output = "\u200A"))
        put("*ZWSp*", Special("␣", KeyAction.Text, output = "\u200B"))
        for (t in listOf("*ZWNJ*", "*ZWNJiOS*", "*ZWNJAndroid*", "*ZWNJGeneric*")) {
            put(t, Special("◌│◌", KeyAction.Text, output = "\u200C"))
        }
        put("*ZWJ*", Special("◌‿◌", KeyAction.Text, output = "\u200D"))
        put("*WJ*", Special("◌⁀◌", KeyAction.Text, output = "\u2060"))
        put("*CGJ*", Special("◌⌇◌", KeyAction.Text, output = "\u034F"))
        put("*LTRM*", Special("◌→", KeyAction.Text, output = "\u200E"))
        put("*RTLM*", Special("←◌", KeyAction.Text, output = "\u200F"))
        put("*SH*", Special("◌-◌", KeyAction.Text, output = "\u00AD"))
        put("*HTab*", Special("⇥", KeyAction.SendKey(KEYCODE_TAB)))
        // No equivalent: hiding the keyboard is the system's gesture here, and
        // the desktop modifier caps have no meaning on a touch grid.
        for (t in listOf("*Hide*", "*Tab*", "*TabLeft*", "*AltGr*", "*Alt*", "*Ctrl*",
            "*LAlt*", "*RAlt*", "*LCtrl*", "*RCtrl*")) {
            put(t, Special("", null))
        }
    }

    /**
     * Bare spellings the corpus uses for keys the format wants starred, keyed
     * lowercase. Only the unambiguous ones: a cap of "shift" could plausibly be
     * a word someone meant to show, while "zwnj" could not.
     */
    private val SPECIAL_BY_BARE_NAME: Map<String, Special> by lazy {
        // Only names no language writes on a key. `sh` is a digraph before it
        // is the soft hyphen's name, `sp` a syllable, `abc` and `123` plausible
        // caps — reading those as specials put a soft hyphen on a key that
        // types "sh".
        buildMap {
            for (name in listOf("*ZWNJ*", "*ZWNJiOS*", "*ZWNJAndroid*", "*ZWNJGeneric*", "*ZWJ*", "*ZWSp*",
                "*NBSp*", "*NarNBSp*", "*LTRM*", "*RTLM*", "*BkSp*", "*Shift*", "*Enter*")) {
                put(name.trim('*').lowercase(), SPECIAL_KEYS.getValue(name))
            }
            put("lrm", SPECIAL_KEYS.getValue("*LTRM*"))
            put("rlm", SPECIAL_KEYS.getValue("*RTLM*"))
        }
    }

    /** The `sp` values that mean "leave a gap here" rather than "type". */
    private val HOLLOW_KEYS = setOf(KeymanKeySp.SPACER, KeymanKeySp.BLANK)

    private const val DEFAULT_PAD = 5f
    private const val MIN_WIDTH = 0.1f
    private const val MAX_WIDTH = 12f

    private class Report {
        var droppedMultitaps = 0
        var droppedFlicks = 0
        var droppedSpecial = 0
        var droppedUnknown = 0
        var unmappedIds = 0

        fun toReport() = KeymanConversionReport(
            droppedMultitaps = droppedMultitaps,
            droppedDiagonalFlicks = droppedFlicks,
            droppedSpecialKeys = droppedSpecial,
            droppedBlankKeys = droppedUnknown,
            keysWithoutVirtualKey = unmappedIds,
        )
    }
}

/** A converted layout and an honest account of what did not survive. */
data class ConvertedKeymanLayout(
    /** [LayoutSpec.langId] is deliberately blank; the caller assigns it. */
    val layout: LayoutSpec,
    val report: KeymanConversionReport,
    /**
     * What the repair pass had to add - typically a way back from a symbols
     * layer, or a spacebar. Worth showing on import: it means the upstream
     * keyboard leaned on Keyman's own frame for something ours expects the
     * layout to carry.
     */
    val repairNotes: List<LayoutMessage> = emptyList(),
)

/**
 * What the conversion could not carry across. Every field is a count rather
 * than a flag so the import screen can say how much, not just whether — a
 * keyboard that lost one diagonal flick and one that lost forty are different
 * situations for the person deciding whether to keep it.
 */
data class KeymanConversionReport(
    /**
     * Keys whose repeated-tap cycle did not survive: a cycle on a frame key or
     * a layer switch, or one with a step that has nothing to show. Cycles on
     * keys that type are kept.
     */
    val droppedMultitaps: Int = 0,
    /** Flicks aimed at a diagonal, which our four directions cannot express. */
    val droppedDiagonalFlicks: Int = 0,
    /** Frame keys with no counterpart here, e.g. hide-keyboard. */
    val droppedSpecialKeys: Int = 0,
    /** Keys with neither a cap nor an id we could use. */
    val droppedBlankKeys: Int = 0,
    /** Keys that type their cap because their id named no virtual key. */
    val keysWithoutVirtualKey: Int = 0,
) {
    val isClean: Boolean
        get() = droppedMultitaps == 0 && droppedDiagonalFlicks == 0 &&
            droppedSpecialKeys == 0 && droppedBlankKeys == 0 && keysWithoutVirtualKey == 0
}
