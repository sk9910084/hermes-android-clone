package com.hermes.clone.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface MdBlock {
    data class Paragraph(val lines: List<String>) : MdBlock
    data class Code(val text: String) : MdBlock
}

/**
 * A small Markdown renderer for chat: paragraphs, "-" bullets, "#" headings, **bold**, `code`
 * and fenced code blocks. No images, links or tables (the system prompt asks the model to avoid them).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Code -> Surface(color = codeBackground, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = block.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = renderLines(block.lines, codeBackground),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = ArrayList<String>()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toList())
            paragraph.clear()
        }
    }

    for (line in text.replace("\r\n", "\n").split('\n')) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
                code.clear()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(line).append('\n')
        } else if (line.isBlank()) {
            flushParagraph()
        } else {
            paragraph += line
        }
    }
    // A fence that is still open (the reply is streaming) is shown as code so far.
    if (inCode) blocks += MdBlock.Code(code.toString().trimEnd('\n')) else flushParagraph()
    return blocks
}

private fun renderLines(lines: List<String>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { index, raw ->
        if (index > 0) append('\n')
        val trimmed = raw.trimStart()
        val indent = raw.length - trimmed.length
        when {
            trimmed.startsWith("#") -> {
                val heading = trimmed.trimStart('#').trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(heading, codeBackground) }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append(" ".repeat(indent.coerceAtMost(8)))
                append("\u2022 ")
                appendInline(trimmed.substring(2), codeBackground)
            }
            else -> appendInline(raw, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, codeBackground: Color) {
    var i = 0
    while (i < line.length) {
        if (line.startsWith("**", i)) {
            val end = line.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (line[i] == '`') {
            val end = line.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(line.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(line[i])
        i++
    }
}
