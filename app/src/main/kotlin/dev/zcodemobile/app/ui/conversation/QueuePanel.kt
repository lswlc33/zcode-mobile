package dev.zcodemobile.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.components.ZcAlertDialog
import dev.zcodemobile.app.ui.components.ZcDropdownMenu
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.BackgroundWork
import dev.zcodemobile.protocol.ConversationState
import dev.zcodemobile.protocol.GoalState
import dev.zcodemobile.protocol.QueueItem

/** Queue operations the panel can raise. */
enum class QueueAction { SendNow, Edit, MoveUp, MoveDown, Delete, ResumeDrain, PauseDrain }

/**
 * Session status strip: goal, queue and background work.
 *
 * Renders nothing when there is nothing to say, so a plain conversation keeps
 * its full height. Everything here is a *state* view — the actions are the
 * commands that change it, and each is gated on the snapshot's own
 * `availability` map where one exists.
 */
@Composable
fun SessionStatusPanel(
    conversation: ConversationState,
    onQueueAction: (QueueAction, String, String?) -> Unit,
    onGoalAction: (pause: Boolean) -> Unit,
    onCancelBackgroundWork: (String) -> Unit,
    onSetGoal: (String) -> Unit,
) {
    val goal = conversation.goal
    val queue = conversation.queue
    val works = conversation.backgroundWorks
    val hasAnything = goal != null || !queue.isEmpty || works.isNotEmpty()
    if (!hasAnything) return

    var expanded by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<QueueItem?>(null) }
    var goalDialog by remember { mutableStateOf(false) }

    // Drag-to-reorder by id: crossings dispatch a real reorder command and
    // tracking follows the neighbour, so the host's pushed order agrees.
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 46.dp.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    editing?.let { item ->
        EditQueueDialog(
            initial = item.text,
            onDismiss = { editing = null },
            onConfirm = { text ->
                onQueueAction(QueueAction.Edit, item.queueItemId, text)
                editing = null
            },
        )
    }

    if (goalDialog) {
        GoalDialog(
            onDismiss = { goalDialog = false },
            onConfirm = { text ->
                onSetGoal(text)
                goalDialog = false
            },
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Everything the panel has to say lives on one line: goal or
            // queue count, pause/resume, the goal link, and the fold toggle.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (goal != null) {
                    GoalChip(goal = goal, onGoalAction = onGoalAction)
                } else {
                    Icon(
                        Icons.Default.Flag, null,
                        modifier = Modifier.size(13.dp),
                        tint = LocalZcTokens.current.secondaryText,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (!queue.isEmpty) "队列 ${queue.items.size}" else "设定目标",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalZcTokens.current.secondaryText,
                        modifier = if (!queue.isEmpty) Modifier else Modifier.clickable { goalDialog = true },
                    )
                }

                if (!queue.isEmpty) {
                    if (!queue.autoDrain) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            QueueLabels.pauseReason(queue.pauseReason),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalZcTokens.current.warning,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (queue.autoDrain) "暂停排队" else "继续排队",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onQueueAction(
                                    if (queue.autoDrain) QueueAction.PauseDrain else QueueAction.ResumeDrain,
                                    "", null,
                                )
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    if (goal == null) {
                        Text(
                            "设定目标",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalZcTokens.current.secondaryText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { goalDialog = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        "展开",
                        modifier = Modifier.size(17.dp),
                        tint = LocalZcTokens.current.secondaryText,
                    )
                }
            }

            if (!expanded) return@Column

            works.forEach { w ->
                Spacer(Modifier.height(4.dp))
                BackgroundWorkRow(w, onCancelBackgroundWork)
            }

            queue.items.forEachIndexed { index, item ->
                Spacer(Modifier.height(2.dp))
                val dragging = draggingId == item.queueItemId
                QueueRow(
                    item = item,
                    dragging = dragging,
                    onSendNow = { onQueueAction(QueueAction.SendNow, item.queueItemId, null) },
                    onEdit = { editing = item },
                    onDelete = { onQueueAction(QueueAction.Delete, item.queueItemId, null) },
                    handleModifier = Modifier.pointerInput(item.queueItemId, queue.items.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingId = item.queueItemId
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (draggingId != item.queueItemId) return@detectDragGesturesAfterLongPress
                                dragOffset += amount.y
                                val i = queue.items.indexOfFirst { it.queueItemId == item.queueItemId }
                                if (dragOffset <= -rowHeightPx && i > 0) {
                                    onQueueAction(QueueAction.MoveUp, item.queueItemId, null)
                                    draggingId = queue.items[i - 1].queueItemId
                                    dragOffset += rowHeightPx
                                } else if (dragOffset >= rowHeightPx && i < queue.items.lastIndex) {
                                    onQueueAction(QueueAction.MoveDown, item.queueItemId, null)
                                    draggingId = queue.items[i + 1].queueItemId
                                    dragOffset -= rowHeightPx
                                }
                            },
                            onDragEnd = { draggingId = null; dragOffset = 0f },
                            onDragCancel = { draggingId = null; dragOffset = 0f },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun GoalChip(goal: GoalState, onGoalAction: (Boolean) -> Unit) {
    val tokens = LocalZcTokens.current
    val active = goal.isActive

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer
        else tokens.chipBackground,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Flag, null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                goal.summaryTitle ?: goal.objective,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                GoalLabels.status(goal.status),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
            IconButton(onClick = { onGoalAction(active) }, modifier = Modifier.size(26.dp)) {
                Icon(
                    if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (active) "暂停目标" else "继续目标",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}


/**
 * One queued message: drag handle on the left (long-press drag reorders),
 * one line of text, compact actions on the right.
 */
@Composable
private fun QueueRow(
    item: QueueItem,
    dragging: Boolean,
    onSendNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    handleModifier: Modifier,
) {
    val tokens = LocalZcTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.DragHandle, "拖动排序",
            tint = if (dragging) MaterialTheme.colorScheme.primary else tokens.secondaryText,
            modifier = handleModifier.size(26.dp),
        )

        Text(
            item.text.ifBlank {
                // A compact entry is typed maintenance; it has no user text.
                QueueLabels.kindLabel(item.kind)
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )

        if (item.attachmentCount > 0) {
            Text(
                "${item.attachmentCount} 附件",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (item.isReserved) {
            Text(
                "正在启动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        }

        QueueIcon(Icons.Default.Send, "立即发送", onSendNow)
        // A compact entry is not an editable prompt — the host rejects it.
        if (!item.isMaintenance) {
            QueueIcon(Icons.Default.Edit, "编辑", onEdit)
        }
        QueueIcon(Icons.Default.Delete, "移除", onDelete)
    }
}

@Composable
private fun QueueIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon, label,
            modifier = Modifier.size(15.dp),
            tint = LocalZcTokens.current.secondaryText,
        )
    }
}

@Composable
private fun BackgroundWorkRow(work: BackgroundWork, onCancel: (String) -> Unit) {
    val tokens = LocalZcTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (work.isRunning) Icons.Default.PlayArrow else Icons.Default.Stop,
            null,
            modifier = Modifier.size(13.dp),
            tint = tokens.secondaryText,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            work.title.ifBlank { work.workId },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            BackgroundLabels.status(work.status),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.secondaryText,
        )
        if (work.cancellable && work.isRunning) {
            IconButton(onClick = { onCancel(work.workId) }, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Default.Close, "取消",
                    modifier = Modifier.size(15.dp),
                    tint = tokens.danger,
                )
            }
        }
    }
}

@Composable
private fun EditQueueDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑排队中的消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GoalDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设定目标") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("这个会话要达成什么？") },
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "目标会持续驱动后续回合，直到你暂停或它被判定完成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalZcTokens.current.secondaryText,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text("设定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Wire enums are identifiers; they are never shown raw. */
object GoalLabels {
    fun status(wire: String): String = when (wire) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "verifying" -> "验证中"
        "verified" -> "已达成"
        "notSatisfied" -> "未达成"
        "failed" -> "验证失败"
        else -> wire
    }
}

object QueueLabels {
    fun pauseReason(wire: String?): String = when (wire) {
        "stopped" -> "已暂停（中断后保留）"
        "manual" -> "已手动暂停"
        "error" -> "因错误暂停"
        else -> "已暂停"
    }

    fun kindLabel(kind: String): String = when (kind) {
        "compact" -> "压缩上下文"
        "sendGoalCommand" -> "目标指令"
        "sendText" -> "消息"
        else -> kind
    }
}

object BackgroundLabels {
    fun status(wire: String): String = when (wire) {
        "running" -> "运行中"
        "resultPending" -> "结果待投递"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        else -> wire
    }
}
