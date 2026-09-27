package dev.zcodemobile.app.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.components.ZcAlertDialog
import dev.zcodemobile.app.ui.components.ZcDropdownMenu
import dev.zcodemobile.app.ui.theme.Dims
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import dev.zcodemobile.app.session.ConnState
import dev.zcodemobile.app.session.ConversationUiState
import dev.zcodemobile.protocol.Commands
import dev.zcodemobile.protocol.PendingInteraction
import dev.zcodemobile.protocol.Row as TranscriptRow

/**
 * Conversation view: header, transcript, composer.
 *
 * Structure mirrors the desktop-mobile client conventions this app targets —
 * a compact title bar that also carries workspace identity, a transcript that
 * only auto-scrolls when the reader is already at the bottom, and a composer
 * that shows the live model and context budget.
 */
@Composable
fun ConversationScreen(
    title: String,
    workspace: String?,
    state: ConversationUiState,
    connState: ConnState = ConnState.Idle,
    reconnectAttempt: Int = 0,
    onBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    fontScale: Float = 1f,
    onSetMsgSize: (Float) -> Unit = {},
    onSend: (text: String, startNow: Boolean, planEnabled: Boolean?) -> Unit,
    onSendWithQueueDecision: (text: String, clearQueue: Boolean) -> Unit,
    onRetryReconnect: () -> Unit = {},
    onStopReconnect: () -> Unit = {},
    onStop: () -> Unit,
    onResolve: (interactionId: String, optionId: String?) -> Unit,
    onSnooze: (interactionId: String) -> Unit,
    onLoadOlder: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (ref: String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onPickModel: (dev.zcodemobile.protocol.ModelOption) -> Unit,
    onPickMode: (Commands.Mode) -> Unit,
    onPickThought: (String) -> Unit,
    onPickBranch: (String) -> Unit,
    onRowAction: (rowId: Long, entityId: String, action: RowAction, text: String?) -> Unit,
    onQueueAction: (QueueAction, String, String?) -> Unit,
    onSetGoal: (String) -> Unit,
    onGoalAction: (pause: Boolean) -> Unit,
    onCompact: () -> Unit,
    onCancelBackgroundWork: (String) -> Unit,
) {
    val conv = state.conversation
    val listState = rememberLazyListState()
    val tokens = LocalZcTokens.current
    // Hoisted so a suggestion chip can seed the input without sending it.
    var draft by remember { mutableStateOf("") }
    // Set when a send needs the "clear the queue first?" decision.
    var pendingSend by remember { mutableStateOf<String?>(null) }

    // Stickiness is a user-intent flag, not a derived position. It turns off
    // only on a scroll the user initiated, and back on only when they return
    // to the tail themselves. Deriving it purely from position is wrong: the
    // list has no laid-out items for a frame whenever new rows arrive, and
    // treating "unknown" as "at bottom" snaps the reader back on every delta.
    var stickToBottom by remember { mutableStateOf(true) }
    var showJumpToBottom by remember { mutableStateOf(false) }
    val scrollScope = rememberCoroutineScope()

    val turnRunning = conv.canStop

    // Actions live only at the very end of the answer.
    val lastAssistantRowId = conv.rows
        .lastOrNull { it.kind == "assistantText" && it.state == "complete" }?.rowId

    // Per-turn presentation: a running turn streams its process rows live
    // (reasoning as a one-line ticker); every finished turn folds its whole
    // process — thinking, tool calls, intermediate messages — into one bar,
    // leaving only the final summary visible.
    val transcriptItems = remember(conv.rows, turnRunning) {
        buildTranscriptItems(conv.rows, turnRunning)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            when {
                info.totalItemsCount == 0 || last == null -> null
                else -> last.index >= info.totalItemsCount - 2
            }
        }.collect { atBottom ->
            when (atBottom) {
                null -> Unit // not measured yet — keep whatever intent we had
                // Only the user's own scroll re-arms following: the transcript
                // growing at the bottom must not, or the "stop following at
                // the summary" decision below would be undone every delta.
                true -> {
                    if (listState.isScrollInProgress) stickToBottom = true
                    showJumpToBottom = false
                }
                false -> {
                    if (listState.isScrollInProgress) stickToBottom = false
                    showJumpToBottom = true
                }
            }
        }
    }

    // The follow engine: any growth of the tail — a row added, streamed text
    // extending it, tool output appending, an expanded process card growing —
    // keeps the reader pinned at the bottom. A user swipe up (the intent flow
    // above turns the flag off) is the ONLY thing that stops it; growth never
    // cancels following on its own. Gated on !isScrollInProgress so the snap
    // never fights the drag frames of a swipe, and on the last visible item
    // being the actual last so loadOlder's prepends don't trigger it.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, lastVisible?.index, lastVisible?.size)
        }.distinctUntilChanged().collect { (count, lastIndex, _) ->
            if (stickToBottom && !listState.isScrollInProgress && lastIndex == count - 1) {
                listState.snapToTail()
            }
        }
    }

    // Reaching the top pulls the next page of history. ZCodeSession guards
    // against re-entry and against paging past the start.
    LaunchedEffect(listState, conv.sessionId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { if (it <= 1) onLoadOlder() }
    }

    // Entering an existing conversation lands at the newest message, not at
    // the top of the snapshot window. The follow engine can't do this: it
    // only re-arms while the last item is already on screen, which is false
    // on a fresh layout that starts at the top. One-shot per session.
    LaunchedEffect(conv.sessionId, conv.hasSnapshot) {
        if (conv.hasSnapshot && conv.rows.isNotEmpty()) {
            stickToBottom = true
            listState.snapToTail()
        }
    }

    conv.pending.firstOrNull()?.let { pending ->
        InteractionDialog(pending = pending, onResolve = onResolve, onSnooze = onSnooze)
    }

    pendingSend?.let { text ->
        QueueDecisionDialog(
            queueCount = conv.queue.items.size,
            pauseReason = conv.queue.pauseReason,
            onDismiss = { pendingSend = null },
            onDecide = { clear ->
                onSendWithQueueDecision(text, clear)
                pendingSend = null
            },
        )
    }

    // Floating chrome over full-bleed content: the transcript scrolls behind
    // the header and the composer, both translucent, so text peeks through
    // under them while it moves. The bars are overlays; the content layer
    // reserves their height through contentPadding instead of layout.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // The status strip (queue/goal/background work) adds height to the
        // bottom stack whenever it is visible.
        val hasStatusStrip = conv.queue.items.isNotEmpty() || conv.goal != null
        val bottomReserve = navBottom + 160.dp + if (hasStatusStrip) 52.dp else 0.dp

        // ── content layer: full-bleed, runs behind both bars ──
        Box(Modifier.fillMaxSize()) {
            when {
                // Opening an existing session: `beginOpenSession` blanks the
                // projection before the snapshot lands. An empty row list in
                // that window is "loading", not "empty conversation" — showing
                // the 你好 starter page for an old session is the bug this
                // branch fixes.
                conv.rows.isEmpty() && !conv.hasSnapshot -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "正在载入会话…",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.secondaryText,
                        )
                    }
                }
                conv.rows.isEmpty() -> {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        EmptyTranscript(onSuggestion = { draft = it })
                    }
                }
                else -> {
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.zcodemobile.app.ui.theme.LocalZcMsgScale provides fontScale,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = statusTop + 98.dp,
                            bottom = bottomReserve,
                        ),
                    ) {
                        if (conv.hasMoreOlder || conv.loadingOlder) {
                            item(key = "older-history") { HistoryLoader(conv.loadingOlder) }
                        }
                        items(transcriptItems.size, key = { transcriptItems[it].key }) { i ->
                            when (val item = transcriptItems[i]) {
                                is TranscriptItem.Single -> {
                                    val row = item.row
                                    ConversationRow(
                                        row = row,
                                        // Copy/feedback belong only at the end of the
                                        // final answer of a finished turn — never on
                                        // the intermediate texts of a running one.
                                        showActions = !turnRunning && row.rowId == lastAssistantRowId,
                                        live = item.live,
                                        onCopy = {},
                                        onAction = { action ->
                                            val entity = row.entityId
                                            if (entity.isNullOrBlank()) {
                                                // Every row-targeting command needs the
                                                // stable entity id; a row without one
                                                // cannot be addressed.
                                                state.lastError?.let { }
                                            } else {
                                                onRowAction(row.rowId, entity, action, row.text)
                                            }
                                        },
                                    )
                                }
                                is TranscriptItem.Process -> ProcessGroupCard(
                                    rows = item.rows,
                                    onRowAction = { row, action ->
                                        val entity = row.entityId
                                        if (!entity.isNullOrBlank()) {
                                            onRowAction(row.rowId, entity, action, row.text)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                }
            }

            // Jump-to-tail pill, floating just above the composer overlay.
            AnimatedVisibility(
                visible = showJumpToBottom && conv.rows.isNotEmpty(),
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.8f),
                exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = bottomReserve - 28.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.87f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 3.dp,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowDownward, "回到最新",
                        modifier = Modifier
                            .padding(9.dp)
                            .clickable { stickToBottom = true; scrollScope.launch { listState.snapToTail() } },
                        tint = tokens.secondaryText,
                    )
                }
            }
        }

        // ── top overlay ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            ConversationHeader(
                title = title.ifBlank { conv.title ?: "会话" },
                subtitle = buildSubtitle(workspace, conv.mode, conv.phase, conv.rows.size, conv.totalCount),
                canCompact = conv.allowed("compact"),
                fontScale = fontScale,
                onSetMsgSize = onSetMsgSize,
                onBack = onBack,
                onOpenSidebar = onOpenSidebar,
                onCompact = onCompact,
            )

            AnimatedVisibility(
                visible = connState == ConnState.Reconnecting,
                enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(14.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "连接断开，正在重连（第 $reconnectAttempt 次）…",
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRetryReconnect) { Text("立即重试") }
                        TextButton(onClick = onStopReconnect) { Text("停止") }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.lastError != null,
                enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                state.lastError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            err,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ── bottom overlay: status strip + composer ──
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SessionStatusPanel(
                conversation = conv,
                onQueueAction = onQueueAction,
                onGoalAction = onGoalAction,
                onCancelBackgroundWork = onCancelBackgroundWork,
                onSetGoal = onSetGoal,
            )

            Composer(
            git = state.git,
            conversation = conv,
            sending = state.sending,
            connected = true,
            attachments = state.attachments,
            uploading = state.uploading,
            uploadProgress = state.uploadProgress,
            draft = draft,
            onDraftChange = { draft = it },
            models = state.models,
            modelsLoading = state.modelsLoading,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            onOpenModelPicker = onOpenModelPicker,
            onPickModel = onPickModel,
            onPickMode = onPickMode,
            onPickThought = onPickThought,
            onPickBranch = onPickBranch,
            onStop = onStop,
            onSend = { text, startNow, plan ->
                // Under `choice` routing the host refuses a bare send, so the
                // decision is collected before the command goes out.
                if (conv.needsQueueDecision && conv.queue.items.isNotEmpty()) {
                    pendingSend = text
                } else {
                    onSend(text, startNow, plan)
                }
            },
        )
        }
    }
}

/**
 * "The queue is paused" decision.
 *
 * `inputRouting.mode = choice` means the host will not silently enqueue: the
 * user has to say whether the held items should go first or be dropped.
 */
@Composable
private fun QueueDecisionDialog(
    queueCount: Int,
    pauseReason: String?,
    onDismiss: () -> Unit,
    onDecide: (clearQueue: Boolean) -> Unit,
) {
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("队列里有 $queueCount 条待发消息") },
        text = {
            Text(
                "当前队列已暂停（${QueueLabels.pauseReason(pauseReason)}）。" +
                    "你可以先清空队列再发送这条，或者保留它们并立即发送这条。"
            )
        },
        confirmButton = {
            TextButton(onClick = { onDecide(false) }) { Text("保留队列并发送") }
        },
        dismissButton = {
            TextButton(onClick = { onDecide(true) }) { Text("清空队列后发送") }
        },
    )
}

/**
 * Presentation slots of the transcript.
 *
 * A turn's process — thinking, tool calls, intermediate messages — collapses
 * into a single [Process] bar once the turn ends; the running turn renders
 * its rows individually with [TranscriptItem.Single.live] previews.
 */
private sealed interface TranscriptItem {
    val key: String

    data class Single(val row: TranscriptRow, val live: Boolean) : TranscriptItem {
        override val key: String get() = "r${row.rowId}"
    }

    data class Process(val rows: List<TranscriptRow>) : TranscriptItem {
        override val key: String
            get() = "p${rows.first().rowId}-${rows.last().rowId}"
    }
}

/**
 * Group transcript rows by turn (a turn starts at its `userInput` row).
 *
 * Finished turns fold everything except their final assistant summary into
 * one [TranscriptItem.Process]; the trailing (running) turn streams its rows
 * individually so the reader can watch progress.
 */
private fun buildTranscriptItems(rows: List<TranscriptRow>, turnRunning: Boolean): List<TranscriptItem> {
    val items = ArrayList<TranscriptItem>()
    var buffer = ArrayList<TranscriptRow>()

    fun flush(live: Boolean) {
        if (buffer.isEmpty()) return
        val summaryIdx = buffer.indexOfLast { it.kind == "assistantText" }
        if (!live && summaryIdx >= 0) {
            val process = buffer.filterIndexed { index, _ -> index != summaryIdx }
            if (process.isNotEmpty()) items.add(TranscriptItem.Process(process))
            items.add(TranscriptItem.Single(buffer[summaryIdx], live = false))
        } else {
            buffer.forEach { items.add(TranscriptItem.Single(it, live = live)) }
        }
        buffer = ArrayList()
    }

    for (row in rows) {
        when (row.kind) {
            "userInput" -> {
                flush(live = false)
                items.add(TranscriptItem.Single(row, live = false))
            }
            "turnHeader" -> items.add(TranscriptItem.Single(row, live = false))
            else -> buffer.add(row)
        }
    }
    flush(live = turnRunning)
    return items
}

private fun buildSubtitle(
    workspace: String?,
    mode: String?,
    phase: String?,
    loaded: Int,
    total: Int,
): String = buildList {
    workspace?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }?.let(::add)
    ModeLabels.label(mode)?.let { add(it) }
    phase?.let { add(it) }
    add("$loaded/$total 行")
}.joinToString(" · ")

private data class MsgSizeOption(val label: String, val scale: Float)

private val MSG_SIZES = listOf(
    MsgSizeOption("小", 0.85f),
    MsgSizeOption("标准", 1f),
    MsgSizeOption("大", 1.15f),
    MsgSizeOption("特大", 1.3f),
)

@Composable
private fun ConversationHeader(
    title: String,
    subtitle: String,
    canCompact: Boolean,
    fontScale: Float,
    onSetMsgSize: (Float) -> Unit,
    onBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    onCompact: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }

    if (confirmCompact) {
        ZcAlertDialog(
            onDismissRequest = { confirmCompact = false },
            title = { Text("压缩上下文") },
            text = {
                Text(
                    "把已有对话压缩成摘要，释放上下文窗口。" +
                        "聊天记录本身不会被删除，但更早的细节会变成摘要。"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmCompact = false; onCompact() }) { Text("压缩") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCompact = false }) { Text("取消") }
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        // Distinct elevated color: `surface` equals `background` in this theme,
        // so a `surface` tint is invisible and the card reads as "missing".
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.Default.ArrowBack, "返回", onClick = onBack)

        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Task-management page mirroring the desktop sidebar (third level:
        // home → conversation → sidebar).
        CircleIconButton(Icons.Default.Menu, "侧栏", onClick = onOpenSidebar)

        Box {
            CircleIconButton(Icons.Default.MoreHoriz, "更多") { menuOpen = true }
            ZcDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("压缩上下文") },
                    leadingIcon = { Icon(Icons.Default.Compress, null) },
                    enabled = canCompact,
                    onClick = { menuOpen = false; confirmCompact = true },
                )
                HorizontalDivider()
                Text(
                    "文字大小",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                MSG_SIZES.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.label) },
                        trailingIcon = {
                            if (size.scale == fontScale) {
                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                            }
                        },
                        onClick = { menuOpen = false; onSetMsgSize(size.scale) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HistoryLoader(loading: Boolean) {
    val tokens = LocalZcTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
            Text("加载更早的消息…", style = MaterialTheme.typography.labelSmall, color = tokens.secondaryText)
        } else {
            Text("上滑加载更早的消息", style = MaterialTheme.typography.labelSmall, color = tokens.secondaryText)
        }
    }
}

@Composable
private fun EmptyTranscript(onSuggestion: (String) -> Unit) {
    val tokens = LocalZcTokens.current

    // An empty session is the one screen with room to breathe, so it carries a
    // large headline and a few concrete starting points rather than a bare
    // "no content" line. Tapping a suggestion only fills the input — sending is
    // still the user's call. Centered between the floating header and the
    // composer: a fixed top padding would sit under the header on small
    // screens (the layout bug this replaced).
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
    ) {
        Spacer(Modifier.height(96.dp))
        Text(
            "你好",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "有什么可以帮你的？",
            style = MaterialTheme.typography.displaySmall,
            color = tokens.secondaryText,
        )

        Spacer(Modifier.height(36.dp))

        STARTERS.forEachIndexed { index, starter ->
            // Staggered entrance: each chip fades and slides in a beat after
            // the previous one.
            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
            val enter = tween<Float>(320, delayMillis = 100 + index * 90)
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn(enter) + slideInVertically(tween<IntOffset>(320, delayMillis = 100 + index * 90)) { it / 3 },
                exit = fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSuggestion(starter.prompt) },
                ) {

                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            starter.icon, contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = tokens.secondaryText,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(starter.title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(160.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun Composer(
    conversation: dev.zcodemobile.protocol.ConversationState,
    git: dev.zcodemobile.protocol.GitRepoInfo,
    sending: Boolean,
    connected: Boolean,
    attachments: List<dev.zcodemobile.protocol.AttachmentRef>,
    uploading: Boolean,
    uploadProgress: String?,
    draft: String,
    onDraftChange: (String) -> Unit,
    models: List<dev.zcodemobile.protocol.ModelOption>,
    modelsLoading: Boolean,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenModelPicker: () -> Unit,
    onPickModel: (dev.zcodemobile.protocol.ModelOption) -> Unit,
    onPickMode: (Commands.Mode) -> Unit,
    onPickThought: (String) -> Unit,
    onPickBranch: (String) -> Unit,
    onStop: () -> Unit,
    onSend: (String, Boolean, Boolean?) -> Unit,
) {
    val tokens = LocalZcTokens.current
    var modelSheet by remember { mutableStateOf(false) }
    var contextDetail by remember { mutableStateOf(false) }
    var thoughtMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    // The plan axis is independent of the mode radio group (web structure).
    // null = follow the session state; a value here is baked into the next
    // sendText, exactly how the web composer chips behave.
    var planOverride by remember { mutableStateOf<Boolean?>(null) }
    val planOn = planOverride ?: conversation.planEnabled ?: false
    val keyboard = LocalSoftwareKeyboardController.current

    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && connected && !uploading
    val running = conversation.canStop

    val doSend: (Boolean) -> Unit = { startNow ->
        if (canSend) {
            onSend(draft.trim(), startNow, planOverride)
            onDraftChange("")
            planOverride = null
            keyboard?.hide()
        }
    }

    Surface(
        // Same chrome as the header card: a translucent panel the transcript
        // keeps scrolling behind, so the two bars read as one system.
        //
        // The controls inside therefore take the page background rather than
        // `chipBackground`: that token IS `surfaceContainerHigh` in both
        // themes, which is this card's own colour — on it the input pill and
        // the chips would float invisibly.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .navigationBarsPadding()
            .padding(bottom = 10.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The mode label is the cheapest thing to sacrifice: below this
        // width only the shield icon shows.
        val showModeLabel = maxWidth >= 300.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (!connected) {
                Text(
                    "设备离线，保持桌面端在线后可继续发指令…",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.secondaryText,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (attachments.isNotEmpty() || uploading) {
                AttachmentStrip(
                    attachments = attachments,
                    uploading = uploading,
                    progress = uploadProgress,
                    onRemove = onRemoveAttachment,
                )
                Spacer(Modifier.height(8.dp))
            }

            ComposerInput(
                draft = draft,
                enabled = connected,
                sending = sending,
                running = running,
                canSend = canSend,
                onDraftChange = onDraftChange,
                onStartNow = { doSend(true) },
                onQueue = { doSend(false) },
                onStop = onStop,
            )

            Spacer(Modifier.height(8.dp))

            // One compact control row, mirroring the desktop composer:
            // + | 🛡 mode | ◯ model | 🧠 thought | ↑ send
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Fixed left cluster + a flexible model slot; the ring stays
                // pinned at the far right of the bar.
                Box(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ComposerCircleButton(
                            Icons.Default.Add, "添加附件",
                            enabled = connected && !uploading,
                            onClick = onAttach,
                        )

                        Spacer(Modifier.width(6.dp))

                        Box {
                            ComposerChip(
                                icon = Icons.Default.Shield,
                                text = if (showModeLabel) {
                                    ModeLabels.label(conversation.mode) ?: "模式未选择"
                                } else {
                                    null
                                },
                                onClick = { modeMenu = true },
                            )
                            ModeMenu(
                                expanded = modeMenu,
                                planOn = planOn,
                                currentMode = conversation.mode,
                                onPlanChange = {
                                    planOverride = it
                                    modeMenu = false
                                },
                                onPickMode = {
                                    planOverride = null
                                    modeMenu = false
                                    onPickMode(it)
                                },
                                onDismiss = { modeMenu = false },
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(Modifier.weight(1f)) {
                            // The row's slack stays *outside* the chip: the
                            // pill is only as wide as its label, so a short
                            // model name leaves the blank to its left instead
                            // of stretching a half-empty pill across it.
                            ComposerChip(
                                icon = null,
                                text = modelChipLabel(conversation, models),
                                onClick = { modelSheet = true; onOpenModelPicker() },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Box {
                            ComposerChip(
                                icon = Icons.Default.Psychology,
                                text = ThoughtLabels.label(conversation.thoughtLevel) ?: "思考未选择",
                                onClick = { thoughtMenu = true },
                            )
                            ZcDropdownMenu(expanded = thoughtMenu, onDismissRequest = { thoughtMenu = false }) {
                                val levels = conversation.thoughtLevels
                                if (levels.isEmpty()) {
                                    DropdownMenuItem(text = { Text("该模型不支持") }, enabled = false, onClick = {})
                                }
                                levels.forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(ThoughtLabels.label(level) ?: level) },
                                        trailingIcon = {
                                            if (level == conversation.thoughtLevel) {
                                                Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                            }
                                        },
                                        onClick = { thoughtMenu = false; onPickThought(level) },
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Context ring lives at the far right, after thinking.
                if (conversation.usage.fraction != null) {
                    ContextRing(usage = conversation.usage) { contextDetail = true }
                }
            }

            if (contextDetail) {
                ContextDetailDialog(conversation.usage) { contextDetail = false }
            }

            if (modelSheet) {
                ModelPickerSheet(
                    models = models,
                    loading = modelsLoading,
                    currentProviderId = conversation.provider,
                    currentModelId = conversation.model,
                    onPick = {
                        modelSheet = false
                        onPickModel(it)
                    },
                    onDismiss = { modelSheet = false },
                )
            }

            GitRow(git = git, onPickBranch = onPickBranch)
        }
        }
    }
}

private fun modelChipLabel(
    conversation: dev.zcodemobile.protocol.ConversationState,
    models: List<dev.zcodemobile.protocol.ModelOption>,
): String {
    // No model info yet (fresh session before the first snapshot) — say so
    // explicitly instead of showing a bare "模型" that reads as a label.
    val model = conversation.model?.takeIf { it.isNotBlank() } ?: return "模型未选择"
    val match = models.firstOrNull {
        it.providerId == conversation.provider && it.modelId == model
    }
    return match?.shortName ?: model.substringAfterLast(':')
}

/**
 * Mode menu in the web's two-axis structure: plan is a checkbox above a
 * divider, then the three-mode radio group. Copy from the web composer
 * (LIVE-WEB-VERIFICATION §4.1).
 */
@Composable
private fun ModeMenu(
    expanded: Boolean,
    planOn: Boolean,
    currentMode: String?,
    onPlanChange: (Boolean) -> Unit,
    onPickMode: (Commands.Mode) -> Unit,
    onDismiss: () -> Unit,
) {
    ZcDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        "计划模式",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "编辑前先出计划。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingIcon = {
                androidx.compose.material3.Checkbox(checked = planOn, onCheckedChange = null)
            },
            onClick = { onPlanChange(!planOn) },
        )
        HorizontalDivider()
        listOf(Commands.Mode.Build, Commands.Mode.Edit, Commands.Mode.Yolo).forEach { m ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(ModeLabels.label(m), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            ModeLabels.describe(m),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    androidx.compose.material3.RadioButton(
                        selected = m.wire == currentMode,
                        onClick = null,
                    )
                },
                onClick = { onPickMode(m) },
            )
        }
    }
}


/** Small donut showing the context-window fill, tinted by pressure. */
@Composable
private fun ContextRing(usage: dev.zcodemobile.protocol.ContextUsage, onClick: () -> Unit) {
    val tokens = LocalZcTokens.current
    val fraction = usage.fraction ?: return
    val animated by animateFloatAsState(fraction, label = "ctx-ring")
    val tint = when {
        animated > 0.9f -> tokens.danger
        animated > 0.7f -> tokens.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = Stroke(width = 2.5.dp.toPx())
                drawArc(
                    color = tokens.secondaryText.copy(alpha = 0.3f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = -90f, sweepAngle = 360f * animated, useCenter = false,
                    style = stroke,
                )
            },
    )
}

/** Staged uploads, shown above the input so they are visible before sending. */
@Composable
private fun AttachmentStrip(
    attachments: List<dev.zcodemobile.protocol.AttachmentRef>,
    uploading: Boolean,
    progress: String?,
    onRemove: (String) -> Unit,
) {
    val tokens = LocalZcTokens.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { a ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {

                Row(
                    Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (a.isImage) Icons.Default.Image else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = tokens.secondaryText,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        a.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                    IconButton(
                        onClick = { onRemove(a.ref) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close, "移除",
                            modifier = Modifier.size(13.dp),
                            tint = tokens.secondaryText,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        if (uploading) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {

                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        progress?.let { "上传中 $it" } ?: "上传中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.secondaryText,
                    )
                }
            }
        }
    }
}

/**
 * The input pill: one line by default, grows to at most three, then scrolls
 * internally. The send control lives inside the pill at its bottom-right and
 * the text wraps short of it, so long drafts never run under the button.
 */
@Composable
private fun ComposerInput(
    draft: String,
    enabled: Boolean,
    sending: Boolean,
    running: Boolean,
    canSend: Boolean,
    onDraftChange: (String) -> Unit,
    onStartNow: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    Box(
        Modifier
            // Page background, not `chipBackground`: see the composer card's
            // note — the pill has to contrast with the translucent bar.
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(26.dp))
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) {
                    Text(
                        if (enabled) "发消息给 ZCode…" else "设备离线…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.secondaryText,
                        maxLines = 1,
                        // Same vertical rhythm as the field below, so the
                        // placeholder and the typed text sit on one baseline.
                        modifier = Modifier.padding(top = 11.dp, bottom = 11.dp),
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 11.dp, bottom = 11.dp),
                )
            }

            Spacer(Modifier.width(4.dp))

            SendButton(
                enabled = canSend,
                sending = sending,
                running = running,
                onStartNow = onStartNow,
                onQueue = onQueue,
                onStop = onStop,
                // Bottom-end of the pill; the extra bottom padding balances the
                // field's own padding so a single line reads centered.
                modifier = Modifier.padding(bottom = 7.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun ComposerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.size(36.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, label,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    tokens.secondaryText.copy(alpha = 0.5f)
                },
            )
        }
    }
}

/**
 * The single send control, whose meaning follows the session state exactly
 * like the web's: idle = send now (↑), running with text = add to queue,
 * running with an empty input = stop the generation.
 */
@Composable
private fun SendButton(
    enabled: Boolean,
    sending: Boolean,
    running: Boolean,
    onStartNow: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalZcTokens.current
    val stop = running && !enabled
    val queue = running && enabled
    val tint = when {
        stop -> MaterialTheme.colorScheme.error
        enabled || sending -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.background
    }
    val contentTint = when {
        stop -> MaterialTheme.colorScheme.onError
        enabled || sending -> MaterialTheme.colorScheme.onPrimary
        else -> tokens.secondaryText
    }
    Surface(
        shape = CircleShape,
        color = tint,
        modifier = modifier.size(34.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(enabled = enabled || running || sending) {
                    when {
                        sending -> Unit
                        stop -> onStop()
                        queue -> onQueue()
                        else -> onStartNow()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                sending -> CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                stop -> Icon(
                    Icons.Default.Stop, "停止生成",
                    modifier = Modifier.size(19.dp),
                    tint = contentTint,
                )
                queue -> Icon(
                    Icons.Default.PlaylistAdd, "加入队列",
                    modifier = Modifier.size(20.dp),
                    tint = contentTint,
                )
                else -> Icon(
                    Icons.Default.ArrowUpward, "发送",
                    modifier = Modifier.size(19.dp),
                    tint = contentTint,
                )
            }
        }
    }
}

/**
 * Compact control chip: optional leading icon + label.
 *
 * `text = null` renders the icon-only variant (the mode chip's fallback when
 * the row runs out of width). The chip is only ever as wide as its label: a
 * short name must not leave a blank stretch inside the pill, so the row — not
 * the chip — owns the slack. The caller bounds the width instead, letting a
 * long name ellipsize rather than push its neighbours out.
 */
@Composable
private fun ComposerChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.clickable(onClick = onClick),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    it, null,
                    modifier = Modifier.size(12.dp),
                    tint = tokens.secondaryText,
                )
                if (text != null) Spacer(Modifier.width(3.dp))
            }
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}




/**
 * What the context pill opens into: the same total, split by what is actually
 * occupying the window, plus the prompt-cache hit rate. Reading "98% 消息"
 * is what tells you whether compaction is worth it.
 */
@Composable
private fun ContextDetailDialog(
    usage: dev.zcodemobile.protocol.ContextUsage,
    onDismiss: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    val rows = usage.detailRows()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文容量", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        buildString {
                            append(formatCountZh(usage.usedTokens))
                            append("/")
                            append(formatCountZh(usage.maxTokens))
                            usage.fraction?.let { append("  (${(it * 100).toInt()}%)") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.secondaryText,
                    )
                }

                Spacer(Modifier.height(12.dp))

                usage.fraction?.let { f ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(tokens.chipBackground),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                rows.forEach { (label, share) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (share < 0.001f) "0%" else "%.1f%%".format(share * 100),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.secondaryText,
                        )
                    }
                }

                usage.cacheHitRate?.let { rate ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("平均缓存命中率", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "%.1f%%".format(rate * 100),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.secondaryText,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chinese myriad grouping: 711_000 reads as "71.1万", which is how the count is
 * spoken. Plain "711k" is an English convention and reads as a foreign unit.
 */
private fun formatCountZh(value: Long?): String = when {
    value == null -> "—"
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}

/**
 * Blocking prompt: the agent is paused until this is answered.
 *
 * The snooze action exists because the desktop may auto-resolve an
 * `AskUserQuestion` on a countdown; snoozing stops that timer for this
 * interaction so the answer stays the user's to give.
 */
@Composable
private fun InteractionDialog(
    pending: PendingInteraction,
    onResolve: (interactionId: String, optionId: String?) -> Unit,
    onSnooze: (interactionId: String) -> Unit,
) {
    var snoozed by remember(pending.interactionId) { mutableStateOf(false) }

    ZcAlertDialog(
        onDismissRequest = { /* blocking by design */ },
        title = { Text(pending.title ?: pending.kind ?: "需要确认") },
        text = {
            Column {
                pending.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                pending.options.forEach { opt ->
                    OutlinedButton(
                        onClick = { onResolve(pending.interactionId, opt.optionId) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(opt.name ?: opt.optionId)
                            opt.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalZcTokens.current.secondaryText,
                                )
                            }
                        }
                    }
                }
                if (pending.options.isEmpty()) {
                    TextButton(onClick = { onResolve(pending.interactionId, null) }) {
                        Text("知道了")
                    }
                }
                if (snoozed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已停止自动应答，等你决定。",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalZcTokens.current.secondaryText,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!snoozed) {
                TextButton(onClick = {
                    onSnooze(pending.interactionId)
                    snoozed = true
                }) { Text("停止自动应答") }
            }
        },
    )
}

/**
 * Which repository and branch this session is working in.
 *
 * ZCode's model is workspace-scoped — a session is already bound to one
 * workspace — so the workspace is a label and only the branch is switchable.
 * Non-repository workspaces render nothing rather than an empty picker.
 */
@Composable
private fun GitRow(git: dev.zcodemobile.protocol.GitRepoInfo, onPickBranch: (String) -> Unit) {
    if (!git.isRepository) return
    val tokens = LocalZcTokens.current
    var menu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable { menu = true },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AccountTree, contentDescription = "分支",
                        modifier = Modifier.size(15.dp),
                        tint = tokens.secondaryText,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        git.branchLabel,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                    // An uncommitted tree is the common reason a switch fails.
                    if (git.isDirty) {
                        Spacer(Modifier.width(4.dp))
                        Text("•", style = MaterialTheme.typography.labelMedium, color = tokens.warning)
                    }
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Default.ExpandMore, contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = tokens.secondaryText,
                    )
                }
            }
            ZcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                git.branches.forEach { b ->
                    DropdownMenuItem(
                        text = { Text(b.name, style = MaterialTheme.typography.bodyMedium) },
                        trailingIcon = {
                            if (b.isCurrent) Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        },
                        onClick = { menu = false; onPickBranch(b.name) },
                    )
                }
            }
        }

        git.repoName?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.weight(1f))

        if (git.ahead > 0 || git.behind > 0) {
            Text(
                buildString {
                    if (git.ahead > 0) append("↑${git.ahead}")
                    if (git.behind > 0) { if (isNotEmpty()) append(" "); append("↓${git.behind}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
        }
    }
}

/**
 * Jump to the true bottom of the list — including past a last item taller
 * than the viewport, where a plain `scrollToItem(last)` would land on that
 * item's top and hide the newest content.
 */
internal suspend fun LazyListState.snapToTail() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    scrollToItem(count - 1)
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val hidden = last.offset + last.size - layoutInfo.viewportEndOffset
    if (hidden > 0) scrollBy(hidden.toFloat())
}
