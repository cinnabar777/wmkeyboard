package com.wasimaster.wmkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating keyboard button (issue #374): a small round button over every
 * app while the keyboard is closed. Tapping it brings the keyboard up, field or
 * no field.
 *
 * This is what the "Display over other apps" grant buys, and the reason it is
 * worth asking for. The shade's "Show keyboard" ([KeyboardControls]) has to
 * fight the shade itself: pulling it down moves the input focus to the system
 * UI, and when it closes again the app's window gets the focus back, which
 * Android answers by hiding the keyboard from any window that has no text
 * field. This button is a window that can never take the focus
 * (`FLAG_NOT_FOCUSABLE`), so pressing it changes nothing about who is focused,
 * and the keyboard's show request lands on exactly the window the user was
 * looking at.
 *
 * A plain View rather than Compose: the keyboard's lifecycle owner is stopped
 * whenever the keyboard is hidden, which is the only time this is on screen.
 *
 * Tap to show the keyboard, drag to move (it settles against the nearer side
 * and remembers where), press and hold to put it away until the keyboard next
 * opens.
 */
internal class SummonButton(
    private val context: Context,
    /** The tap: bring the keyboard up. */
    private val onTap: () -> Unit,
    /** A drag ended here, as fractions of the room the screen leaves the button. */
    private val onMoved: (xFraction: Float, yFraction: Float) -> Unit,
    /** The long press: away until the keyboard next opens. */
    private val onDismiss: () -> Unit,
) {

    private var view: ButtonView? = null
    private var params: WindowManager.LayoutParams? = null
    private var xFraction = 1f
    private var yFraction = 0.6f

    /**
     * A context Android lets draw an overlay window. From API 30 a window
     * context of the overlay type, so the window manager and the screen size
     * it reports belong to a display; before that the application context,
     * which is what overlay windows always used.
     */
    private val windowContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val display = context.getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
                context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }.getOrNull() ?: context.applicationContext
        } else {
            context.applicationContext
        }
    }

    private val windowManager: WindowManager
        get() = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val sizePx: Int
        get() = (BUTTON_DP * context.resources.displayMetrics.density).roundToInt()

    val showing: Boolean get() = view != null

    /**
     * Puts the button on screen at the given resting place, or moves it there
     * when it already is. Does nothing without the overlay grant, and nothing
     * when Android refuses the window anyway (a grant revoked a moment ago).
     */
    fun show(xFraction: Float, yFraction: Float) {
        val moved = xFraction != this.xFraction || yFraction != this.yFraction
        this.xFraction = xFraction
        this.yFraction = yFraction
        val current = view
        if (current != null) {
            if (moved && !current.dragging) place()
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val button = ButtonView(windowContext)
        val layout = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WM Keyboard button"
        }
        params = layout
        positionFromFractions(layout)
        val added = runCatching { windowManager.addView(button, layout) }.isSuccess
        if (added) view = button else params = null
    }

    /** Takes the button off screen. Safe to call when it is not there. */
    fun hide() {
        val current = view ?: return
        view = null
        params = null
        current.cancelPress()
        runCatching { windowManager.removeViewImmediate(current) }
    }

    /** The screen changed shape (rotation, fold): put the button back on it. */
    fun relayout() {
        if (view != null) place()
    }

    private fun place() {
        val layout = params ?: return
        val current = view ?: return
        positionFromFractions(layout)
        runCatching { windowManager.updateViewLayout(current, layout) }
    }

    private fun positionFromFractions(layout: WindowManager.LayoutParams) {
        val (roomX, roomY) = room()
        layout.x = (xFraction.coerceIn(0f, 1f) * roomX).roundToInt()
        layout.y = (yFraction.coerceIn(0f, 1f) * roomY).roundToInt()
    }

    /** How far the button's top-left corner can travel on each axis. */
    private fun room(): Pair<Int, Int> {
        val (width, height) = screenSize()
        return (width - sizePx).coerceAtLeast(0) to (height - sizePx).coerceAtLeast(0)
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** The button itself: its look, and the tap / drag / hold that drive it. */
    @SuppressLint("ViewConstructor")
    private inner class ButtonView(context: Context) : ImageView(context) {

        var dragging = false
            private set

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var held = false

        private val longPress = Runnable {
            held = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onDismiss()
        }

        init {
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FILL)
                setStroke((density).roundToInt().coerceAtLeast(1), STROKE)
            }
            setImageResource(R.drawable.ic_summon_keyboard)
            imageTintList = ColorStateList.valueOf(GLYPH)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (GLYPH_PADDING_DP * density).roundToInt()
            setPadding(pad, pad, pad, pad)
            alpha = REST_ALPHA
            contentDescription = context.getString(R.string.ime_summon_button_label)
            isClickable = true
            isFocusable = false
        }

        fun cancelPress() {
            removeCallbacks(longPress)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val layout = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = layout.x
                    downY = layout.y
                    dragging = false
                    held = false
                    alpha = 1f
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (held) return true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        removeCallbacks(longPress)
                    }
                    if (dragging) {
                        val (roomX, roomY) = room()
                        layout.x = (downX + dx.roundToInt()).coerceIn(0, roomX)
                        layout.y = (downY + dy.roundToInt()).coerceIn(0, roomY)
                        runCatching { windowManager.updateViewLayout(this, layout) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    alpha = REST_ALPHA
                    when {
                        held -> Unit
                        dragging -> settle(layout)
                        else -> performClick()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    alpha = REST_ALPHA
                    if (dragging) settle(layout)
                    dragging = false
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            onTap()
            return true
        }

        /** Ends a drag against the nearer side, and remembers it there. */
        private fun settle(layout: WindowManager.LayoutParams) {
            val (roomX, roomY) = room()
            layout.x = if (layout.x + sizePx / 2 < (roomX + sizePx) / 2) 0 else roomX
            runCatching { windowManager.updateViewLayout(this, layout) }
            xFraction = if (roomX == 0) 0f else layout.x.toFloat() / roomX
            yFraction = if (roomY == 0) 0f else layout.y.toFloat() / roomY
            onMoved(xFraction, yFraction)
        }
    }

    private companion object {
        const val BUTTON_DP = 48
        const val GLYPH_PADDING_DP = 12
        const val REST_ALPHA = 0.85f
        const val FILL = 0xE6202124.toInt()
        const val STROKE = 0x40FFFFFF
        const val GLYPH = 0xFFFFFFFF.toInt()
    }
}
