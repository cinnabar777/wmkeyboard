package com.wasimaster.wmkeyboard.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteChecklistTest {

    @Test
    fun `progress counts ticked boxes and ignores boxes inside a fence`() {
        val body = "- [ ] a\n- [x] b\n  * [X] c\n```\n- [ ] not a task\n```\nprose - [ ] nor this"
        assertEquals(NoteChecklist.Progress(2, 3), NoteChecklist.progress(body))
        assertEquals(NoteChecklist.Progress(0, 0), NoteChecklist.progress("no boxes here"))
    }

    @Test
    fun `setTask keeps the indent and the marker and writes a lowercase x`() {
        assertEquals("  * [x] c", NoteChecklist.setTask("  * [ ] c", 0, checked = true))
        assertEquals("- [ ] a\n+ [ ] b", NoteChecklist.setTask("- [ ] a\n+ [X] b", 1, checked = false))
        // An empty box somebody only just typed.
        assertEquals("- [x]", NoteChecklist.setTask("- [ ]", 0, checked = true))
    }

    @Test
    fun `setTask is idempotent and refuses lines that are not tasks`() {
        val body = "- [x] done\nprose\n```\n- [ ] code\n```"
        assertEquals(body, NoteChecklist.setTask(body, 0, checked = true))
        assertNull(NoteChecklist.setTask(body, 1, checked = true))
        assertNull(NoteChecklist.setTask(body, 3, checked = true))
        assertNull(NoteChecklist.setTask(body, 40, checked = true))
        assertNull(NoteChecklist.setTask(body, -1, checked = true))
    }

    @Test
    fun `toggleTask flips whichever way the box was`() {
        assertEquals("- [x] a\n- [ ] b", NoteChecklist.toggleTask("- [ ] a\n- [ ] b", 0))
        assertEquals("- [ ] a", NoteChecklist.toggleTask("- [x] a", 0))
        assertNull(NoteChecklist.toggleTask("plain", 0))
    }
}
