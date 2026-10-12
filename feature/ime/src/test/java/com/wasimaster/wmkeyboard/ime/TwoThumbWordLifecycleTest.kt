package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #640: the service side of a two-thumb word (#638), gesture by gesture.
 *
 * This module has no dictionary, so every decode here finds no word at all —
 * which is exactly the state #640 is about: a word still being built whose
 * gestures finish nothing yet. With no prefix to show either, the field shows
 * the letters under the gestures ([DuetWord.literal]); [literalOf] works the
 * same thing out from the grid, so the tests do not depend on which key sits
 * where.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbWordLifecycleTest {

    private val grid = glideKeyGrid()
    private val space = Key(label = " ", action = KeyAction.Space)

    private fun keyboard(): Pair<WMKeyboardService, RecordingEditor> {
        val settings = KeyboardSettings(
            learnFromTyping = false,
            gesture = GestureSettings(twoThumbGlide = true),
        )
        val (service, editor, _) = glideKeyboard(glideReadyState(settings = settings))
        return service to editor
    }

    private fun swipe(service: WMKeyboardService, row: Int) =
        service.onGesture(straightStroke(row), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    private fun field(service: WMKeyboardService, name: String): Any? {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(service)
    }

    private fun gestures(service: WMKeyboardService): Int = (field(service, "duetWord") as DuetWord).snapshot().size

    private fun decoding(service: WMKeyboardService): Boolean = (field(service, "duetJob") as Job?)?.isActive == true

    /** The keys a straight stroke starts and ends on, the way the field spells an unread swipe. */
    private fun literalOf(stroke: List<GesturePoint>): String {
        fun nearest(p: GesturePoint): KeyCenter =
            grid.minBy { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) }
        val first = Character.toLowerCase(nearest(stroke.first()).codePoint)
        val last = Character.toLowerCase(nearest(stroke.last()).codePoint)
        return buildString {
            appendCodePoint(first)
            if (last != first) appendCodePoint(last)
        }
    }

    @Test
    fun `gestures that finish no word yet are all kept, and nothing is typed`() {
        val (service, editor) = keyboard()
        val first = literalOf(straightStroke(0))
        val both = first + literalOf(straightStroke(1))

        swipe(service, 0)
        settle { editor.text.toString() == first }
        assertEquals("the first swipe never reached the field", first, editor.text.toString())

        swipe(service, 1)
        settle { editor.text.toString() == both }

        assertEquals("a gesture was dropped from the word in progress", both, editor.text.toString())
        assertEquals("the word lost a gesture from its buffer", 2, gestures(service))
        assertTrue("something was typed before the word ended: ${editor.commits}", editor.commits.isEmpty())
    }

    @Test
    fun `space while the last gesture is still being read types the whole word, once`() {
        val (service, editor) = keyboard()
        val first = literalOf(straightStroke(0))
        val both = first + literalOf(straightStroke(1))

        swipe(service, 0)
        settle { editor.text.toString() == first }
        // The second gesture's decode hops to another thread and has not come
        // back yet: the looper is not pumped before the space.
        swipe(service, 1)
        service.onKey(space)
        settle { !decoding(service) }
        // Then give the cancelled decode every chance to come home and write
        // over the field anyway: its thread finishes, and its resumption is a
        // post to this looper.
        repeat(LATE_ROUNDS) {
            Thread.sleep(LATE_STEP_MS)
            settle { true }
        }

        assertEquals("the space typed a stale reading, or the late one landed after it", "$both ", editor.text.toString())
        assertEquals("the word did not end with its space", 0, gestures(service))
    }

    private companion object {
        const val LATE_ROUNDS = 20
        const val LATE_STEP_MS = 10L
    }
}
