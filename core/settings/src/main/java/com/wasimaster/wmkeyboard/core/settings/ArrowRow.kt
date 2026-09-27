package com.wasimaster.wmkeyboard.core.settings

/**
 * One key of the arrow row under the keyboard (issue #369). The row draws all
 * four, in the order [LayoutBehaviorSettings.arrowRowOrder] gives.
 */
enum class ArrowKey { LEFT, UP, DOWN, RIGHT }

/** Left, up, down, right: the order a laptop's inverted T reads from left to right. */
val DefaultArrowRowOrder: List<ArrowKey> = ArrowKey.entries.toList()

/**
 * [stored] as a full ordering of the four arrows.
 *
 * Unknown names are dropped and missing arrows are appended in their default
 * place, so a hand-edited or partly restored value still draws four keys, each
 * once. Anything unreadable falls back to [DefaultArrowRowOrder].
 */
fun decodeArrowRowOrder(stored: String?): List<ArrowKey> {
    if (stored.isNullOrBlank()) return DefaultArrowRowOrder
    val named = stored.split(',')
        .mapNotNull { name -> ArrowKey.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
    return named + DefaultArrowRowOrder.filter { it !in named }
}

/** The stored form [decodeArrowRowOrder] reads back. */
fun encodeArrowRowOrder(order: List<ArrowKey>): String =
    decodeArrowRowOrder(order.joinToString(",") { it.name }).joinToString(",") { it.name }
