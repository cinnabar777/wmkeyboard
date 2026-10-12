package com.wasimaster.wmkeyboard.core.tools

/**
 * Reads a model's answer into blocks a chat bubble can draw: paragraphs,
 * headings, list items, task items, quotes and fenced code.
 *
 * [AiMarkdown] answers a different question. It removes the markers, because a
 * text field cannot draw them. A chat bubble can, and a fenced block there is
 * worth more than its text: it is the part of an answer the user most often
 * wants to copy alone, so the bubble has to know where one starts and stops.
 *
 * Deliberately a small subset, and no Compose: the drawing is the caller's, so
 * this runs on the JVM under test. The inline rules are the conservative ones
 * [AiMarkdown] uses, for the same reason: `2 * 3 * 4` and `file_name` are not
 * emphasis.
 *
 * The answer arrives as a stream, so every rule has to read a half-written
 * text sensibly. A fence that has not closed yet is a code block that runs to
 * the end ([Block.Code.closed] is false), and an emphasis marker without its
 * partner is plain text until the partner arrives.
 *
 * Every block carries the index of the source line it starts on ([Block.line],
 * into `text.lines()`). The Notes tool (#560) needs it twice: a tap on a task
 * item rewrites that one line to tick it, and a tap on any other block opens
 * the editor with the caret at that line.
 */
object AiMarkdownBlocks {

    enum class Style { BOLD, ITALIC, CODE }

    /** [style] over `[start, end)` of [Inline.text]. */
    data class Span(val start: Int, val end: Int, val style: Style)

    /** One run of text with its markers removed and turned into [spans]. */
    data class Inline(val text: String, val spans: List<Span> = emptyList())

    sealed interface Block {
        /** Index of the first source line of this block, into the parsed text's `lines()`. */
        val line: Int

        data class Paragraph(val inline: Inline, override val line: Int = 0) : Block
        data class Heading(val level: Int, val inline: Inline, override val line: Int = 0) : Block

        /** [marker] is what the bubble draws in front: a dot, or `3.` */
        data class Bullet(val indent: Int, val marker: String, val inline: Inline, override val line: Int = 0) : Block

        /**
         * A task-list item, `- [ ] text` or `- [x] text`. Its own block rather
         * than a bullet whose text starts with a bracket, so a reader can draw a
         * box and, through [line], tick it by rewriting the source.
         */
        data class Task(val indent: Int, val checked: Boolean, val inline: Inline, override val line: Int = 0) : Block
        data class Quote(val inline: Inline, override val line: Int = 0) : Block

        /** [code] has no fences and no trailing newline. */
        data class Code(val language: String, val code: String, val closed: Boolean, override val line: Int = 0) : Block
        data class Rule(override val line: Int = 0) : Block
    }

    /**
     * A task line taken apart: what sits before the box, the box, and the text
     * after it. [indent] and [marker] are kept verbatim so a rewrite puts the
     * line back exactly as it was, with only the box changed.
     */
    data class TaskLine(val indent: String, val marker: Char, val checked: Boolean, val text: String)

    private val FENCE = Regex("""^ {0,3}(```|~~~)\s*([\w+#.-]*).*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6}) +(.*?)( +#+ *)?$""")
    private val BULLET = Regex("""^( *)[-*+] +(.*)$""")
    private val ORDERED = Regex("""^( *)(\d{1,3})[.)] +(.*)$""")
    private val QUOTE = Regex("""^ {0,3}(?:> ?)+(.*)$""")
    private val RULE = Regex("""^ {0,3}([-*_])( *\1){2,} *$""")

    /**
     * `- [ ] text`, `- [x] text`, or a bare `- [ ]` somebody has only just typed.
     * One to four spaces between the marker and the box, as CommonMark allows.
     */
    private val TASK = Regex("""^( *)([-*+]) {1,4}\[([ xX])\](?: +(.*)|)$""")

    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC = Regex("""(?<![\w*_])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![\w*_])""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]*)]\((\S*?)\)""")

    /** [line] read as a task item, or null when it is not one. The same rule [parse] uses. */
    fun task(line: String): TaskLine? {
        val match = TASK.matchEntire(line) ?: return null
        return TaskLine(
            indent = match.groupValues[1],
            marker = match.groupValues[2][0],
            checked = match.groupValues[3] != " ",
            text = match.groupValues[4],
        )
    }

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        var paragraphStart = 0
        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += Block.Paragraph(inline(paragraph.joinToString("\n")), paragraphStart)
            paragraph.clear()
        }

        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val lineIndex = i
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val mark = fence.groupValues[1]
                val code = ArrayList<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(mark) && lines[i].trim().all { it == mark[0] }) {
                        closed = true
                        i++
                        break
                    }
                    code += lines[i]
                    i++
                }
                blocks += Block.Code(fence.groupValues[2], code.joinToString("\n"), closed, lineIndex)
                continue
            }
            i++
            if (line.isBlank()) {
                flush()
                continue
            }
            // Before the bullet rule: `* * *` is a rule, not a list of stars.
            if (RULE.matches(line)) {
                flush()
                blocks += Block.Rule(lineIndex)
                continue
            }
            val heading = HEADING.matchEntire(line)
            // Before the bullet rule too: a task is a bullet with a box in front.
            val task = TASK.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]), lineIndex)
                }
                task != null -> {
                    flush()
                    blocks += Block.Task(
                        task.groupValues[1].length / 2,
                        task.groupValues[3] != " ",
                        inline(task.groupValues[4]),
                        lineIndex,
                    )
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Bullet(bullet.groupValues[1].length / 2, "•", inline(bullet.groupValues[2]), lineIndex)
                }
                ordered != null -> {
                    flush()
                    blocks += Block.Bullet(
                        ordered.groupValues[1].length / 2,
                        ordered.groupValues[2] + ".",
                        inline(ordered.groupValues[3]),
                        lineIndex,
                    )
                }
                quote != null -> {
                    flush()
                    blocks += Block.Quote(inline(quote.groupValues[1]), lineIndex)
                }
                else -> {
                    if (paragraph.isEmpty()) paragraphStart = lineIndex
                    paragraph += line.trimEnd()
                }
            }
        }
        flush()
        return blocks
    }

    /** [text] with its inline markers turned into spans. */
    fun inline(text: String): Inline {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var at = 0
        while (at < text.length) {
            val next = listOfNotNull(
                CODE.find(text, at), LINK.find(text, at), BOLD.find(text, at), ITALIC.find(text, at),
            ).minByOrNull { it.range.first }
            if (next == null) break
            out.append(text, at, next.range.first)
            val start = out.length
            when (next.value.first()) {
                '`' -> {
                    out.append(next.groupValues[1])
                    spans += Span(start, out.length, Style.CODE)
                }
                '[' -> {
                    val (label, url) = next.destructured
                    out.append(
                        when {
                            label.isBlank() -> url
                            url.isBlank() || url == label -> label
                            else -> "$label ($url)"
                        },
                    )
                }
                else -> {
                    val bold = next.groupValues[1].length == 2
                    // Emphasis nests: bold text can hold italics and code.
                    val inner = inline(next.groupValues[2])
                    out.append(inner.text)
                    inner.spans.mapTo(spans) { it.copy(start = it.start + start, end = it.end + start) }
                    spans += Span(start, out.length, if (bold) Style.BOLD else Style.ITALIC)
                }
            }
            at = next.range.last + 1
        }
        out.append(text, at, text.length)
        return Inline(out.toString(), spans)
    }
}
