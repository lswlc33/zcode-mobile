package dev.zcodemobile.shared.ui.conversation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape


















import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zcodemobile.shared.ui.components.ZcMarkdown
import dev.zcodemobile.shared.ui.components.ZcAlertDialog
import dev.zcodemobile.shared.ui.components.ZcDropdownMenu
import dev.zcodemobile.shared.ui.components.zcMarkdownTypography
import dev.zcodemobile.shared.ui.theme.Dims
import dev.zcodemobile.shared.ui.theme.LocalZcMsgScale
import dev.zcodemobile.shared.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.FileChange
import dev.zcodemobile.protocol.Row
import dev.zcodemobile.protocol.fileChangeOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.zcodemobile.shared.ui.ZcIcons

/**
 * Renderers for the v4 row kinds.
 *
 * The kinds and their fields come from the live wire (`conversationRowSchema`):
 * `turnHeader`, `userInput`, `assistantText`, `reasoning`, `toolCall`. Text
 * kinds carry Markdown plus a `state` (`streaming` while the model is still
 * emitting); `toolCall` carries `toolName`/`status`/`input`/`output`.
 */
enum class RowStyle { User, Assistant, Reasoning, Tool, TurnHeader }

@Composable
fun ConversationRow(
    row: Row,
    /** Actions render only on the transcript's last assistant row. */
    showActions: Boolean = false,
    /**
     * True while this row's turn is actively streaming: reasoning shows a
     * live ticker of its newest line so the reader can tell output is
     * happening without unfolding anything.
     */
    live: Boolean = false,
    onCopy: (String) -> Unit = {},
    onAction: (RowAction) -> Unit = {},
) {
    when (row.kind) {
        "turnHeader" -> TurnHeaderRow(row)
        "userInput" -> UserInputRow(row, onAction)
        "assistantText" -> AssistantRow(row, live, showActions, onAction)
        "reasoning" -> ReasoningRow(row, live)
        "toolCall" -> ToolCallRow(row, live)
        else -> FallbackRow(row)
    }
}

/** Message text style, scaled by the reader's 字号 choice. */
@Composable
private fun msgStyle(style: TextStyle): TextStyle {
    val scale = LocalZcMsgScale.current
    return if (scale == 1f) style
    else style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
}

/**
 * Overflow menu for the actions the **host** declared on this row.
 *
 * Driving the menu off `row.actions` rather than the row's kind means we never
 * offer a command the host will refuse: `canEdit`/`canRetry`/`canFork`/
 * `canRewindFiles` are exactly the row-targeting capabilities, and a streaming
 * row simply has none yet.
 */
@Composable
private fun RowActionMenu(
    row: Row,
    onAction: (RowAction) -> Unit,
    onCopy: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    var open by remember { mutableStateOf(false) }

    Box {
        ActionIcon(ZcIcons.Default.MoreHoriz, "更多操作") { open = true }

        ZcDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(RowActionLabels.label(RowAction.Copy)) },
                leadingIcon = { Icon(ZcIcons.Default.ContentCopy, null) },
                onClick = { open = false; onCopy() },
            )

            if (row.actions.canEdit) {
                DropdownMenuItem(
                    text = { Text(RowActionLabels.label(RowAction.Edit)) },
                    leadingIcon = { Icon(ZcIcons.Default.Edit, null) },
                    onClick = { open = false; onAction(RowAction.Edit) },
                )
                // Only offered when the turn actually changed files; the host
                // declares that separately from editability.
                if (row.actions.canRewindFiles) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(RowActionLabels.label(RowAction.EditAndRewind))
                                Text(
                                    RowActionLabels.describe(RowAction.EditAndRewind).orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.secondaryText,
                                )
                            }
                        },
                        leadingIcon = { Icon(ZcIcons.Default.History, null) },
                        onClick = { open = false; onAction(RowAction.EditAndRewind) },
                    )
                }
            }

            if (row.actions.canRetry) {
                DropdownMenuItem(
                    text = { Text(RowActionLabels.label(RowAction.Retry)) },
                    leadingIcon = { Icon(ZcIcons.Default.Refresh, null) },
                    onClick = { open = false; onAction(RowAction.Retry) },
                )
            }

            if (row.actions.canFork) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(RowActionLabels.label(RowAction.Fork))
                            Text(
                                RowActionLabels.describe(RowAction.Fork).orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.secondaryText,
                            )
                        }
                    },
                    leadingIcon = { Icon(ZcIcons.Default.CallSplit, null) },
                    onClick = { open = false; onAction(RowAction.Fork) },
                )
            }

            if (row.actions.canRewindFiles && !row.actions.canEdit) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(RowActionLabels.label(RowAction.RewindFiles))
                            Text(
                                RowActionLabels.describe(RowAction.RewindFiles).orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.secondaryText,
                            )
                        }
                    },
                    leadingIcon = { Icon(ZcIcons.Default.History, null) },
                    onClick = { open = false; onAction(RowAction.RewindFiles) },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// turnHeader — a turn boundary. Most turns are noise on a phone, so this is a
// thin timestamp rule rather than a card.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TurnHeaderRow(row: Row) {
    val tokens = LocalZcTokens.current
    val elapsed = turnDurationOf(row)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dims.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTime(row.createdAt),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.secondaryText,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        )
        // Wall-clock time of the whole turn: from the message being sent to
        // the answer finishing (`turnHeader.startedAt/endedAt` from the host).
        if (elapsed != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "用时 $elapsed",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
        }
    }
}

/** `turnHeader` fields: how long the turn ran, formatted for humans. */
private fun turnDurationOf(row: Row): String? {
    fun num(key: String): Long? = (row.raw[key] as? Number)?.toLong()
    val ms = num("activeMs") ?: run {
        val start = num("startedAt") ?: return null
        val end = num("endedAt") ?: return null
        end - start
    }
    if (ms <= 0) return null
    val totalSec = ms / 1000
    return when {
        totalSec < 60 -> String.format("%.1f 秒", ms / 1000.0)
        totalSec < 3600 -> "${totalSec / 60} 分 ${totalSec % 60} 秒"
        else -> "${totalSec / 3600} 时 ${(totalSec % 3600) / 60} 分"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// userInput — author is the reader, so it is right-aligned and de-emphasised.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UserInputRow(row: Row, onAction: (RowAction) -> Unit) {
    val tokens = LocalZcTokens.current
    val text = row.text ?: return
    val clipboard = LocalClipboardManager.current

    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dims.screenPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(tokens.userBubble, RoundedCornerShape(22.dp))
                .clickable { clipboard.setText(AnnotatedString(text)) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // User text is plain: it is typed, not model-authored Markdown.
            Text(text, style = msgStyle(MaterialTheme.typography.bodyLarge))
        }

        // Only a row the host flagged editable gets a menu at all; the
        // synthetic rows (background results, goal continuations) do not.
        if (row.actions.canEdit || row.actions.canRewindFiles) {
            Spacer(Modifier.width(2.dp))
            RowActionMenu(row, onAction) { clipboard.setText(AnnotatedString(text)) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// assistantText — the model's answer. Markdown, plus a footer of actions.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AssistantRow(
    row: Row,
    live: Boolean,
    showActions: Boolean,
    onAction: (RowAction) -> Unit,
) {
    val text = row.text.orEmpty()
    // A row the host left marked "streaming" only still streams while its turn
    // is actually running: an interrupted turn flips canStop but may not emit a
    // terminal update for the row it was mid-way through, and trusting the
    // row's own state here would leave a perpetual "生成中…" (or an empty
    // ghost) on a finished conversation.
    val streaming = row.state == "streaming" && live
    if (text.isEmpty() && !streaming) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPadding, vertical = 8.dp)
            .animateContentSize(),
    ) {
        ZcMarkdown(
            content = text,
            typography = zcMarkdownTypography(LocalZcMsgScale.current),
        )

        if (streaming) {
            Spacer(Modifier.height(8.dp))
            StreamingIndicator()
        } else if (showActions) {
            // Copy / votes / menu belong only at the very end of the answer —
            // the caller flags the transcript's last assistant row.
            Spacer(Modifier.height(6.dp))
            MessageActions(row, text, onAction)
        }
    }
}

@Composable
private fun StreamingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            Modifier.size(12.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "生成中…",
            style = MaterialTheme.typography.labelSmall,
            color = LocalZcTokens.current.secondaryText,
        )
    }
}

/**
 * Row footer: copy, real feedback, and the action menu.
 *
 * The vote reflects `row.feedback` from the projection rather than local state,
 * so the control agrees with the session after a resync or after another device
 * rated the same message. Tapping the active vote clears it.
 */
@Composable
private fun MessageActions(row: Row, text: String, onAction: (RowAction) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val tokens = LocalZcTokens.current
    val liked = row.feedback == "like"
    val disliked = row.feedback == "dislike"

    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(ZcIcons.Default.ContentCopy, "复制") {
            clipboard.setText(AnnotatedString(text))
        }
        Spacer(Modifier.width(4.dp))
        ActionIcon(
            ZcIcons.Default.ThumbUp, "有帮助",
            tint = if (liked) MaterialTheme.colorScheme.primary else tokens.secondaryText,
        ) {
            onAction(if (liked) RowAction.ClearFeedback else RowAction.Like)
        }
        Spacer(Modifier.width(4.dp))
        ActionIcon(
            ZcIcons.Default.ThumbDown, "没帮助",
            tint = if (disliked) MaterialTheme.colorScheme.primary else tokens.secondaryText,
        ) {
            onAction(if (disliked) RowAction.ClearFeedback else RowAction.Dislike)
        }

        if (row.actions.canRetry || row.actions.canFork) {
            Spacer(Modifier.width(4.dp))
            RowActionMenu(row, onAction) { clipboard.setText(AnnotatedString(text)) }
        }
    }
}

@Composable
private fun ActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint ?: tokens.secondaryText,
        modifier = Modifier
            .size(Dims.iconButton)
            .clickable(onClick = onClick)
            .padding(7.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// reasoning — the model's working notes. Collapsed by default: it is valuable
// when auditing a decision and noise when just reading the answer.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReasoningRow(row: Row, live: Boolean) {
    val tokens = LocalZcTokens.current
    val text = row.text.orEmpty()
    if (text.isEmpty()) return
    // Collapsed is the resting state; the reader can unfold the full text.
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPadding, vertical = 4.dp)
            .background(tokens.chipBackground, RoundedCornerShape(Dims.cardRadius))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                ZcIcons.Default.Lightbulb,
                contentDescription = null,
                tint = tokens.secondaryText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            if (expanded) {
                Text(
                    "思考过程",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    modifier = Modifier.weight(1f),
                )
            } else if (live) {
                // Streaming: show the newest thinking line so it reads as
                // activity, not a silent placeholder.
                val latest = text.lineSequence().lastOrNull { it.isNotBlank() } ?: ""
                Text(
                    "思考中：$latest",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "思考过程 · ${text.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    modifier = Modifier.weight(1f),
                )
            }
            if (live && !expanded) {
                CircularProgressIndicator(
                    Modifier.size(10.dp), strokeWidth = 1.5.dp, color = tokens.secondaryText,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) ZcIcons.Default.ExpandLess else ZcIcons.Default.ExpandMore,
                contentDescription = null,
                tint = tokens.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = msgStyle(MaterialTheme.typography.bodySmall),
                color = tokens.secondaryText,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// toolCall — a card: what ran, whether it succeeded, and its I/O on demand.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolCallRow(row: Row, live: Boolean) {
    val tokens = LocalZcTokens.current
    var expanded by remember { mutableStateOf(false) }
    var fullOutput by remember { mutableStateOf(false) }
    val change = remember(row) { fileChangeOf(row) }

    // A tool the host left "running" only still runs while its turn does; an
    // interrupted turn flips canStop without necessarily emitting a terminal
    // status for the tool, so a finished turn must not keep spinning "运行中".
    val running = row.status == "running" && live
    val interrupted = row.status == "running" && !live

    val statusTint = when {
        running -> MaterialTheme.colorScheme.primary
        interrupted -> tokens.secondaryText
        row.status == "success" -> tokens.success
        row.status == "error" || row.status == "failed" -> tokens.danger
        else -> tokens.secondaryText
    }
    val statusIcon = when (row.status) {
        "success" -> ZcIcons.Default.Check
        "error", "failed" -> ZcIcons.Default.ErrorOutline
        else -> null
    }
    val statusLabel = if (interrupted) "已中断" else shortStatus(row.status)

    if (fullOutput) {
        OutputViewer(
            title = row.toolName ?: "输出",
            text = row.outputText.orEmpty(),
            onDismiss = { fullOutput = false },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dims.screenPadding, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Dims.cardRadius))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                ZcIcons.Default.Build, contentDescription = null,
                tint = statusTint, modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                row.toolName ?: "工具",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                running -> CircularProgressIndicator(
                    Modifier.size(12.dp), strokeWidth = 1.5.dp, color = statusTint,
                )
                statusIcon != null -> Icon(
                    statusIcon, contentDescription = row.status,
                    tint = statusTint, modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = statusTint,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) ZcIcons.Default.ExpandLess else ZcIcons.Default.ExpandMore,
                contentDescription = null,
                tint = tokens.secondaryText,
                modifier = Modifier.size(16.dp),
            )
        }

        // A write tool gets a diff summary chip, the way a change card reads.
        change?.let {
            Spacer(Modifier.height(8.dp))
            DiffChip(it)
        }

        // A one-line preview keeps the collapsed card informative.
        if (!expanded) {
            val preview = toolPreview(row)
            if (preview.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    preview,
                    style = msgStyle(MaterialTheme.typography.bodySmall).copy(fontFamily = FontFamily.Monospace),
                    color = tokens.secondaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            val input = row.inputText?.takeIf { it.isNotBlank() }
            if (input != null) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("输入")
                CodeSurface(input)
            }
            val output = row.outputText?.takeIf { it.isNotBlank() }
            if (output != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("输出")
                    Spacer(Modifier.weight(1f))
                    // Long tool output is unreadable in a card; open it full-bleed.
                    if (output.length > 600 || output.count { it == '\n' } > 12) {
                        Text(
                            "查看完整输出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { fullOutput = true }
                                .padding(4.dp),
                        )
                    }
                }
                CodeSurface(output, maxLines = 24)
            }
            durationOf(row)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "耗时 $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                )
            }
        }
    }
}

/** `nginx.conf  +12 -3` — additions green, deletions red. */
@Composable
private fun DiffChip(change: FileChange) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tokens.codeBackground,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                ZcIcons.Default.Description, contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tokens.secondaryText,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                change.fileName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            if (change.additions > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "+${change.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.success,
                )
            }
            if (change.deletions > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "-${change.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.danger,
                )
            }
        }
    }
}

/** Full-screen, scrollable tool output. */
@Composable
private fun OutputViewer(title: String, text: String, onDismiss: () -> Unit) {
    val tokens = LocalZcTokens.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.85f),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(ZcIcons.Default.Close, "关闭")
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxSize().padding(12.dp),
                ) {
                    item {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalZcTokens.current.secondaryText,
    )
    Spacer(Modifier.height(4.dp))
}

/** Horizontal scroll keeps long commands readable instead of wrapping badly. */
@Composable
private fun CodeSurface(text: String, maxLines: Int = Int.MAX_VALUE) {
    val tokens = LocalZcTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(tokens.codeBackground, RoundedCornerShape(16.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FallbackRow(row: Row) {
    val tokens = LocalZcTokens.current
    Text(
        row.text ?: "[${row.kind}]",
        style = msgStyle(MaterialTheme.typography.bodySmall),
        color = tokens.secondaryText,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dims.screenPadding, vertical = 6.dp),
    )
}

/**
 * A finished turn's whole process — thinking, tool calls, intermediate
 * messages — folded into one bar. The answer stays outside this block; the
 * reader unfolds the process on demand.
 */
@Composable
fun ProcessGroupCard(
    rows: List<Row>,
    onRowAction: (Row, RowAction) -> Unit,
) {
    val tokens = LocalZcTokens.current
    var expanded by remember(rows.firstOrNull()?.rowId) { mutableStateOf(false) }
    val thinking = rows.count { it.kind == "reasoning" }
    val tools = rows.count { it.kind == "toolCall" }
    val detail = buildList {
        if (thinking > 0) add("思考 $thinking")
        if (tools > 0) add("工具 $tools")
        val other = rows.size - thinking - tools
        if (other > 0) add("消息 $other")
    }.joinToString(" · ")

    Column(Modifier.fillMaxWidth().padding(horizontal = Dims.screenPadding, vertical = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(Dims.cardRadius),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    ZcIcons.Default.PlayArrow, contentDescription = null,
                    tint = tokens.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (expanded) "收起过程" else "展开过程",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) ZcIcons.Default.ExpandLess else ZcIcons.Default.ExpandMore,
                    contentDescription = null,
                    tint = tokens.secondaryText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (expanded) {
            rows.forEach { row ->
                ConversationRow(row = row, live = false, onAction = { action ->
                    val entity = row.entityId
                    if (!entity.isNullOrBlank()) onRowAction(row, action)
                })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun shortStatus(status: String?): String = when (status) {
    "success" -> "完成"
    "running" -> "运行中"
    "error", "failed" -> "失败"
    null -> ""
    else -> status
}

/** First meaningful line of the tool input, for the collapsed card. */
private fun toolPreview(row: Row): String {
    val command = row.raw["input"]?.let { input ->
        val m = input as? Map<*, *> ?: return@let null
        (m["command"] ?: m["file_path"] ?: m["path"] ?: m["pattern"] ?: m["description"]) as? String
    }
    val source = command ?: row.inputText.orEmpty()
    return source.lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(160)
        ?.let(::wrappable)
        .orEmpty()
}

/**
 * A Windows/POSIX path is one unbreakable word to the line breaker, and in
 * `E:\dir\file` the only candidate break lands after the drive colon — the
 * card then wraps as `E:` / `\dir\file`. Zero-width spaces after the
 * separators give it real opportunities at directory boundaries instead.
 * Values containing spaces already break naturally and are left alone.
 */
private fun wrappable(s: String): String =
    if (s.contains(' ')) s
    else s.replace("\\", "\\\u200b").replace("/", "/\u200b")

private fun durationOf(row: Row): String? {
    fun num(key: String): Long? = (row.raw[key] as? Number)?.toLong()
    val start = num("startedAt") ?: return null
    val end = num("endedAt") ?: return null
    val ms = end - start
    if (ms <= 0) return null
    return if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatTime(millis: Long?): String =
    millis?.let { timeFormat.format(Date(it)) }.orEmpty()
