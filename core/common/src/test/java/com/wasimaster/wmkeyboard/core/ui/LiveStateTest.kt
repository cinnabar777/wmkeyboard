package com.wasimaster.wmkeyboard.core.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [LiveState]: a write to one field recomposes the rows that read that field,
 * and nothing else — not the other rows, and not the screen holding them.
 */
@RunWith(RobolectricTestRunner::class)
class LiveStateTest {

    private val compose = createComposeRule()

    // As in LiveSliderTest: the rule launches a ComponentActivity this library
    // module's test manifest does not declare.
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val app = RuntimeEnvironment.getApplication()
                    shadowOf(app.packageManager)
                        .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                }
            },
        )
        .around(compose)

    private data class Settings(val sound: Boolean = false, val volume: Float = 0.5f, val tags: List<String> = listOf("a"))

    private val stored = mutableStateOf(Settings())
    private val live = LiveState(stored)
    private val compositions = mutableMapOf<String, Int>()
    private val seen = mutableMapOf<String, Any?>()

    @Composable
    private fun Counted(name: String, value: Any?) {
        SideEffect {
            compositions[name] = (compositions[name] ?: 0) + 1
            seen[name] = value
        }
    }

    @Composable
    private fun Screen(live: LiveState<Settings>) {
        Counted("screen", null)
        Column {
            Row("sound") { live.watch { it.sound } }
            Row("volume") { live.watch { it.volume } }
            Row("tags") { live.watch { it.tags } }
        }
    }

    @Composable
    private fun Row(name: String, read: @Composable () -> Any?) {
        Counted(name, read())
    }

    private fun write(change: (Settings) -> Settings) {
        stored.value = change(stored.value)
        compose.waitForIdle()
    }

    @Test
    fun `a write recomposes only the row that reads the field`() {
        compose.setContent { Screen(live) }
        compose.waitForIdle()
        val before = compositions.toMap()

        write { it.copy(sound = true) }

        assertEquals(before.getValue("sound") + 1, compositions["sound"])
        assertEquals(true, seen["sound"])
        assertEquals("volume did not change", before["volume"], compositions["volume"])
        assertEquals("tags did not change", before["tags"], compositions["tags"])
        assertEquals("the screen reads nothing", before["screen"], compositions["screen"])
    }

    @Test
    fun `an equal value is not a change`() {
        compose.setContent { Screen(live) }
        compose.waitForIdle()
        val before = compositions.toMap()

        // A new list, and a new settings object, with the same contents.
        write { it.copy(tags = listOf("a")) }

        assertEquals(before, compositions.toMap())
    }

    @Test
    fun `a selector that captures an input follows it`() {
        var field by mutableStateOf("sound")
        compose.setContent {
            val name = field
            Counted("picked", live.watch { if (name == "sound") it.sound else it.volume })
        }
        compose.waitForIdle()
        assertEquals(false, seen["picked"])

        field = "volume"
        compose.waitForIdle()
        assertEquals(0.5f, seen["picked"])

        write { it.copy(volume = 0.75f) }
        assertEquals(0.75f, seen["picked"])
    }

    @Test
    fun `value reads the whole thing now`() {
        stored.value = stored.value.copy(volume = 0.25f)
        assertEquals(0.25f, live.value.volume)
    }
}
