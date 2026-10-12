package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBox
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckBoxOutlineBlank
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks
import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks.Block
import com.wasimaster.wmkeyboard.ime.R

/**
 * The colours a chat answer is drawn in. Passed in rather than read off a
 * theme because two surfaces draw answers and they are painted by different
 * things: the keyboard's panel by the keyboard theme, the chat screen in the
 * settings app by Material.
 */
data class ChatMarkdownColors(
    val text: Color,
    /** Quotes, list markers and a code block's label. */
    val dim: Color,
    /** Behind inline code and code blocks. */
    val codeBackground: Color,
)

/**
 * A model's answer, drawn: paragraphs, headings, lists, quotes and code blocks,
 * with bold, italic and inline code inside them. See [AiMarkdownBlocks] for
 * what is read and why it is a subset.
 *
 * [codeActions] fills the right of each code block's header. It is a slot and
 * not a pair of buttons because what a block can do differs by surface: the
 * keyboard can put the code in the field behind it, the app can only copy.
 */
@Composable
fun ChatMarkdown(
    text: String,
    colors: ChatMarkdownColors,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    /**
     * A task item's box was tapped (#560). Null leaves the boxes as drawings:
     * a chat answer's checklist is the model's, not something to tick off.
     */
    onTaskToggle: ((Block.Task) -> Unit)? = null,
    /**
     * A block tapped (#560): the notes read view opens its editor at that
     * block's line. Null leaves the text inert, as a chat answer is.
     */
    onBlockTap: ((Block) -> Unit)? = null,
    codeActions: (@Composable RowScope.(Block.Code) -> Unit)? = null,
) {
    val blocks = remember(text) { AiMarkdownBlocks.parse(text) }
    val lineHeight = fontSize * LINE_HEIGHT
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            val tappable = if (onBlockTap != null && block !is Block.Task) {
                Modifier.fillMaxWidth().clickable { onBlockTap(block) }
            } else {
                Modifier
            }
            Box(tappable) {
            when (block) {
                is Block.Paragraph -> Text(
                    annotated(block.inline, colors),
                    color = colors.text,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                )
                is Block.Heading -> Text(
                    annotated(block.inline, colors),
                    color = colors.text,
                    // Three sizes are all a bubble has room to tell apart.
                    fontSize = fontSize * (if (block.level <= 1) 1.25f else if (block.level == 2) 1.12f else 1f),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = lineHeight * 1.12f,
                )
                is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 12).dp)) {
                    Text(block.marker, color = colors.dim, fontSize = fontSize, lineHeight = lineHeight)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        annotated(block.inline, colors),
                        color = colors.text,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                    )
                }
                is Block.Task -> TaskRow(block, colors, fontSize, lineHeight, onTaskToggle)
                is Block.Quote -> Row {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(with(androidx.compose.ui.platform.LocalDensity.current) { lineHeight.toDp() })
                            .background(colors.dim),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        annotated(block.inline, colors),
                        color = colors.dim,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                    )
                }
                is Block.Code -> CodeBlock(block, colors, fontSize, codeActions)
                is Block.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .height(1.dp)
                        .background(colors.dim.copy(alpha = colors.dim.alpha * 0.5f)),
                )
            }
            }
        }
    }
}

/**
 * A task item: a box, then its text struck through once ticked. Tappable only
 * when the caller handed over [onToggle]; the box is then a checkbox to
 * TalkBack, and the whole row is the target rather than the 18 dp glyph.
 */
@Composable
private fun TaskRow(
    block: Block.Task,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onToggle: ((Block.Task) -> Unit)?,
) {
    val toggleable = if (onToggle != null) {
        Modifier.toggleable(
            value = block.checked,
            role = Role.Checkbox,
            onValueChange = { onToggle(block) },
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.indent * 12).dp)
            .clip(RoundedCornerShape(6.dp))
            .then(toggleable)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (block.checked) colors.dim else colors.text,
            modifier = Modifier
                .padding(top = with(androidx.compose.ui.platform.LocalDensity.current) { ((lineHeight.toDp() - 18.dp) / 2).coerceAtLeast(0.dp) })
                .size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            annotated(block.inline, colors),
            color = if (block.checked) colors.dim else colors.text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textDecoration = if (block.checked) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
private fun CodeBlock(
    block: Block.Code,
    colors: ChatMarkdownColors,
    fontSize: TextUnit,
    codeActions: (@Composable RowScope.(Block.Code) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBackground)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                block.language.ifEmpty { stringResource(R.string.ime_ai_chat_code_label) },
                color = colors.dim,
                fontSize = fontSize * 0.82f,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // Nothing to act on until the block has closed: half a function
            // is not something to paste.
            if (codeActions != null && block.closed) codeActions(block)
        }
        // Code does not wrap, so a long line scrolls rather than breaking where
        // the language never would.
        Text(
            block.code,
            color = colors.text,
            fontSize = fontSize * 0.92f,
            fontFamily = FontFamily.Monospace,
            lineHeight = fontSize * 1.3f,
            softWrap = false,
            modifier = Modifier
                .padding(top = 3.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

private fun annotated(inline: AiMarkdownBlocks.Inline, colors: ChatMarkdownColors): AnnotatedString =
    buildAnnotatedString {
        append(inline.text)
        for (span in inline.spans) {
            val style = when (span.style) {
                AiMarkdownBlocks.Style.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
                AiMarkdownBlocks.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                AiMarkdownBlocks.Style.CODE -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = colors.codeBackground,
                )
            }
            addStyle(style, span.start, span.end)
        }
    }

private const val LINE_HEIGHT = 1.38f
