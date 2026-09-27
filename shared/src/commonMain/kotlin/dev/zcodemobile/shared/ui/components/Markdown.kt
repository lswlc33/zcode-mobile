package dev.zcodemobile.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownTypography
import dev.zcodemobile.shared.ui.theme.LocalZcTokens
import dev.zcodemobile.shared.ui.theme.MonoFamily
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

/**
 * Renders assistant Markdown.
 *
 * Two things happen here that the underlying renderer does not do correctly on
 * its own:
 *
 *  1. **Tables are extracted and drawn by us.** Version 0.27 renders a GFM
 *     table as loose paragraphs and emits the grid twice, so the table body is
 *     split out before handing the remainder to the renderer. This also lets
 *     wide tables scroll horizontally, which long shell/config tables need.
 *  2. **The flavour must be GFM** — the default CommonMark has no table
 *     extension at all.
 *
 * Code fences keep syntax highlighting via the renderer's `-code` module.
 */
@Composable
fun ZcMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    colors: MarkdownColors = zcMarkdownColors(),
    typography: MarkdownTypography = zcMarkdownTypography(),
) {
    val blocks = remember(content) { splitTables(content) }

    Column(modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Text -> if (block.markdown.isNotBlank()) {
                    Markdown(
                        content = block.markdown,
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.fillMaxWidth(),
                        flavour = GFMFlavourDescriptor(),
                        components = markdownComponents(
                            codeBlock = highlightedCodeBlock,
                            codeFence = highlightedCodeFence,
                        ),
                    )
                }

                is MdBlock.Table -> Table(block)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GFM table extraction
// ─────────────────────────────────────────────────────────────────────────────

internal sealed interface MdBlock {
    data class Text(val markdown: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

/**
 * Split a document into prose and table blocks.
 *
 * A table starts at a `|`-leading line whose successor is a delimiter row
 * (`|---|:--:|`), and runs while lines keep starting with `|`.
 */
internal fun splitTables(markdown: String): List<MdBlock> {
    val lines = markdown.lines()
    val out = mutableListOf<MdBlock>()
    val buffer = StringBuilder()

    fun flushText() {
        if (buffer.isNotBlank()) out += MdBlock.Text(buffer.toString().trimEnd())
        buffer.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)
        if (isTableRow(line) && next != null && isDelimiterRow(next)) {
            flushText()

            val header = splitRow(line)
            i += 2 // consume header + delimiter
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && isTableRow(lines[i])) {
                rows += splitRow(lines[i])
                i++
            }
            out += MdBlock.Table(header, rows)
        } else {
            buffer.appendLine(line)
            i++
        }
    }
    flushText()
    return out
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.length >= 2 && t.startsWith("|") && t.endsWith("|")
}

/** `|---|:--:|` — at least one dash per column, optional alignment colons. */
private fun isDelimiterRow(line: String): Boolean {
    if (!isTableRow(line)) return false
    val cells = splitRow(line)
    return cells.isNotEmpty() && cells.all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.count { it == '-' } >= 1
    }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

// ─────────────────────────────────────────────────────────────────────────────
// Table rendering
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Uniform column width, with the whole grid scrolling horizontally.
 *
 * Content-sized columns would read better for narrow values, but they interact
 * badly with the row-height intrinsic used to draw full-height cell dividers —
 * columns came out narrow enough to break monospace identifiers mid-word.
 * Fixed width plus a scroll axis is also what desktop clients do with tables.
 */
private val COLUMN_WIDTH = 168.dp

@Composable
private fun Table(table: MdBlock.Table) {
    val tokens = LocalZcTokens.current
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val columns = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
        1,
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(tokens.codeBackground, RoundedCornerShape(18.dp))
            .border(1.dp, outline.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            // Wide tables scroll rather than wrapping every cell into a column
            // of single characters.
            .horizontalScroll(rememberScrollState())
            .heightIn(max = 420.dp),
    ) {
        Column {
            HeaderRow(table.header, columns, outline)
            table.rows.forEach { row ->
                Divider(outline)
                BodyRow(row, columns, outline)
            }
        }
    }
}

@Composable
private fun HeaderRow(cells: List<String>, columns: Int, outline: Color) {
    // No vertical dividers and no IntrinsicSize: intrinsic measurement ignores
    // the cell's explicit width, which broke monospace identifiers mid-word.
    // Horizontal rules alone still read as a grid.
    Row {
        repeat(columns) { c ->
            Cell(
                text = cells.getOrNull(c).orEmpty(),
                weight = FontWeight.SemiBold,
                mono = false,
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            )
        }
    }
}

@Composable
private fun BodyRow(cells: List<String>, columns: Int, outline: Color) {
    Row {
        repeat(columns) { c ->
            val raw = cells.getOrNull(c).orEmpty()
            Cell(text = stripInline(raw), weight = FontWeight.Normal, mono = isCode(raw))
        }
    }
}

@Composable
private fun Cell(
    text: String,
    weight: FontWeight,
    mono: Boolean,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontWeight = weight,
            fontSize = if (mono) 11.5.sp else 12.5.sp,
            lineHeight = 17.sp,
            fontFamily = if (mono) MonoFamily else FontFamily.Default,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .width(COLUMN_WIDTH)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun Divider(color: Color) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

private fun isCode(cell: String): Boolean =
    cell.length >= 2 && cell.startsWith("`") && cell.endsWith("`")

/** Drop the inline markers a table cell commonly carries. */
private fun stripInline(cell: String): String =
    cell.removePrefix("`").removeSuffix("`")
        .replace("**", "")
        .replace("~~", "")

// ─────────────────────────────────────────────────────────────────────────────
// Tokens
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun zcMarkdownColors(): MarkdownColors {
    val tokens = LocalZcTokens.current
    val scheme = MaterialTheme.colorScheme
    return markdownColor(
        text = scheme.onSurface,
        codeText = scheme.onSurface,
        codeBackground = tokens.codeBackground,
        inlineCodeText = scheme.onSurface,
        inlineCodeBackground = tokens.chipBackground,
        dividerColor = scheme.outline,
    )
}

@Composable
fun zcMarkdownTypography(scale: Float = 1f): MarkdownTypography {
    val base = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    fun fs(sp: Float) = (sp * scale).sp
    fun body() = base.bodyMedium.copy(fontSize = fs(15f), lineHeight = (23f * scale).sp)
    // Headings must be visibly larger than body text: with the Material
    // defaults an h2 is 16sp against 15sp body, which reads as bold-only and
    // makes a long answer's structure invisible.
    return markdownTypography(
        h1 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(22f), lineHeight = (29f * scale).sp,
            fontWeight = FontWeight.Bold, color = scheme.onSurface,
        ),
        h2 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(19f), lineHeight = (26f * scale).sp,
            fontWeight = FontWeight.Bold, color = scheme.onSurface,
        ),
        h3 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(17f), lineHeight = (23f * scale).sp,
            fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
        ),
        h4 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(15.5f), lineHeight = (22f * scale).sp,
            fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
        ),
        h5 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(15f), lineHeight = (21f * scale).sp,
            fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
        ),
        h6 = androidx.compose.ui.text.TextStyle(
            fontSize = fs(15f), lineHeight = (21f * scale).sp,
            fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant,
        ),
        text = body(),
        code = base.bodySmall.copy(fontFamily = MonoFamily, fontSize = fs(12.5f)),
        inlineCode = base.bodyMedium.copy(fontFamily = MonoFamily, fontSize = fs(13.5f)),
        quote = body().copy(color = scheme.onSurfaceVariant),
        paragraph = body(),
        ordered = body(),
        bullet = body(),
        list = body(),
    )
}
