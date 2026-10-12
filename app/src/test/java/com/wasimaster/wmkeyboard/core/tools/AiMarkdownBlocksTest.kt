package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Span
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMarkdownBlocksTest {

    @Test
    fun `plain prose is one paragraph with no spans`() {
        val blocks = AiMarkdownBlocks.parse("Hello there.\nStill the same paragraph.")
        val paragraph = blocks.single() as Block.Paragraph
        assertEquals("Hello there.\nStill the same paragraph.", paragraph.inline.text)
        assertTrue(paragraph.inline.spans.isEmpty())
    }

    @Test
    fun `a blank line starts a new paragraph`() {
        assertEquals(2, AiMarkdownBlocks.parse("One.\n\nTwo.").size)
    }

    @Test
    fun `a fenced block keeps its code and its language, and loses its fences`() {
        val blocks = AiMarkdownBlocks.parse("Try this:\n```kotlin\nval a = 1\n  val b = 2\n```\nDone.")
        assertEquals(3, blocks.size)
        val code = blocks[1] as Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\n  val b = 2", code.code)
        assertTrue(code.closed)
    }

    @Test
    fun `a fence still open mid-stream is a block that runs to the end`() {
        val code = AiMarkdownBlocks.parse("```\nprint(1)\nprint(").single() as Block.Code
        assertEquals("print(1)\nprint(", code.code)
        assertFalse(code.closed)
    }

    @Test
    fun `markers inside a fence are code, not markdown`() {
        val code = AiMarkdownBlocks.parse("```\n# not a heading\n- not a bullet\n**not bold**\n```").single() as Block.Code
        assertEquals("# not a heading\n- not a bullet\n**not bold**", code.code)
    }

    @Test
    fun `headings, bullets, numbers, quotes and rules are read as blocks`() {
        val blocks = AiMarkdownBlocks.parse("## Title\n- first\n  - nested\n3. third\n> said\n---")
        assertEquals(Block.Heading(2, AiMarkdownBlocks.Inline("Title"), line = 0), blocks[0])
        assertEquals(Block.Bullet(0, "•", AiMarkdownBlocks.Inline("first"), line = 1), blocks[1])
        assertEquals(Block.Bullet(1, "•", AiMarkdownBlocks.Inline("nested"), line = 2), blocks[2])
        assertEquals(Block.Bullet(0, "3.", AiMarkdownBlocks.Inline("third"), line = 3), blocks[3])
        assertEquals(Block.Quote(AiMarkdownBlocks.Inline("said"), line = 4), blocks[4])
        assertEquals(Block.Rule(line = 5), blocks[5])
    }

    @Test
    fun `task items are their own blocks, ticked or not`() {
        val blocks = AiMarkdownBlocks.parse("- [ ] milk\n- [x] eggs\n  - [X] nested\n- [ ]\n- [ ]x not a task")
        assertEquals(Block.Task(0, false, AiMarkdownBlocks.Inline("milk"), line = 0), blocks[0])
        assertEquals(Block.Task(0, true, AiMarkdownBlocks.Inline("eggs"), line = 1), blocks[1])
        assertEquals(Block.Task(1, true, AiMarkdownBlocks.Inline("nested"), line = 2), blocks[2])
        // A box somebody has only just typed is a task with nothing after it yet.
        assertEquals(Block.Task(0, false, AiMarkdownBlocks.Inline(""), line = 3), blocks[3])
        // No space after the box: a bullet whose text happens to start with brackets.
        assertEquals(Block.Bullet(0, "•", AiMarkdownBlocks.Inline("[ ]x not a task"), line = 4), blocks[4])
    }

    @Test
    fun `line indices survive a fenced block and a multi-line paragraph`() {
        val blocks = AiMarkdownBlocks.parse("intro\nstill intro\n\n```\ncode\nmore\n```\n- [ ] after")
        assertEquals(0, blocks[0].line)
        assertEquals(3, blocks[1].line)
        val task = blocks[2] as Block.Task
        assertEquals(7, task.line)
        assertEquals(AiMarkdownBlocks.TaskLine("", '-', false, "after"), AiMarkdownBlocks.task("- [ ] after"))
        assertEquals(null, AiMarkdownBlocks.task("- plain bullet"))
    }

    @Test
    fun `bold, italic and code become spans over the text without the markers`() {
        val inline = AiMarkdownBlocks.inline("Use **bold**, *slant* and `code`.")
        assertEquals("Use bold, slant and code.", inline.text)
        assertEquals(
            listOf(Span(4, 8, Style.BOLD), Span(10, 15, Style.ITALIC), Span(20, 24, Style.CODE)),
            inline.spans,
        )
    }

    @Test
    fun `emphasis nests`() {
        val inline = AiMarkdownBlocks.inline("**very *much* so**")
        assertEquals("very much so", inline.text)
        assertTrue(Span(0, 12, Style.BOLD) in inline.spans)
        assertTrue(Span(5, 9, Style.ITALIC) in inline.spans)
    }

    @Test
    fun `arithmetic and file names are not emphasis`() {
        assertEquals("2 * 3 * 4", AiMarkdownBlocks.inline("2 * 3 * 4").text)
        assertTrue(AiMarkdownBlocks.inline("2 * 3 * 4").spans.isEmpty())
        assertTrue(AiMarkdownBlocks.inline("my_file_name.txt").spans.isEmpty())
    }

    @Test
    fun `a marker with no partner yet is plain text`() {
        val inline = AiMarkdownBlocks.inline("This is **half")
        assertEquals("This is **half", inline.text)
        assertTrue(inline.spans.isEmpty())
    }

    @Test
    fun `a link keeps its address`() {
        assertEquals("the docs (https://x.dev)", AiMarkdownBlocks.inline("[the docs](https://x.dev)").text)
    }
}
