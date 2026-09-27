package dev.zcodemobile.shared.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zcodemobile.shared.ui.sessions.SessionListScreen
import dev.zcodemobile.shared.ui.theme.ZCodeTheme

/**
 * The iOS root screen. Deliberately minimal for this milestone: it renders
 * the shared home list against the live session state — add-link/scan flows
 * and deeper navigation are Android-shell features that iOS grows next; the
 * connection, list projection and transcript pipeline underneath are fully
 * shared and live.
 */
@Composable
fun IosRoot(model: AppModel) {
    ZCodeTheme {
        val links by model.linkStore.links.collectAsState()
        val connState by model.session.state.collectAsState()
        val error by model.session.error.collectAsState()

        Box(Modifier.fillMaxSize()) {
            if (links.isEmpty()) {
                Text(
                    "在桌面端生成远程链接后，通过 TestFlight 版本扫码或粘贴链接添加。",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                SessionListScreen(
                    links = links,
                    connState = connState,
                    error = error,
                    reconnectAttempt = model.session.reconnectAttempt.collectAsState().value,
                    onRetryReconnect = model.session::retryNow,
                    onStopReconnect = model.session::stopReconnect,
                    desktopVersion = null,
                    groups = emptyList(),
                    sortBy = dev.zcodemobile.shared.ui.sessions.TaskSort.Updated,
                    onSortBy = {},
                    viewMode = dev.zcodemobile.shared.ui.sessions.HomeView.Project,
                    onViewMode = {},
                    rows = emptyList(),
                    onAddLink = {},
                    onScan = {},
                    onDeleteLink = { model.linkStore.remove(it.id) },
                    onOpenLink = { model.scope.launchConnect(model, it) },
                    onOpenSession = {},
                    onNewSession = {},
                    onRenameSession = { _, _ -> },
                    onDeleteSession = {},
                    onPinSession = { _, _, _ -> },
                    onArchiveSession = { _, _, _ -> },
                    onUnreadSession = { _, _, _ -> },
                    onOpenSettings = {},
                    onReconnect = {},
                    onDisconnect = {},
                )
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchConnect(model: AppModel, link: dev.zcodemobile.shared.data.SavedLink) {
    launch {
        runCatching { model.session.connect(link.link) }
    }
}
