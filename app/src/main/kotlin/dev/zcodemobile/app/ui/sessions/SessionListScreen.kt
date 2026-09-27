package dev.zcodemobile.app.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.zcodemobile.app.data.SavedLink
import dev.zcodemobile.app.session.ConnState
import dev.zcodemobile.app.ui.components.ZcAlertDialog
import dev.zcodemobile.app.ui.components.ZcDropdownMenu
import dev.zcodemobile.app.ui.theme.Dims
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home screen: every task of the desktop, grouped into project sections and
 * ordered as a timeline.
 *
 * Two states in one screen: before a link is chosen it is a connection
 * manager, afterwards it is the project-grouped task list.
 */
@Composable
fun SessionListScreen(
    links: List<SavedLink>,
    connState: ConnState,
    error: String?,
    reconnectAttempt: Int = 0,
    desktopVersion: String?,
    groups: List<SessionGroup>,
    sortBy: TaskSort,
    onSortBy: (TaskSort) -> Unit,
    viewMode: HomeView,
    onViewMode: (HomeView) -> Unit,
    rows: List<SessionRow>,
    onAddLink: () -> Unit,
    onScan: () -> Unit,
    onDeleteLink: (SavedLink) -> Unit,
    onOpenLink: (SavedLink) -> Unit,
    onOpenSession: (SessionRow) -> Unit,
    onNewSession: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onPinSession: (SessionRow, Boolean) -> Unit = { _, _ -> },
    onArchiveSession: (SessionRow, Boolean) -> Unit = { _, _ -> },
    onUnreadSession: (SessionRow, Boolean) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onReconnect: () -> Unit,
    onRetryReconnect: () -> Unit = {},
    onStopReconnect: () -> Unit = {},
    onDisconnect: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    val density = LocalDensity.current
    var chromeTop by remember { mutableStateOf(0.dp) }
    var query by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionRow?>(null) }
    var deleting by remember { mutableStateOf<SessionRow?>(null) }
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }

    // Collapsing search: swiping up slides the search bar up under the fixed
    // header and lets the filter row + list move up to reclaim its space.
    // `chromeCollapsePx` is the full chrome content height (header + search +
    // filter), `searchHeightPx` just the search bar; both measured on layout.
    var collapseOffsetPx by remember { mutableStateOf(0f) }
    var chromeCollapsePx by remember { mutableStateOf(with(density) { 178.dp.toPx() }) }
    var searchHeightPx by remember { mutableStateOf(with(density) { 60.dp.toPx() }) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Only the search bar collapses; the header and filter stay put.
                val max = searchHeightPx
                if (max <= 0f) return Offset.Zero
                val target = (collapseOffsetPx - available.y).coerceIn(0f, max)
                val consumed = collapseOffsetPx - target
                collapseOffsetPx = target
                return Offset(0f, consumed)
            }
        }
    }

    // A rebuild (workspace switch, reconnect) walks through the non-Ready
    // connection states with the row list momentarily empty; keep the list
    // surface instead of flashing the saved-links page.
    val connected = rows.isNotEmpty() ||
        connState == ConnState.Ready ||
        connState == ConnState.Reconnecting ||
        (links.isNotEmpty() &&
            connState != ConnState.Idle && connState != ConnState.Failed)
    val visibleGroups = remember(groups, query) {
        if (query.isBlank()) {
            groups
        } else {
            groups.mapNotNull { g ->
                val hit = g.rows.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        g.label.contains(query, ignoreCase = true) ||
                        it.subtitle?.contains(query, ignoreCase = true) == true
                }
                if (hit.isEmpty()) null else g.copy(rows = hit)
            }
        }
    }

    renaming?.let { row ->
        RenameDialog(
            initial = row.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                onRenameSession(row.sessionId, title)
                renaming = null
            },
        )
    }

    deleting?.let { row ->
        ZcAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("关闭会话") },
            text = {
                Text(
                    "将关闭「${row.title}」并释放它的运行资源。" +
                        "消息记录会保留在桌面端，之后仍可重新打开。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(row.sessionId)
                    deleting = null
                }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    // Opaque chrome over full-bleed content: the fixed header and filter row
    // stay pinned, and only the search bar between them collapses on scroll.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val showTaskList = connected && visibleGroups.isNotEmpty()
        // Space reserved for the chrome, shrinking in step with the gesture so
        // the list content moves up as the search bar collapses.
        val chromeReserve = with(density) {
            (chromeCollapsePx - collapseOffsetPx).coerceAtLeast(0f).toDp()
        }

        // ── content layer ──
        if (!connected && links.isEmpty() && error == null) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                FirstRun(onAddLink = onAddLink, onScan = onScan)
            }
        } else if (!connected) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                LinkList(links = links, onAddLink = onAddLink, onDelete = onDeleteLink, onOpen = onOpenLink)
            }
        } else if (!showTaskList) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (query.isBlank()) "还没有任务" else "没有匹配的任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.secondaryText,
                )
            }
        } else if (viewMode == HomeView.Project) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = statusTop + chromeReserve, bottom = navBottom + 96.dp),
            ) {
                visibleGroups.forEach { group ->
                    item(key = "group-${group.workspacePath}") {
                        Box(Modifier.animateItem()) {
                            GroupHeader(
                                group,
                                collapsed = group.workspacePath in collapsedGroups,
                                onToggle = {
                                    collapsedGroups = if (group.workspacePath in collapsedGroups) {
                                        collapsedGroups - group.workspacePath
                                    } else {
                                        collapsedGroups + group.workspacePath
                                    }
                                },
                            )
                        }
                    }
                    if (group.workspacePath !in collapsedGroups) {
                        items(group.rows, key = { it.key }) { row ->
                            Box(Modifier.animateItem()) {
                                SessionRowItem(
                                    row = row,
                                    onOpen = { onOpenSession(row) },
                                    onRename = { renaming = row },
                                    onDelete = { deleting = row },
                                    onPin = { onPinSession(row, it) },
                                    onArchive = { onArchiveSession(row, it) },
                                    onUnread = { onUnreadSession(row, it) },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // One global timeline for 时间 rather than the project blocks in
            // section order: with several projects the blocks read as random.
            val flat = remember(visibleGroups, sortBy) {
                HomeProjection.timeline(visibleGroups, sortBy)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = statusTop + chromeReserve, bottom = navBottom + 96.dp),
            ) {
                items(flat, key = { it.key }) { row ->
                    Box(Modifier.animateItem()) {
                        SessionRowItem(
                            row = row,
                            // No section header here, so the row itself has to
                            // say which project it belongs to.
                            showWorkspace = true,
                            onOpen = { onOpenSession(row) },
                            onRename = { renaming = row },
                            onDelete = { deleting = row },
                            onPin = { onPinSession(row, it) },
                            onArchive = { onArchiveSession(row, it) },
                            onUnread = { onUnreadSession(row, it) },
                        )
                    }
                }
            }
        }

        // ── top overlay: fixed header + collapsible search + filters ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            // Chrome, measured so the transient banners can overlay just below
            // it without shifting it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        chromeTop = with(density) { it.height.toDp() }
                        chromeCollapsePx = it.height.toFloat()
                    }
            ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
            // ── header (fixed) ──
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "任务",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onScan) {
                    Icon(Icons.Default.QrCodeScanner, "扫描二维码", tint = tokens.secondaryText)
                }
                Box {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(
                            Icons.Default.Settings, "设置",
                            tint = tokens.secondaryText,
                        )
                    }
                    ZcDropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("桌面设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            enabled = connected,
                            onClick = { settingsOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("添加连接") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { settingsOpen = false; onAddLink() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (connected) "断开连接" else "未连接") },
                            enabled = connected,
                            onClick = { settingsOpen = false; onDisconnect() },
                        )
                        DropdownMenuItem(
                            text = { Text(desktopVersion?.let { "桌面 ZCode $it" } ?: "—") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }
            }

            // Search + filters slide up together as the search collapses.
            Column(
                Modifier.offset { IntOffset(0, -collapseOffsetPx.roundToInt()) }
            ) {
                Box(Modifier.onSizeChanged { searchHeightPx = it.height.toFloat() }) {
                    SearchBar(query, onQueryChange = { query = it })
                }

                if (showTaskList) {
                    ViewToggle(viewMode, sortBy, onViewMode, onSortBy)
                }
            }
            }
        }

        // Transient status banners overlay just below the chrome. Kept out of
        // the chrome's Column, so appearing never shifts the search bar or the
        // filter toggle down (the layout bug the red error banner used to cause).
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = chromeTop)
        ) {
            AnimatedVisibility(
                visible = connState == ConnState.Reconnecting,
                enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
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
                visible = error != null,
                enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            err,
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Only offered on a dropped connection: the relay allows
                        // one controller, so taking it back is the user's call.
                        if (connState == ConnState.Failed) {
                            TextButton(onClick = onReconnect) { Text("重新连接") }
                        }
                    }
                }
                }
            }

            AnimatedVisibility(
                visible = connState == ConnState.Handshaking || connState == ConnState.Bootstrapping,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(160)),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在连接中继…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── FAB ──
        AnimatedVisibility(
            visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
            enter = scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.4f) + fadeIn(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
        Surface(
            shape = CircleShape,
            color = if (connected) {
                MaterialTheme.colorScheme.primary
            } else {
                tokens.chipBackground.copy(alpha = 0.87f)
            },
            shadowElevation = 6.dp,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(20.dp)
                .size(56.dp)
                .clickable(enabled = connected, onClick = onNewSession),
        ) {
            Icon(
                Icons.Default.Add, "新建任务",
                tint = if (connected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    tokens.secondaryText
                },
                modifier = Modifier.padding(15.dp),
            )
        }
        }
    }
}

/** 项目/时间 view switch plus the 更新/创建 sort chips. */
@Composable
private fun ViewToggle(
    viewMode: HomeView,
    sortBy: TaskSort,
    onViewMode: (HomeView) -> Unit,
    onSortBy: (TaskSort) -> Unit,
) {
    val tokens = LocalZcTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeView.entries.forEach { view ->
            val selected = view == viewMode
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    tokens.chipBackground
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    tokens.secondaryText
                },
                modifier = Modifier.clickable { onViewMode(view) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (view == HomeView.Project) Icons.Default.Folder else Icons.Default.Schedule,
                        null,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(view.label, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        Spacer(Modifier.weight(1f))

        TaskSort.entries.forEach { sort ->
            val selected = sort == sortBy
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    tokens.chipBackground
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    tokens.secondaryText
                },
                modifier = Modifier.clickable { onSortBy(sort) },
            ) {
                Text(
                    sort.label,
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** Custom search pill: filled, borderless, rounded. */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        // A step lighter than the header pill: `chipBackground` equals
        // `surfaceContainerHigh` in both themes, so sharing it would fuse the
        // two into one undifferentiated block.
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search, null,
                tint = tokens.secondaryText,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "搜索任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.secondaryText,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Close, "清除",
                    tint = tokens.secondaryText,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SessionGroup, collapsed: Boolean, onToggle: () -> Unit) {
    val tokens = LocalZcTokens.current
    // With several sessions running in different folders, the per-project
    // count alone does not say where the work is happening.
    val running = group.rows.count { it.isRunning }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder, null,
            tint = tokens.secondaryText,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            group.label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (running > 0) "${group.rows.size} · $running 运行中" else "${group.rows.size}",
            style = MaterialTheme.typography.labelSmall,
            color = if (running > 0) MaterialTheme.colorScheme.primary else tokens.secondaryText,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            contentDescription = if (collapsed) "展开" else "收起",
            tint = tokens.secondaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("会话标题") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "手动命名后，桌面端不会再自动改标题。",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalZcTokens.current.secondaryText,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SessionRowItem(
    row: SessionRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: (Boolean) -> Unit = {},
    onArchive: (Boolean) -> Unit = {},
    onUnread: (Boolean) -> Unit = {},
    /** Name the project inline; only the flat 时间 view needs it. */
    showWorkspace: Boolean = false,
) {
    val tokens = LocalZcTokens.current
    var menu by remember { mutableStateOf(false) }

    // The folder label belongs in front only where no section header carries
    // it; otherwise the model and the preview have the line to themselves.
    val secondary = listOfNotNull(
        row.workspaceLabel.takeIf { showWorkspace },
        row.subtitle,
    ).joinToString(" · ").ifBlank { row.workspaceLabel ?: "—" }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Dims.avatarSize)
                .clip(CircleShape)
                .background(tokens.chipBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                sessionGlyph(row),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                row.title.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                statusGlyph(row.status, row.isRunning)?.let { glyph ->
                    Icon(
                        glyph,
                        contentDescription = null,
                        tint = statusTint(row.status, row.isRunning),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        if (row.isUnread) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(6.dp))
        }

        Text(
            relativeTime(row.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.secondaryText,
        )

        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.MoreVert, "更多",
                    tint = tokens.secondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
            ZcDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (row.isPinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(Icons.Default.PushPin, null) },
                    onClick = { menu = false; onPin(!row.isPinned) },
                )
                DropdownMenuItem(
                    text = { Text("归档任务") },
                    leadingIcon = { Icon(Icons.Default.Archive, null) },
                    onClick = { menu = false; onArchive(true) },
                )
                DropdownMenuItem(
                    text = { Text(if (row.isUnread) "标记为已读" else "标记为未读") },
                    leadingIcon = { Icon(Icons.Default.MarkChatUnread, null) },
                    onClick = { menu = false; onUnread(!row.isUnread) },
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    onClick = { menu = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("关闭会话") },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun statusTint(status: String?, running: Boolean) = when {
    running -> MaterialTheme.colorScheme.primary
    status == "error" || status == "failed" -> LocalZcTokens.current.danger
    else -> LocalZcTokens.current.secondaryText
}

/**
 * Glyph for the small status slot next to the subtitle. Null when the row has
 * nothing to say — an idle/finished row would only repeat the avatar's
 * terminal icon, so the slot disappears entirely.
 */
private fun statusGlyph(status: String?, running: Boolean): ImageVector? = when {
    running -> Icons.Default.Schedule
    status == "error" || status == "failed" -> Icons.Default.ErrorOutline
    else -> null
}

private fun sessionGlyph(row: SessionRow) =
    if (row.workspacePath.isNullOrBlank()) Icons.Default.AutoAwesome else Icons.Default.Terminal

@Composable
private fun FirstRun(onAddLink: () -> Unit, onScan: () -> Unit) {
    val tokens = LocalZcTokens.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Terminal, null,
            tint = tokens.secondaryText,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("还没有连接的桌面", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "在桌面端 ZCode 侧栏点手机图标生成二维码后扫描，或手工粘贴连接地址。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.secondaryText,
        )
        Spacer(Modifier.height(20.dp))
        Row {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onScan),
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "扫描二维码",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tokens.chipBackground,
                modifier = Modifier.clickable(onClick = onAddLink),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Text(
                    "粘贴链接",
                    Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun LinkList(
    links: List<SavedLink>,
    onAddLink: () -> Unit,
    onDelete: (SavedLink) -> Unit,
    onOpen: (SavedLink) -> Unit,
) {
    val tokens = LocalZcTokens.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "已保存的连接",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.secondaryText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(links, key = { it.id }) { link ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(link) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(Dims.avatarSize)
                        .clip(CircleShape)
                        .background(tokens.chipBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        link.displayName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    )
                    Text(
                        "链接已生成 ${relativeTime(link.savedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.secondaryText,
                    )
                }
                IconButton(onClick = { onDelete(link) }) {
                    Icon(Icons.Default.Close, "删除", tint = tokens.secondaryText, modifier = Modifier.size(18.dp))
                }
                Icon(
                    Icons.Default.ChevronRight, null,
                    tint = tokens.secondaryText, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// time formatting: today / yesterday / date, matching list conventions
// ─────────────────────────────────────────────────────────────────────────────

private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
private val mmdd = SimpleDateFormat("MM-dd", Locale.getDefault())

fun relativeTime(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return hhmm.format(Date(millis))

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "昨天 ${hhmm.format(Date(millis))}"

    return mmdd.format(Date(millis))
}
