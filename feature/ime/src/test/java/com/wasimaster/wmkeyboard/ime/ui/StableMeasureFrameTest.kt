package com.wasimaster.wmkeyboard.ime.ui

import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The IME template measures the input view twice per traversal, first against
 * the whole display and then against what the window really got. Issue #370:
 * sideways, the second is the display less the status bar, and content laid
 * out at the first ran that far past the screen's bottom edge.
 */
@RunWith(RobolectricTestRunner::class)
class StableMeasureFrameTest {

    /**
     * Stands in for the keyboard: a board of [board] px and a key-preview band
     * that fills what the room leaves, up to [band] px.
     */
    private class Content(context: Context, val board: Int, val band: Int) : View(context) {
        var lastRoom = -1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            lastRoom = MeasureSpec.getSize(heightMeasureSpec)
            val wanted = board + band
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minOf(wanted, lastRoom))
        }
    }

    private lateinit var frame: StableMeasureFrame
    private lateinit var content: Content

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        frame = StableMeasureFrame(context)
    }

    private fun holding(board: Int, band: Int) {
        frame.removeAllViews()
        content = Content(frame.context, board, band)
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /** One traversal: a measure pass per offer, then the layout. Returns the frame's height. */
    private fun traversal(vararg offers: Int): Int {
        for (offer in offers) {
            frame.measure(
                MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(offer, MeasureSpec.AT_MOST),
            )
        }
        frame.layout(0, 0, WIDTH, frame.measuredHeight)
        return frame.measuredHeight
    }

    @Test
    fun `a window held below what the keyboard asked for is what it lays out in`() {
        holding(board = 800, band = 350)
        // The first pass hands over the whole display, the window then gets the
        // display less the status bar.
        val height = traversal(1220, 1090)
        assertEquals(1090, height)
        assertEquals(1090, content.measuredHeight)
    }

    @Test
    fun `later traversals start from the held height, so both passes agree`() {
        holding(board = 800, band = 350)
        traversal(1220, 1090)
        frame.measure(
            MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(1220, MeasureSpec.AT_MOST),
        )
        assertEquals(1090, content.lastRoom)
        assertEquals(1090, traversal(1090))
    }

    @Test
    fun `a second pass that only echoes the reported height keeps the whole room`() {
        holding(board = 500, band = 100)
        assertEquals(600, traversal(2400, 600))
        // Measured against the first pass's room both times: a panel that asks
        // for more next time still can have it.
        assertEquals(2400, content.lastRoom)
    }

    @Test
    fun `a new first offer forgets the held height`() {
        holding(board = 800, band = 350)
        traversal(1220, 1090)
        // Turned upright: a taller display, and the keyboard fits in it.
        assertEquals(1150, traversal(2400, 1150))
        assertEquals(2400, content.lastRoom)
    }

    private companion object {
        const val WIDTH = 1000
    }
}
