package com.wasimaster.wmkeyboard.core.prediction

/**
 * Continuous touch-likelihood model for typo scoring: where the finger
 * actually landed, not just which key won the hit test.
 *
 * All coordinates are in key-width units in keyboard space (the same
 * convention the gesture decoder uses), so the model is independent of
 * density, orientation and keyboard size. The IME feeds key centers from the
 * live layout and per-keystroke tap positions; when either is missing the
 * engine falls back to the discrete [KeyProximity] adjacency weights.
 */
data class TouchPoint(val x: Float, val y: Float)

/**
 * Per-key 2D Gaussian likelihood. [centers] maps each committed character to
 * its key center in key-width units.
 */
class KeyTouchModel(private val centers: Map<Char, TouchPoint>) {

    private val keysArray: CharArray = centers.keys.toCharArray()
    private val centersArray: Array<TouchPoint> = Array(keysArray.size) { centers[keysArray[it]]!! }

    fun knows(ch: Char): Boolean = centers.containsKey(ch)

    /** Where [ch]'s key is drawn, in key widths, or null off the model. */
    fun center(ch: Char): TouchPoint? = centers[ch]

    /**
     * ln P(tap at [p] | intended key [ch]) up to a constant that cancels in
     * ratios: `-(d^2) / (2 * sigma^2)` with sigma in key widths.
     */
    fun logLikelihood(p: TouchPoint, ch: Char): Double {
        val center = centers[ch] ?: return Double.NEGATIVE_INFINITY
        val dx = (p.x - center.x).toDouble()
        val dy = (p.y - center.y).toDouble()
        return -(dx * dx + dy * dy) / (2.0 * SIGMA * SIGMA)
    }

    /** The most likely intended key for a tap, or null off-keyboard. */
    fun bestKey(p: TouchPoint): Char? {
        var best: Char? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in keysArray.indices) {
            val center = centersArray[i]
            val dx = (p.x - center.x).toDouble()
            val dy = (p.y - center.y).toDouble()
            val score = -(dx * dx + dy * dy)
            if (score > bestScore) {
                bestScore = score
                best = keysArray[i]
            }
        }
        return best
    }

    companion object {
        /** Tap-scatter sigma in key widths. */
        const val SIGMA = 0.5
    }
}
