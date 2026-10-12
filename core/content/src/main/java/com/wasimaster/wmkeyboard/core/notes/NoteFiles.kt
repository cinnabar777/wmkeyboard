package com.wasimaster.wmkeyboard.core.notes

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Notes as files: one note to Markdown or plain text and back, and a whole
 * set as a zip of such files.
 *
 * The shape is deliberately not front matter. A `.md` opened in any editor
 * should read as the note, not as a configuration block followed by the note:
 *
 * ```
 * # Title                 (only when the user typed a title the body does not already open with)
 *
 * the body, verbatim
 *
 * Tags: work, school stuff   (only when the note has tags; always the last line)
 * ```
 *
 * Tags are comma-separated rather than `#work #school`, because a tag may hold
 * spaces and a `#` at the start of a line turns into a heading the moment the
 * text is moved around. The store strips commas from tag names, so the line
 * splits without ambiguity. Reading a file leaves a leading heading in the
 * body: [Note.displayTitle] derives the same words from it, so the round trip
 * is lossless for the text and the list looks the same.
 *
 * Policy-free on purpose: the caller decides which notes travel (a locked
 * note does not leave the app without the user passing its lock first).
 */
object NoteFiles {

    enum class Format(val extension: String, val mimeType: String) {
        MARKDOWN("md", "text/markdown"),
        TEXT("txt", "text/plain"),
    }

    const val ZIP_MIME = "application/zip"

    /** Most files one zip import reads, and most bytes one entry may hold: both zip-bomb guards. */
    const val MAX_IMPORT_FILES = 1_000
    const val MAX_IMPORT_BYTES = 4L * NoteStore.MAX_BODY_CHARS

    /** File names this reads as notes, by extension, ignoring case. */
    val IMPORT_EXTENSIONS: Set<String> = setOf("md", "markdown", "txt", "text")

    private val TAGS_LINE = Regex("""^tags:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("""^#{1,6}\s+(.*?)(\s+#+\s*)?$""")
    private val SLUG_KEEP = Regex("""[^\p{L}\p{N}]+""")

    /** [note] as text, see the class note for the shape. */
    fun toText(note: Note): String {
        val out = StringBuilder()
        val body = note.body.trim()
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }
        val headingAlreadyThere = firstLine != null &&
            HEADING.matchEntire(firstLine)?.groupValues?.get(1)?.trim() == note.title.trim()
        if (note.title.isNotBlank() && !headingAlreadyThere) {
            out.append("# ").append(note.title.trim()).append("\n\n")
        }
        out.append(body)
        if (note.tags.isNotEmpty()) {
            if (out.isNotEmpty()) out.append("\n\n")
            out.append("Tags: ").append(note.tags.joinToString(", "))
        }
        out.append('\n')
        return out.toString()
    }

    /**
     * A note read from [text], not yet stored (id 0; hand it to
     * [NoteStore.add]). A trailing `Tags:` line becomes the tags. [modifiedAt]
     * is the file's time when the picker gives one, so an imported note keeps
     * the date it was written rather than the date it was imported.
     */
    fun fromText(fileName: String, text: String, modifiedAt: Long = System.currentTimeMillis()): Note {
        var body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()
        var tags: List<String> = emptyList()
        val lastLine = body.lineSequence().lastOrNull { it.isNotBlank() }
        if (lastLine != null) {
            TAGS_LINE.matchEntire(lastLine.trim())?.let { match ->
                tags = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                body = body.substring(0, body.lastIndexOf(lastLine)).trimEnd()
            }
        }
        // A file whose body is empty still has a name worth keeping as the title.
        val title = if (body.isBlank()) stem(fileName) else ""
        return Note(id = 0, body = body, title = title, tags = tags, createdAt = modifiedAt, updatedAt = modifiedAt)
    }

    /**
     * A file name for [note]: the title as a slug, the id when the title gives
     * nothing, and a `-2`, `-3` suffix while [taken] already holds the name.
     * The chosen name is added to [taken].
     */
    fun fileName(note: Note, format: Format, taken: MutableSet<String>): String {
        val base = slug(note.displayTitle()).ifEmpty { "note-${note.id}" }
        var candidate = "$base.${format.extension}"
        var n = 2
        while (!taken.add(candidate.lowercase(Locale.ROOT))) {
            candidate = "$base-$n.${format.extension}"
            n++
        }
        return candidate
    }

    /** [notes] as one zip, one file each, named by [fileName]. */
    fun writeZip(notes: List<Note>, format: Format, out: OutputStream) {
        val taken = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for (note in notes) {
                val entry = ZipEntry(fileName(note, format, taken))
                if (note.updatedAt > 0) entry.time = note.updatedAt
                zip.putNextEntry(entry)
                zip.write(toText(note).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /**
     * The notes in a zip: every `.md`/`.txt` entry at any depth, folders and
     * other files skipped, capped by [MAX_IMPORT_FILES] and [MAX_IMPORT_BYTES].
     */
    fun readZip(input: InputStream): List<Note> {
        val out = ArrayList<Note>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (!isNoteFile(name)) continue
                if (out.size >= MAX_IMPORT_FILES) break
                val bytes = readCapped(zip, MAX_IMPORT_BYTES) ?: continue
                val time = entry.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                out += fromText(name, String(bytes), time)
            }
        }
        return out
    }

    /** Whether [fileName] is one this reads as a note. */
    fun isNoteFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMPORT_EXTENSIONS && '.' in fileName

    /** Letters and digits kept, everything else one dash, at most 60 characters. */
    fun slug(text: String): String =
        text.replace(SLUG_KEEP, "-").trim('-').take(60).trim('-')

    private fun stem(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').trim()

    /** The stream up to [max] bytes, or null when it holds more. */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > max) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}
