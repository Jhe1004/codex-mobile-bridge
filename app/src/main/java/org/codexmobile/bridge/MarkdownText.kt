package org.codexmobile.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val depth: Int, val text: String) : MarkdownBlock
    data class Numbered(val depth: Int, val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val code: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val headingPattern = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$")
private val bulletPattern = Regex("^(\\s*)[-+*]\\s+(.+)$")
private val numberedPattern = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
private val quotePattern = Regex("^\\s{0,3}>\\s?(.*)$")
private val fencePattern = Regex("^\\s*(`{3,}|~{3,})\\s*([A-Za-z0-9_+.-]*)\\s*$")
private val rulePattern = Regex("^\\s{0,3}((?:-\\s*){3,}|(?:\\*\\s*){3,}|(?:_\\s*){3,})$")
private val tableDividerPattern = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")

internal fun parseMarkdown(raw: String): List<MarkdownBlock> {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }

        val fence = fencePattern.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2]
            val code = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith(marker.take(3))) {
                code += lines[index]
                index += 1
            }
            if (index < lines.size) index += 1
            blocks += MarkdownBlock.Code(language, code.joinToString("\n"))
            continue
        }

        if (line.contains('|') && index + 1 < lines.size && tableDividerPattern.matches(lines[index + 1])) {
            val headers = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                rows += splitTableRow(lines[index])
                index += 1
            }
            blocks += MarkdownBlock.Table(headers, rows)
            continue
        }

        val heading = headingPattern.matchEntire(line)
        if (heading != null) {
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index += 1
            continue
        }

        if (rulePattern.matches(line)) {
            blocks += MarkdownBlock.Rule
            index += 1
            continue
        }

        val firstQuote = quotePattern.matchEntire(line)
        if (firstQuote != null) {
            val quoteLines = mutableListOf(firstQuote.groupValues[1])
            index += 1
            while (index < lines.size) {
                val next = quotePattern.matchEntire(lines[index]) ?: break
                quoteLines += next.groupValues[1]
                index += 1
            }
            blocks += MarkdownBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        val bullet = bulletPattern.matchEntire(line)
        if (bullet != null) {
            blocks += MarkdownBlock.Bullet(bullet.groupValues[1].length / 2, bullet.groupValues[2])
            index += 1
            continue
        }

        val numbered = numberedPattern.matchEntire(line)
        if (numbered != null) {
            blocks += MarkdownBlock.Numbered(numbered.groupValues[1].length / 2, numbered.groupValues[2], numbered.groupValues[3])
            index += 1
            continue
        }

        val paragraph = mutableListOf(line.trim())
        index += 1
        while (index < lines.size && lines[index].isNotBlank() && !startsMarkdownBlock(lines, index)) {
            paragraph += lines[index].trim()
            index += 1
        }
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
    }
    return blocks
}

private fun startsMarkdownBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return fencePattern.matches(line) ||
        headingPattern.matches(line) ||
        bulletPattern.matches(line) ||
        numberedPattern.matches(line) ||
        quotePattern.matches(line) ||
        rulePattern.matches(line) ||
        (line.contains('|') && index + 1 < lines.size && tableDividerPattern.matches(lines[index + 1]))
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)

private val inlinePattern = Regex(
    "!?\\[[^]\\n]+]\\((?:<[^>\\n]+>|[^)\\n]+)\\)|`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|~~[^~\\n]+~~|\\*[^*\\n]+\\*|(?<!\\w)_[^_\\n]+_(?!\\w)|https?://[^\\s<>]+"
)
private val markdownLinkPattern = Regex("^(!?)\\[([^]]+)]\\((<[^>]+>|[^)]+)\\)$")

internal fun inlineMarkdown(raw: String, textColor: Color, linkColor: Color, codeSurface: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var cursor = 0
    inlinePattern.findAll(raw).forEach { match ->
        builder.append(raw.substring(cursor, match.range.first))
        val token = match.value
        val link = markdownLinkPattern.matchEntire(token)
        when {
            link != null -> {
                val imagePrefix = if (link.groupValues[1] == "!") "🖼 " else ""
                val label = imagePrefix + link.groupValues[2]
                val target = link.groupValues[3].trim().removeSurrounding("<", ">")
                builder.pushStringAnnotation("LINK", target)
                builder.pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
                builder.append(label)
                builder.pop()
                builder.pop()
            }
            token.startsWith("`") -> styled(builder, token.drop(1).dropLast(1), SpanStyle(color = textColor, background = codeSurface, fontFamily = FontFamily.Monospace))
            token.startsWith("**") -> styled(builder, token.drop(2).dropLast(2), SpanStyle(fontWeight = FontWeight.Bold))
            token.startsWith("__") -> styled(builder, token.drop(2).dropLast(2), SpanStyle(fontWeight = FontWeight.Bold))
            token.startsWith("~~") -> styled(builder, token.drop(2).dropLast(2), SpanStyle(textDecoration = TextDecoration.LineThrough))
            token.startsWith("*") || token.startsWith("_") -> styled(builder, token.drop(1).dropLast(1), SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            token.startsWith("http://") || token.startsWith("https://") -> {
                val target = token.trimEnd('.', ',', ';', ':', '。', '，', '；', '：', '！', '？')
                builder.pushStringAnnotation("LINK", target)
                builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                builder.append(target)
                builder.pop()
                builder.pop()
                builder.append(token.drop(target.length))
            }
            else -> builder.append(token)
        }
        cursor = match.range.last + 1
    }
    builder.append(raw.substring(cursor))
    return builder.toAnnotatedString()
}

private fun styled(builder: AnnotatedString.Builder, text: String, style: SpanStyle) {
    builder.pushStyle(style)
    builder.append(text)
    builder.pop()
}

@Composable
internal fun MarkdownText(
    text: String,
    color: Color,
    codeSurface: Color,
    quoteSurface: Color,
    onLink: (String) -> Unit,
) {
    val linkColor = if (color.luminance() > 0.5f) Color(0xFF8CCBFF) else Color(0xFF176B87)
    val blocks = remember(text) { parseMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> InlineText(
                    block.text,
                    color,
                    linkColor,
                    codeSurface,
                    MaterialTheme.typography.titleLarge.copy(
                        color = color,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            else -> 16.sp
                        },
                        fontWeight = FontWeight.Bold,
                    ),
                    onLink,
                )
                is MarkdownBlock.Paragraph -> InlineText(block.text, color, linkColor, codeSurface, MaterialTheme.typography.bodyLarge.copy(color = color), onLink)
                is MarkdownBlock.Bullet -> ListLine("•", block.depth, block.text, color, linkColor, codeSurface, onLink)
                is MarkdownBlock.Numbered -> ListLine("${block.number}.", block.depth, block.text, color, linkColor, codeSurface, onLink)
                is MarkdownBlock.Quote -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(quoteSurface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    InlineText(block.text, color, linkColor, codeSurface, MaterialTheme.typography.bodyMedium.copy(color = color), onLink)
                }
                is MarkdownBlock.Code -> CodeBlock(block, color, codeSurface)
                is MarkdownBlock.Table -> MarkdownTable(block, color, linkColor, codeSurface, onLink)
                MarkdownBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.22f))
            }
        }
    }
}

@Composable
private fun ListLine(
    marker: String,
    depth: Int,
    text: String,
    color: Color,
    linkColor: Color,
    codeSurface: Color,
    onLink: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width((depth * 14).dp))
        Text(marker, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        Box(Modifier.weight(1f)) {
            InlineText(text, color, linkColor, codeSurface, MaterialTheme.typography.bodyLarge.copy(color = color), onLink)
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code, color: Color, codeSurface: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(codeSurface, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        if (block.language.isNotBlank()) {
            Text(block.language, color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = block.code,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    color: Color,
    linkColor: Color,
    codeSurface: Color,
    onLink: (String) -> Unit,
) {
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull(List<String>::size) ?: 0)
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(6.dp)),
    ) {
        TableRow(table.headers, columnCount, color, linkColor, codeSurface, true, onLink)
        table.rows.forEach { TableRow(it, columnCount, color, linkColor, codeSurface, false, onLink) }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    count: Int,
    color: Color,
    linkColor: Color,
    codeSurface: Color,
    header: Boolean,
    onLink: (String) -> Unit,
) {
    Row(Modifier.background(if (header) codeSurface else Color.Transparent)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .width(150.dp)
                    .border(0.5.dp, color.copy(alpha = 0.14f))
                    .padding(8.dp),
            ) {
                InlineText(
                    cells.getOrElse(index) { "" },
                    color,
                    linkColor,
                    codeSurface,
                    MaterialTheme.typography.bodySmall.copy(color = color, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal),
                    onLink,
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun InlineText(
    text: String,
    color: Color,
    linkColor: Color,
    codeSurface: Color,
    style: TextStyle,
    onLink: (String) -> Unit,
) {
    val annotated = remember(text, color, linkColor, codeSurface) {
        inlineMarkdown(text, color, linkColor, codeSurface)
    }
    ClickableText(
        text = annotated,
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations("LINK", offset, offset).firstOrNull()?.let { onLink(it.item) }
        },
    )
}
