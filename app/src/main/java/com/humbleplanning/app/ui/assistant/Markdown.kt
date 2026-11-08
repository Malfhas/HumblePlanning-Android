package com.humbleplanning.app.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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

/** A paragraph or a list item of an answer. */
internal data class MarkdownBlock(val text: String, val bullet: Boolean = false, val heading: Boolean = false)

private val BULLET = Regex("^\\s*[-*•]\\s+(.*)$")
private val HEADING = Regex("^#{1,6}\\s+(.*)$")

/** Paragraphs (separated by a blank line), bullets, headings shown as bold paragraphs. */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += MarkdownBlock(paragraph.toString())
        paragraph.clear()
    }
    text.lineSequence().forEach { line ->
        val bullet = BULLET.matchEntire(line)
        val heading = HEADING.matchEntire(line.trim())
        when {
            line.isBlank() -> flush()
            bullet != null -> {
                flush()
                blocks += MarkdownBlock(bullet.groupValues[1], bullet = true)
            }
            heading != null -> {
                flush()
                blocks += MarkdownBlock(heading.groupValues[1], heading = true)
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
            }
        }
    }
    flush()
    return blocks
}

/** `**bold**` and `` `code` ``; a marker still open (answer being written) stays as plain text. */
internal fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val end = when {
            text.startsWith("**", i) -> text.indexOf("**", i + 2).takeIf { it > i + 2 }
            text[i] == '`' -> text.indexOf('`', i + 1).takeIf { it > i + 1 }
            else -> null
        }
        when {
            end != null && text[i] == '*' -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            end != null -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> append(text[i++])
        }
    }
}

/** Light rendering of the answers, no library: bold, inline code, bullet lists, paragraphs. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { markdownBlocks(text) }
    val codeBackground = color.copy(alpha = 0.10f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            val inline = inlineMarkdown(block.text, codeBackground)
            when {
                block.bullet -> Row {
                    Text("•", style = MaterialTheme.typography.bodyMedium, color = color)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(inline, style = MaterialTheme.typography.bodyMedium, color = color)
                }
                block.heading -> Text(inline, style = MaterialTheme.typography.titleSmall, color = color)
                else -> Text(inline, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}
