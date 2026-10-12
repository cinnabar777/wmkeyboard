package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesBackupTest {

    private fun file(vararg notes: String, tags: String = """[{"name":"Work","colour":6}]"""): JsonObject =
        Json.parseToJsonElement("""{"version":1,"notes":[${notes.joinToString(",")}],"tags":$tags}""").jsonObject

    private val plain = """{"id":1,"body":"a","createdAt":1,"updatedAt":1}"""
    private val locked = """{"id":2,"body":"secret","locked":true,"createdAt":2,"updatedAt":2}"""
    private val trashed = """{"id":3,"body":"gone","deletedAt":5,"createdAt":3,"updatedAt":3}"""

    @Test
    fun `portable drops locked notes unless secrets go, and keeps everything else`() {
        val element = file(plain, locked, trashed)
        val stripped = NotesBackup.portable(element, includeSecrets = false).jsonObject
        assertEquals(listOf(1L, 3L), stripped["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() })
        assertEquals(element["tags"], stripped["tags"])
        assertEquals(element["version"], stripped["version"])
        assertSame(element, NotesBackup.portable(element, includeSecrets = true))
    }

    @Test
    fun `restoring a bundle without secrets keeps this phone's locked notes`() {
        val incoming = file(plain)
        val local = file(plain, locked, trashed)
        val merged = NotesBackup.keepingLocalLocked(incoming, local)
        assertEquals(listOf(1L, 2L), merged["notes"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() })
        // A locked note the bundle does carry wins; nothing is doubled.
        val carried = file(plain, locked.replace("secret", "newer"))
        val again = NotesBackup.keepingLocalLocked(carried, local)
        assertEquals(2, again["notes"]!!.jsonArray.size)
        assertEquals("newer", again["notes"]!!.jsonArray[1].jsonObject["body"]!!.jsonPrimitive.content)
        assertSame(incoming, NotesBackup.keepingLocalLocked(incoming, null))
    }

    @Test
    fun `count leaves the trash out and hasLocked finds a locked note`() {
        assertEquals(2, NotesBackup.count(file(plain, locked, trashed)))
        assertEquals(0, NotesBackup.count(JsonArray(emptyList())))
        assertTrue(NotesBackup.hasLocked(file(plain, locked)))
        assertFalse(NotesBackup.hasLocked(file(plain, trashed)))
    }
}
