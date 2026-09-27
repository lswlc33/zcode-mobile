package dev.zcodemobile.shared.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll







import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zcodemobile.shared.session.TerminalUiState
import dev.zcodemobile.shared.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.GitRepoInfo
import dev.zcodemobile.shared.ui.ZcIcons

/**
 * The desktop's right side panel, as a page: the official start screen is the
 * tab picker (打开标签页)， and each chosen tab fills the page. Terminal is a
 * real remote PTY; review reads the workspace git state; 辅助对话 and 浏览器
 * have no mobile implementation yet and say so instead of faking it.
 */
sealed interface SidePanelTab {
    data object SideChat : SidePanelTab
    data object Review : SidePanelTab
    data object Terminal : SidePanelTab
    data object Browser : SidePanelTab
}

@Composable
fun SidebarScreen(
    onBack: () -> Unit,
    terminal: TerminalUiState,
    git: GitRepoInfo,
    onStartTerminal: () -> Unit,
    onTerminalWrite: (String) -> Unit,
    onTerminalStop: () -> Unit,
    onRefreshGit: () -> Unit,
) {
    var tab by remember { mutableStateOf<SidePanelTab?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        if (tab == null) {
            TabPicker(
                onPick = { tab = it },
                onBack = onBack,
            )
        } else {
            TabHeader(title = tabTitle(tab!!)) { sub ->
                if (sub) {
                    tab = null
                } else {
                    if (tab == SidePanelTab.Terminal) onTerminalStop()
                    onBack()
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    SidePanelTab.Terminal -> TerminalTab(
                        state = terminal,
                        onStart = onStartTerminal,
                        onWrite = onTerminalWrite,
                    )
                    SidePanelTab.Review -> ReviewTab(git = git, onRefresh = onRefreshGit)
                    SidePanelTab.SideChat -> ComingSoonTab(
                        title = "辅助对话",
                        note = "桌面的侧聊会话流尚未开放手机端。",
                    )
                    else -> ComingSoonTab(
                        title = "浏览器",
                        note = "远程浏览器标签尚未开放手机端。",
                    )
                }
            }
        }
    }
}

private fun tabTitle(tab: SidePanelTab): String = when (tab) {
    SidePanelTab.SideChat -> "辅助对话"
    SidePanelTab.Review -> "审查"
    SidePanelTab.Terminal -> "终端"
    SidePanelTab.Browser -> "浏览器"
}

/** The official start screen: 打开标签页 with the four tab choices. */
@Composable
private fun TabPicker(onPick: (SidePanelTab) -> Unit, onBack: () -> Unit) {
    val tokens = LocalZcTokens.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ZcIcons.Default.ArrowBack, "返回", modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "打开标签页",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "选择要在侧边面板中打开的标签。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.secondaryText,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(28.dp))

        TabOption(ZcIcons.Default.Chat, "辅助对话") { onPick(SidePanelTab.SideChat) }
        Spacer(Modifier.height(12.dp))
        TabOption(ZcIcons.Default.FactCheck, "审查") { onPick(SidePanelTab.Review) }
        Spacer(Modifier.height(12.dp))
        TabOption(ZcIcons.Default.Terminal, "终端") { onPick(SidePanelTab.Terminal) }
        Spacer(Modifier.height(12.dp))
        TabOption(ZcIcons.Default.Public, "浏览器") { onPick(SidePanelTab.Browser) }

        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
private fun TabOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tokens.secondaryText, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TabHeader(title: String, onNavigate: (backToPicker: Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable { onNavigate(true) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZcIcons.Default.ArrowBack, "返回标签页", modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        // Close the panel entirely (matches the desktop panel's 收起).
        TextAction("关闭") { onNavigate(false) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    )
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 终端 — real remote PTY
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTab(
    state: TerminalUiState,
    onStart: () -> Unit,
    onWrite: (String) -> Unit,
) {
    val tokens = LocalZcTokens.current
    var input by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Every entry to the tab starts a fresh shell: startTerminal() stops the
    // previous one first, so the view never shows a stale orphan session.
    LaunchedEffect(Unit) { onStart() }

    LaunchedEffect(state.output) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        if (last != null && last.index >= info.totalItemsCount - 3) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(tokens.codeBackground),
            contentPadding = PaddingValues(10.dp),
        ) {
            val lines = state.output.split('\n')
            items(lines.size) { i ->
                Text(
                    lines[i],
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }

        if (state.exited) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "会话已结束" + (state.exitCode?.let { "（退出码 $it）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.secondaryText,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction("重新打开") { onStart() }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(
                        "输入命令…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.secondaryText,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    ),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (input.isNotEmpty() && state.running) {
                    onWrite("$input\r")
                    input = ""
                }
            }) {
                Icon(
                    ZcIcons.Default.ArrowUpward, "执行",
                    tint = if (input.isNotEmpty() && state.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        tokens.secondaryText
                    },
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 审查 — workspace git state
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewTab(git: GitRepoInfo, onRefresh: () -> Unit) {
    val tokens = LocalZcTokens.current
    if (!git.isRepository) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                ZcIcons.Default.FactCheck, null,
                tint = tokens.secondaryText,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "当前 workspace 不在 Git 仓库中",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.secondaryText,
            )
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("仓库状态", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextAction("刷新") { onRefresh() }
        }
        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                ReviewLine("分支", git.branchLabel)
                git.repoName?.let { ReviewLine("仓库", it) }
                if (git.isDirty) ReviewLine("工作区", "有未提交改动")
                if (git.ahead > 0 || git.behind > 0) {
                    ReviewLine(
                        "同步",
                        buildString {
                            if (git.ahead > 0) append("领先 ${git.ahead} 个提交")
                            if (git.ahead > 0 && git.behind > 0) append(" · ")
                            if (git.behind > 0) append("落后 ${git.behind} 个提交")
                        },
                    )
                }
            }
        }

        if (git.branches.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("本地分支", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    git.branches.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (b.isCurrent) {
                                Text(
                                    "当前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    val tokens = LocalZcTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.secondaryText,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ComingSoonTab(title: String, note: String) {
    val tokens = LocalZcTokens.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.secondaryText,
        )
    }
}
