package com.wasimaster.wmkeyboard.core.perf

import android.util.Log
import android.view.View
import android.view.Window
import androidx.metrics.performance.FrameData
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import java.util.Locale

/**
 * Frame-by-frame jank reporting for one window, through JankStats: the
 * keyboard's own window, or the settings app's.
 *
 * Off unless a developer asks for it, on any build, release included:
 *
 *     adb shell setprop log.tag.WMJank DEBUG     # a summary each time the window goes away
 *     adb shell setprop log.tag.WMJank VERBOSE   # ...and one line per janky frame
 *     adb logcat -s WMJank
 *
 * The property is read each time the window comes back, so it takes effect on
 * the next show rather than needing a restart. Unset (the default) the monitor
 * never attaches: no listener, no per-frame callback, nothing for it to cost.
 *
 * A frame is janky by JankStats' own measure, twice the refresh interval, and
 * each one carries the screen that was showing when it was drawn (the
 * keyboard's open panel, the settings route), so a summary can say where the
 * stutter was rather than only that there was one.
 */
class JankMonitor(
    /** What the log lines call this window: "keyboard", "settings". */
    private val surface: String,
) {

    private var stats: JankStats? = null
    private var holder: PerformanceMetricsState.Holder? = null
    private var verbose = false
    private var screen: String? = null

    // Written from JankStats' frame-metrics thread, read on main at hide time.
    private val lock = Any()
    private var frames = 0
    private var janky = 0
    private var worstNanos = 0L
    private var worstState = ""
    private val jankyByScreen = HashMap<String, Int>()

    private val listener = JankStats.OnFrameListener { frame -> record(frame) }

    /** Starts counting [window]'s frames if the log property asks for it. Call on every show. */
    fun start(window: Window?) {
        if (window == null || !Log.isLoggable(TAG, Log.DEBUG)) {
            stop(report = false)
            return
        }
        verbose = Log.isLoggable(TAG, Log.VERBOSE)
        val tracking = stats
        if (tracking != null) {
            tracking.isTrackingEnabled = true
            return
        }
        stats = JankStats.createAndTrack(window, listener)
        holder = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
        screen?.let { holder?.state?.putState(STATE_SCREEN, it) }
    }

    /**
     * Stops counting, logging a summary of what was counted since the last one
     * when [report] is set. The window going away is what calls it.
     */
    fun stop(report: Boolean = true) {
        val tracking = stats ?: return
        tracking.isTrackingEnabled = false
        if (report) logSummary()
    }

    /** Tags the frames that follow with the screen on show. Cheap, and a no-op while off. */
    fun screen(name: String) {
        if (name == screen) return
        screen = name
        if (stats != null) holder?.state?.putState(STATE_SCREEN, name)
    }

    private fun record(frame: FrameData) {
        synchronized(lock) {
            frames++
            if (!frame.isJank) return
            janky++
            val state = frame.states.joinToString(" ") { "${it.key}=${it.value}" }
            val key = frame.states.firstOrNull { it.key == STATE_SCREEN }?.value ?: "?"
            jankyByScreen[key] = (jankyByScreen[key] ?: 0) + 1
            if (frame.frameDurationUiNanos > worstNanos) {
                worstNanos = frame.frameDurationUiNanos
                worstState = state
            }
            if (verbose) Log.v(TAG, "$surface: janky frame ${ms(frame.frameDurationUiNanos)} ms $state")
        }
    }

    private fun logSummary() {
        synchronized(lock) {
            if (frames > 0) {
                val share = janky * PERCENT / frames
                val byScreen = jankyByScreen.entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key} ${it.value}" }
                Log.d(
                    TAG,
                    "$surface: $frames frames, $janky janky ($share%), worst ${ms(worstNanos)} ms" +
                        (if (worstState.isEmpty()) "" else " [$worstState]") +
                        (if (byScreen.isEmpty()) "" else "; janky by screen: $byScreen"),
                )
            }
            frames = 0
            janky = 0
            worstNanos = 0L
            worstState = ""
            jankyByScreen.clear()
        }
    }

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_MS)

    companion object {
        private const val TAG = "WMJank"
        private const val STATE_SCREEN = "screen"
        private const val PERCENT = 100
        private const val NANOS_PER_MS = 1_000_000.0

        /**
         * Tags the frames of whichever window [view] is in with [name], for a
         * caller that has the view but not the monitor (a composable). A no-op
         * while that window is not being tracked.
         */
        fun screen(view: View, name: String) {
            PerformanceMetricsState.getHolderForHierarchy(view).state?.putState(STATE_SCREEN, name)
        }
    }
}
