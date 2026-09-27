package dev.zcodemobile.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zcodemobile.app.data.SavedLink
import dev.zcodemobile.app.data.UiPrefs
import dev.zcodemobile.app.session.ConnState
import dev.zcodemobile.app.session.TerminalUiState
import dev.zcodemobile.app.session.ZCodeSession
import dev.zcodemobile.app.ui.ScanScreen
import dev.zcodemobile.app.ui.conversation.ConversationScreen
import dev.zcodemobile.app.ui.conversation.QueueAction
import dev.zcodemobile.app.ui.conversation.RowAction
import dev.zcodemobile.app.ui.sessions.AddLinkScreen
import dev.zcodemobile.app.ui.sessions.HomeView
import dev.zcodemobile.app.ui.sessions.SessionGroup
import dev.zcodemobile.app.ui.sessions.SessionListScreen
import dev.zcodemobile.app.ui.sessions.SessionRow
import dev.zcodemobile.app.ui.sessions.TaskSort
import dev.zcodemobile.app.ui.settings.ModelSettingsScreen
import dev.zcodemobile.app.ui.settings.SettingsScreen
import dev.zcodemobile.app.ui.sidebar.SidebarScreen
import dev.zcodemobile.app.ui.theme.ZCodeTheme
import dev.zcodemobile.protocol.AppSettings
import dev.zcodemobile.protocol.Commands
import dev.zcodemobile.protocol.SessionsIndexState
import dev.zcodemobile.protocol.ProviderSettingsView
import dev.zcodemobile.protocol.TaskIndexRow
import dev.zcodemobile.protocol.TaskIndexState
import dev.zcodemobile.protocol.TaskSummary
import dev.zcodemobile.protocol.WorkspaceConfigState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ZCodeApp
        setContent {
            ZCodeTheme {
                val vm: MainViewModel = viewModel(factory = MainViewModel.factory(app))
                AppNav(vm)
            }
        }
    }
}

private sealed interface Screen {
    /** Navigation depth, drives the slide direction of page transitions. */
    val depth: Int

    data object Sessions : Screen {
        override val depth: Int get() = 0
    }

    data class AddLink(val error: String? = null) : Screen {
        override val depth: Int get() = 1
    }

    data object Scan : Screen {
        override val depth: Int get() = 1
    }

    data class Conversation(val title: String, val workspacePath: String?) : Screen {
        override val depth: Int get() = 1
    }

    /** Full page hosting the desktop side-panel tabs (打开标签页). */
    data object Sidebar : Screen {
        override val depth: Int get() = 2
    }

    data object Settings : Screen {
        override val depth: Int get() = 1
    }

    data object ModelSettings : Screen {
        override val depth: Int get() = 2
    }
}

@Composable
private fun AppNav(vm: MainViewModel) {
    val links by vm.links.collectAsState()
    val connState by vm.connState.collectAsState()
    val error by vm.error.collectAsState()
    val reconnectAttempt by vm.reconnectAttempt.collectAsState()
    val bootstrap by vm.bootstrap.collectAsState()
    val conversation by vm.conversation.collectAsState()
    val rows by vm.rows.collectAsState()
    val settings by vm.settings.collectAsState()
    val homeGroups by vm.homeGroups.collectAsState()
    val sortBy by vm.sortBy.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val fontScale by vm.msgFontScale.collectAsState()
    val terminalState by vm.terminal.collectAsState()
    val providerSettings by vm.providerSettings.collectAsState()
    val modelSettingsError by vm.modelSettingsError.collectAsState()
    val modelTestRunning by vm.modelTestRunning.collectAsState()
    val modelTestResult by vm.modelTestResult.collectAsState()

    var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    // The sidebar is a third level (home → conversation → sidebar); this is
    // where back returns to.
    var sidebarReturn by remember { mutableStateOf<Screen.Conversation?>(null) }

    // Without this the system back button finishes the Activity from any
    // screen, which reads as the app quitting at random.
    androidx.activity.compose.BackHandler(enabled = screen !is Screen.Sessions) {
        when (screen) {
            is Screen.Sidebar -> sidebarReturn?.let { screen = it } ?: run { screen = Screen.Sessions }
            else -> screen = Screen.Sessions
        }
        sidebarReturn = null
    }

    // Page transitions: forward slides in from the right, back from the
    // left, with a short cross-fade. Each Screen carries a navigation depth
    // that picks the direction.
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val move = tween<IntOffset>(260, easing = FastOutSlowInEasing)
            val fade = tween<Float>(200)
            if (targetState.depth >= initialState.depth) {
                (slideInHorizontally(move) { it / 3 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(move) { -it / 5 } + fadeOut(fade))
            } else {
                (slideInHorizontally(move) { -it / 3 } + fadeIn(fade)) togetherWith
                    (slideOutHorizontally(move) { it / 5 } + fadeOut(fade))
            }
        },
        label = "screen",
    ) { current ->
    when (val s = current) {
        Screen.Sessions -> {
            SessionListScreen(
                links = links,
                connState = connState,
                error = error,
                reconnectAttempt = reconnectAttempt,
                onRetryReconnect = vm::retryReconnect,
                onStopReconnect = vm::stopReconnect,
                desktopVersion = bootstrap?.desktopAppVersion,
                groups = homeGroups,
                sortBy = sortBy,
                onSortBy = vm::setSortBy,
                viewMode = viewMode,
                onViewMode = vm::setViewMode,
                rows = rows,
                onAddLink = { screen = Screen.AddLink() },
                onScan = { screen = Screen.Scan },
                onDeleteLink = vm::deleteLink,
                onOpenLink = { link ->
                    vm.connect(link)
                    screen = Screen.Sessions
                },
                onOpenSession = { row ->
                    vm.openSession(row)
                    screen = Screen.Conversation(row.title, row.workspacePath)
                },
                onNewSession = {
                    vm.createSession { id ->
                        // Land in the new conversation rather than leaving the
                        // user on a list that just grew a row.
                        if (id != null) {
                            screen = Screen.Conversation("新会话", vm.bridgedWorkspace.value)
                        }
                    }
                },
                onRenameSession = vm::renameSession,
                onDeleteSession = vm::deleteSession,
                onPinSession = { row, pinned -> vm.setTaskPinned(row.sessionId, row.workspacePath, pinned) },
                onArchiveSession = { row, archived -> vm.setTaskArchived(row.sessionId, row.workspacePath, archived) },
                onUnreadSession = { row, unread -> vm.setTaskUnread(row.sessionId, row.workspacePath, unread) },
                onOpenSettings = { screen = Screen.Settings },
                onReconnect = vm::reconnect,
                onDisconnect = vm::disconnect,
            )
        }

        is Screen.AddLink -> AddLinkScreen(
            onBack = { screen = Screen.Sessions },
            onScan = { screen = Screen.Scan },
            onSubmit = { raw ->
                val err = vm.addLink(raw)
                screen = if (err == null) {
                    // Newly added links are connected immediately — landing back
                    // on a list with an unconnected row would be a dead end.
                    vm.connectLatest()
                    Screen.Sessions
                } else {
                    Screen.AddLink(err)
                }
            },
            parseError = s.error,
        )

        Screen.Scan -> ScanScreen(
            onBack = { screen = Screen.Sessions },
            onManual = { screen = Screen.AddLink() },
            onScanned = { raw ->
                val err = vm.addLink(raw)
                if (err == null) {
                    vm.connectLatest()
                    screen = Screen.Sessions
                } else {
                    screen = Screen.AddLink(err)
                }
            },
        )

        is Screen.Conversation -> {
            // Attachments go through the system picker; the app never asks for
            // broad storage permission.
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { vm.attach(it) } }

            ConversationScreen(
                title = s.title,
                workspace = s.workspacePath,
                state = conversation,
                connState = connState,
                reconnectAttempt = reconnectAttempt,
                onRetryReconnect = vm::retryReconnect,
                onStopReconnect = vm::stopReconnect,
                fontScale = fontScale,
                onSetMsgSize = vm::setMsgFontScale,
                onBack = { screen = Screen.Sessions },
                onOpenSidebar = {
                    sidebarReturn = s
                    screen = Screen.Sidebar
                },
                onSend = vm::sendMessage,
                onSendWithQueueDecision = vm::sendWithQueueDecision,
                onStop = vm::stop,
                onResolve = vm::resolve,
                onSnooze = vm::snoozeInteraction,
                onLoadOlder = vm::loadOlder,
                onAttach = { picker.launch(arrayOf("*/*")) },
                onRemoveAttachment = vm::removeAttachment,
                onOpenModelPicker = vm::loadModels,
                onPickModel = vm::pickModel,
                onPickMode = vm::pickMode,
                onPickThought = vm::pickThought,
                onPickBranch = vm::pickBranch,
                onRowAction = vm::onRowAction,
                onQueueAction = vm::onQueueAction,
                onSetGoal = vm::setGoal,
                onGoalAction = vm::goalAction,
                onCompact = vm::compact,
                onCancelBackgroundWork = vm::cancelBackgroundWork,
            )
        }

        Screen.Sidebar -> SidebarScreen(
            onBack = {
                val ret = sidebarReturn
                sidebarReturn = null
                screen = ret ?: Screen.Sessions
            },
            terminal = terminalState,
            git = conversation.git,
            onStartTerminal = vm::startTerminal,
            onTerminalWrite = vm::terminalWrite,
            onTerminalStop = vm::stopTerminal,
            onRefreshGit = vm::refreshGit,
        )

        Screen.ModelSettings -> ModelSettingsScreen(
            view = providerSettings,
            loading = providerSettings == null,
            error = modelSettingsError,
            testRunningKey = modelTestRunning,
            testResult = modelTestResult,
            onBack = { screen = Screen.Settings },
            onLoad = vm::loadModelSettings,
            onAddProvider = vm::addProvider,
            onDeleteProvider = vm::deleteProvider,
            onAddModel = vm::addProviderModel,
            onDeleteModel = vm::deleteProviderModel,
            onSetModelEnabled = vm::setProviderModelEnabled,
            onTestModel = vm::testProviderModel,
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            onOpenModels = { screen = Screen.ModelSettings },
            onBack = { screen = Screen.Sessions },
            onLoad = vm::loadSettings,
            onUpdate = vm::updateSetting,
        )
        }
    }
}

class MainViewModel(private val app: ZCodeApp) : ViewModel() {

    private val session = ZCodeSession(
        scope = viewModelScope,
        webSocketFactory = app.webSockets,
        // Never log the link or its hash — it is a temporary key to a desktop.
        // Failures used to be swallowed here; surface them so a silent feature
        // (a hidden branch row, an empty model list) is diagnosable.
        logger = { android.util.Log.d("ZCodeSession", it) },
    )

    val links: StateFlow<List<SavedLink>> = app.linkStore.links
    val connState: StateFlow<ConnState> = session.state
    val error: StateFlow<String?> = session.error

    /** Attempt counter while the auto-reconnect loop is running. */
    val reconnectAttempt: StateFlow<Int> = session.reconnectAttempt
    val bootstrap = session.bootstrap
    val conversation = session.conversation
    val settings: StateFlow<AppSettings> = session.settings

    /** Window-wide task push (`controller/tasks-index`). */
    val taskIndex: StateFlow<TaskIndexState> = session.taskIndex

    private val _bridgedWorkspace = MutableStateFlow<String?>(null)
    val bridgedWorkspace: StateFlow<String?> = _bridgedWorkspace.asStateFlow()

    private val index: StateFlow<SessionsIndexState> = session.sessionsIndex

    @Suppress("unused")
    val workspaceConfig: StateFlow<WorkspaceConfigState> = session.workspaceConfig

    /** Filter text, owned here so the row projection can honour it. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Home timeline ordering. */
    private val _sortBy = MutableStateFlow(TaskSort.Updated)
    val sortBy: StateFlow<TaskSort> = _sortBy.asStateFlow()

    fun setSortBy(sort: TaskSort) {
        _sortBy.value = sort
    }

    /** Home organisation: grouped by project or one flat timeline. */
    private val _viewMode = MutableStateFlow(HomeView.Project)
    val viewMode: StateFlow<HomeView> = _viewMode.asStateFlow()

    fun setViewMode(view: HomeView) {
        _viewMode.value = view
    }

    /** Message text scale (文字大小), persisted locally. */
    val msgFontScale: StateFlow<Float> = app.uiPrefs.msgFontScale

    fun setMsgFontScale(scale: Float) {
        app.uiPrefs.setMsgFontScale(scale)
    }

    // ── side panel (终端 / 审查) ──────────────────────────────────────────

    val terminal: StateFlow<TerminalUiState> = session.terminal

    fun startTerminal() {
        viewModelScope.launch {
            val cwd = _bridgedWorkspace.value ?: return@launch
            runCatching { session.startTerminal(cwd) }
                .onFailure { session.reportError("终端连接失败：" + (it.message ?: "")) }
        }
    }

    fun terminalWrite(data: String) {
        viewModelScope.launch { session.terminalWrite(data) }
    }

    fun stopTerminal() {
        session.stopTerminal()
    }

    fun refreshGit() {
        viewModelScope.launch { session.loadGit() }
    }

    // ── model settings (provider-settings) ───────────────────────────────

    val providerSettings: StateFlow<ProviderSettingsView?> = session.providerSettings

    private val _modelSettingsError = MutableStateFlow<String?>(null)
    val modelSettingsError: StateFlow<String?> = _modelSettingsError.asStateFlow()

    /** Key is `providerId/modelId`; value is the human-readable outcome. */
    private val _modelTestResult = MutableStateFlow<Pair<String, String>?>(null)
    val modelTestResult: StateFlow<Pair<String, String>?> = _modelTestResult.asStateFlow()

    private val _modelTestRunning = MutableStateFlow<String?>(null)
    val modelTestRunning: StateFlow<String?> = _modelTestRunning.asStateFlow()

    fun loadModelSettings() {
        viewModelScope.launch {
            _modelSettingsError.value = null
            runCatching { session.loadProviderSettings() }
                .onFailure { _modelSettingsError.value = "读取失败：" + (it.message ?: "") }
        }
    }

    fun addProvider(name: String, apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            runCatching { session.addProvider(name, apiKey, baseUrl) }
                .onFailure { _modelSettingsError.value = "创建失败：" + (it.message ?: "") }
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            runCatching { session.deleteProvider(providerId) }
                .onFailure { _modelSettingsError.value = "删除失败：" + (it.message ?: "") }
        }
    }

    fun addProviderModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            runCatching { session.addProviderModel(providerId, modelId) }
                .onFailure { _modelSettingsError.value = "添加模型失败：" + (it.message ?: "") }
        }
    }

    fun deleteProviderModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            runCatching { session.deleteProviderModel(providerId, modelId) }
                .onFailure { _modelSettingsError.value = "删除模型失败：" + (it.message ?: "") }
        }
    }

    fun setProviderModelEnabled(providerId: String, modelId: String, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { session.setProviderModelEnabled(providerId, modelId, enabled) }
                .onFailure { _modelSettingsError.value = "切换失败：" + (it.message ?: "") }
        }
    }

    /**
     * Connectivity probe can take over 25 seconds — it runs a real request
     * against the provider — so the row shows a spinner until it settles.
     */
    fun testProviderModel(providerId: String, modelId: String) {
        val key = providerId + "/" + modelId
        _modelTestRunning.value = key
        viewModelScope.launch {
            val outcome = runCatching { session.testProviderModel(providerId, modelId) }
                .fold({ it.message }, { "连接失败：" + (it.message ?: "") })
            _modelTestResult.value = key to outcome
            _modelTestRunning.value = null
        }
    }

    /**
     * Unified list projection.
     *
     * Two sources answer different questions: the bootstrap task list spans
     * every workspace but is a point-in-time snapshot, while the sessions-index
     * stream is live but only covers the bridged workspace. Prefer the live one
     * where it applies and fall back to the snapshot elsewhere, rather than
     * showing a stale list for the workspace the user is actually in.
     */
    val rows: StateFlow<List<SessionRow>> = combine(
        index, bootstrap, _query, _bridgedWorkspace,
    ) { idx, boot, query, bridged ->
        buildRows(idx, boot?.tasks.orEmpty(), query, bridged)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Home screen projection: tasks grouped into project sections, ordered as
     * a timeline.
     *
     * The task-index push replaces the fallback per workspace — a workspace it
     * covers gets live membership (archived tasks excluded); anything else
     * keeps the merged bootstrap/index rows.
     */
    val homeGroups: StateFlow<List<SessionGroup>> = combine(
        session.taskIndex, rows, _sortBy,
    ) { idx, baseRows, sort ->
        buildHomeGroups(idx, baseRows, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())


    init {
        // Reconnect on launch: the saved link is the user's only intent signal,
        // and landing on a list that requires an extra tap to become useful
        // reads as a broken screen.
        viewModelScope.launch {
            app.linkStore.links.value.firstOrNull()?.let { connect(it) }
        }
    }

    /**
     * Unified list projection.
     *
     * Two sources answer different questions: the bootstrap task list spans
     * every workspace but is a point-in-time snapshot, while the sessions-index
     * stream is live but only covers the bridged workspace. Prefer the live one
     * where it applies and fall back to the snapshot elsewhere, rather than
     * showing a stale list for the workspace the user is actually in.
     */
    fun setQuery(text: String) { _query.value = text }

    fun addLink(raw: String): String? {
        val link = try {
            dev.zcodemobile.protocol.RemoteLink.parse(raw)
        } catch (e: Exception) {
            return e.message ?: "链接格式无法识别"
        }
        app.linkStore.save(link, null)
        return null
    }

    fun connectLatest() {
        app.linkStore.links.value.firstOrNull()?.let { connect(it) }
    }

    fun connect(link: SavedLink) {
        viewModelScope.launch {
            runCatching { session.connect(link.link) }
                .onSuccess {
                    // The desktop only accepts one bridged workspace at a time;
                    // bridge the one it reports as active so the task list fills in.
                    val boot = it
                    val path = boot.activeWorkspaceKey
                        ?: boot.workspaces.firstOrNull()?.workspacePath
                    if (path != null) {
                        _bridgedWorkspace.value = path
                        runCatching { session.openWorkspace(path, boot.activeTaskId) }
                    }
                }
        }
    }

    fun deleteLink(link: SavedLink) = app.linkStore.remove(link.id)

    /** Skip the auto-reconnect backoff wait and reconnect immediately. */
    fun retryReconnect() = session.retryNow()

    /** Stop the auto-reconnect loop; manual 重新连接 stays available. */
    fun stopReconnect() = session.stopReconnect()

    /**
     * Retry the last link after a drop.
     *
     * The relay permits one remote controller, so a disconnect usually means
     * someone else claimed it; this is the user's explicit "take it back".
     */
    fun reconnect() {
        viewModelScope.launch {
            session.reconnect()?.let { boot ->
                val path = boot.activeWorkspaceKey
                    ?: boot.workspaces.firstOrNull()?.workspacePath
                if (path != null) {
                    _bridgedWorkspace.value = path
                    runCatching { session.openWorkspace(path, boot.activeTaskId) }
                }
            }
        }
    }

    fun openSession(row: SessionRow) {
        viewModelScope.launch {
            // Blank the previous projection first: if the open fails below,
            // the screen must not keep showing another session's transcript.
            session.beginOpenSession(row.sessionId)
            runCatching {
                val path = row.workspacePath
                if (path != null && path != _bridgedWorkspace.value) {
                    // Another workspace: the bridge is per-workspace, so the
                    // connection is rebuilt rather than re-opened in place.
                    session.rebridge(path, row.sessionId)
                    _bridgedWorkspace.value = path
                }
                session.subscribe(row.sessionId)
            }.onFailure {
                session.reportError("打开会话失败：${it.message}")
            }
        }
    }

    /**
     * Create a session, then open it.
     *
     * The id only exists in the ack, so the navigation happens in the callback
     * rather than optimistically.
     */
    fun createSession(onCreated: (String?) -> Unit) {
        viewModelScope.launch {
            val id = session.createSession()
            if (id != null) {
                session.beginOpenSession(id)
                runCatching { session.subscribe(id) }
                    .onFailure { session.reportError("打开新会话失败：${it.message}") }
            }
            onCreated(id)
        }
    }

    /**
     * Rename through the task index (`zcode-task.renameTask`) — the path the
     * web sidebar uses, so it works for any task without a CAS baseline.
     */
    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch {
            val ws = workspaceFor(sessionId) ?: return@launch
            session.renameTaskByIndex(sessionId, ws, title)
        }
    }

    /** Close a session (history survives; not a record delete). */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch { session.deleteSession(sessionId) }
    }

    fun setTaskPinned(sessionId: String, workspacePath: String?, pinned: Boolean) {
        val ws = workspacePath ?: workspaceFor(sessionId) ?: return
        viewModelScope.launch { session.setTaskPinned(sessionId, ws, pinned) }
    }

    fun setTaskArchived(sessionId: String, workspacePath: String?, archived: Boolean) {
        val ws = workspacePath ?: workspaceFor(sessionId) ?: return
        viewModelScope.launch { session.setTaskArchived(sessionId, ws, archived) }
    }

    fun setTaskUnread(sessionId: String, workspacePath: String?, unread: Boolean) {
        val ws = workspacePath ?: workspaceFor(sessionId) ?: return
        viewModelScope.launch { session.setTaskUnread(sessionId, ws, unread) }
    }

    /** Workspace of a task, from whichever projection currently has it. */
    private fun workspaceFor(sessionId: String): String? =
        taskIndex.value.byId(sessionId)?.workspacePath
            ?: rows.value.firstOrNull { it.sessionId == sessionId }?.workspacePath

    fun sendMessage(text: String, startNow: Boolean, planEnabled: Boolean? = null) {
        viewModelScope.launch {
            session.sendMessage(
                text = text,
                delivery = if (startNow) Commands.Delivery.StartNow else null,
                planEnabled = planEnabled,
            )
        }
    }

    /** Answer a paused-queue prompt: clear it and send, or keep it and send. */
    fun sendWithQueueDecision(text: String, clearQueue: Boolean) {
        viewModelScope.launch {
            session.sendMessage(
                text = text,
                delivery = Commands.Delivery.StartNow,
                heldQueueDisposition = if (clearQueue) {
                    Commands.HeldQueueDisposition.ClearQueueAndSend
                } else {
                    Commands.HeldQueueDisposition.KeepQueueAndSend
                },
                expectedHeldQueueItemIds = conversation.value.conversation.queue.items
                    .map { it.queueItemId },
            )
        }
    }

    fun resolve(interactionId: String, optionId: String?) {
        viewModelScope.launch { session.resolve(interactionId, optionId = optionId) }
    }

    fun snoozeInteraction(interactionId: String) {
        viewModelScope.launch { session.snoozeInteraction(interactionId) }
    }

    fun stop() {
        viewModelScope.launch { session.stop() }
    }

    fun loadOlder() {
        viewModelScope.launch { session.loadOlder() }
    }

    fun compact() {
        viewModelScope.launch { session.compact() }
    }

    fun setGoal(text: String) {
        viewModelScope.launch { session.setGoal(text) }
    }

    fun goalAction(pause: Boolean) {
        viewModelScope.launch { if (pause) session.pauseGoal() else session.resumeGoal() }
    }

    fun loadPlans() {
        viewModelScope.launch { session.loadPlans() }
    }

    fun cancelBackgroundWork(workId: String) {
        viewModelScope.launch { session.cancelBackgroundWork(workId) }
    }

    /** Route one transcript row action to its command. */
    fun onRowAction(rowId: Long, entityId: String, action: RowAction, text: String?) {
        viewModelScope.launch {
            when (action) {
                RowAction.Copy -> Unit // handled in the UI layer
                RowAction.Edit ->
                    session.editUserQuery(
                        rowId, entityId, text.orEmpty(),
                        workspaceMode = Commands.WorkspaceMode.Preserve,
                    )
                RowAction.EditAndRewind ->
                    session.editUserQuery(
                        rowId, entityId, text.orEmpty(),
                        workspaceMode = Commands.WorkspaceMode.Rewind,
                    )
                RowAction.Retry -> session.retryTurn(rowId, entityId)
                RowAction.Fork -> session.forkAssistant(rowId, entityId)
                RowAction.Like ->
                    session.setFeedback(rowId, entityId, Commands.Feedback.Like)
                RowAction.Dislike ->
                    session.setFeedback(rowId, entityId, Commands.Feedback.Dislike)
                RowAction.ClearFeedback ->
                    session.setFeedback(rowId, entityId, null)
                RowAction.RewindFiles -> session.applyFileRewind(rowId, entityId)
            }
        }
    }

    /** Route one queue action to its command. */
    fun onQueueAction(action: QueueAction, queueItemId: String, text: String? = null) {
        viewModelScope.launch {
            when (action) {
                QueueAction.SendNow -> session.sendQueuedNow(queueItemId)
                QueueAction.Edit -> text?.let { session.editQueueItem(queueItemId, it) }
                QueueAction.MoveUp -> session.reorderQueueItem(queueItemId, beforeIdFor(queueItemId))
                QueueAction.MoveDown -> {
                    val items = conversation.value.conversation.queue.items
                    val i = items.indexOfFirst { it.queueItemId == queueItemId }
                    if (i in 0 until items.lastIndex) {
                        // Moving down one slot = re-inserting before the item
                        // after the neighbour (tail when the neighbour is last).
                        session.reorderQueueItem(
                            queueItemId,
                            items.getOrNull(i + 2)?.queueItemId,
                        )
                    }
                }
                QueueAction.Delete -> session.deleteQueueItem(queueItemId)
                QueueAction.ResumeDrain -> session.setAutoDrain(true)
                QueueAction.PauseDrain -> session.setAutoDrain(false)
            }
        }
    }

    /** Id of the item that should precede [queueItemId] after a move up. */
    private fun beforeIdFor(queueItemId: String): String? {
        val items = conversation.value.conversation.queue.items
        val i = items.indexOfFirst { it.queueItemId == queueItemId }
        return if (i <= 1) null else items[i - 2].queueItemId
    }

    /** Read a picked document and upload it as a staged attachment. */
    fun attach(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                val resolver = app.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = displayName(uri) ?: "attachment"
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                session.attachFile(name, mime, bytes)
            }.onFailure { session.reportError("附件读取失败：${it.message}") }
        }
    }

    fun removeAttachment(ref: String) = session.removeAttachment(ref)

    fun loadModels() {
        viewModelScope.launch { session.loadModels() }
    }

    fun pickModel(option: dev.zcodemobile.protocol.ModelOption) {
        viewModelScope.launch { session.switchModel(option) }
    }

    fun pickBranch(branch: String) {
        viewModelScope.launch { session.switchBranch(branch) }
    }

    fun pickThought(level: String) {
        viewModelScope.launch { session.switchThought(level) }
    }

    fun pickMode(mode: Commands.Mode) {
        viewModelScope.launch { session.switchMode(mode) }
    }

    fun setFollowup(followup: Commands.Followup) {
        viewModelScope.launch { session.setFollowup(followup) }
    }

    fun loadSettings() {
        viewModelScope.launch { session.loadSettings() }
    }

    fun updateSetting(key: String, value: Any?) {
        viewModelScope.launch { session.updateSetting(key, value) }
    }

    private fun displayName(uri: android.net.Uri): String? =
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    fun disconnect() {
        session.disconnect()
        _bridgedWorkspace.value = null
    }

    override fun onCleared() {
        session.disconnect()
        super.onCleared()
    }

    companion object {
        fun factory(app: ZCodeApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(app) as T
            }

        /**
         * Merge the live index with the bootstrap snapshot into one list.
         *
         * The live entries win for the bridged workspace because they carry the
         * current phase and preview; the snapshot fills in every other
         * workspace, which has no stream open.
         */
        private fun buildRows(
            index: SessionsIndexState,
            tasks: List<TaskSummary>,
            query: String,
            bridged: String?,
        ): List<SessionRow> {
            val live = index.sessions
            val liveApplies = live.isNotEmpty() && bridged != null

            val rows = if (liveApplies) {
                val byId = tasks.associateBy { it.taskId }
                live.map { e ->
                    val task = byId[e.sessionId]
                    SessionRow(
                        sessionId = e.sessionId,
                        title = e.displayTitle,
                        workspacePath = e.workspaceId,
                        workspaceLabel = task?.workspaceLabel,
                        subtitle = listOfNotNull(
                            task?.let { prettyModel(it.model) ?: it.provider },
                            // The preview is model-authored Markdown, so it
                            // arrives with headings and bullets attached.
                            previewSnippet(e.lastAssistantPreview),
                        ).joinToString(" · ").ifBlank { task?.workspaceLabel },
                        status = e.phase,
                        isRunning = e.isRunning,
                        updatedAt = e.lastActivityAt ?: task?.updatedAt,
                        hasCustomTitle = e.hasCustomTitle,
                        live = true,
                    )
                }
            } else {
                tasks.map { t ->
                    SessionRow(
                        sessionId = t.taskId,
                        title = t.title.ifBlank { "(无标题)" },
                        workspacePath = t.workspacePath,
                        workspaceLabel = t.workspaceLabel,
                        subtitle = prettyModel(t.model) ?: t.provider,
                        status = t.displayStatus,
                        isRunning = t.displayStatus == "running",
                        updatedAt = t.updatedAt,
                        hasCustomTitle = false,
                        live = false,
                    )
                }
            }

            val q = query.trim()
            return if (q.isEmpty()) {
                rows.sortedByDescending { it.updatedAt ?: 0L }
            } else {
                rows.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.workspaceLabel?.contains(q, ignoreCase = true) == true ||
                        it.subtitle?.contains(q, ignoreCase = true) == true
                }.sortedByDescending { it.updatedAt ?: 0L }
            }
        }

        /**
         * Group tasks into project sections for the home timeline.
         *
         * A workspace the task-index push covers is taken wholesale from it
         * (live membership, archived excluded); anything else falls back to
         * the merged bootstrap/index rows.
         */
        private fun buildHomeGroups(
            index: TaskIndexState,
            fallback: List<SessionRow>,
            sort: TaskSort,
        ): List<SessionGroup> {
            val pushByWs = index.rows.values
                .filter { !it.archived }
                .groupBy { it.workspacePath }
            val fallbackByWs = fallback.groupBy { it.workspacePath.orEmpty() }
            val paths = (pushByWs.keys + fallbackByWs.keys).filter { it.isNotBlank() }

            return paths.map { ws ->
                val rows = if (pushByWs.containsKey(ws)) {
                    pushByWs[ws].orEmpty().map { it.toSessionRow() }
                } else {
                    fallbackByWs[ws].orEmpty()
                }
                SessionGroup(
                    workspacePath = ws,
                    label = ws.substringAfterLast('\\').ifBlank { ws },
                    rows = sortRows(rows, sort),
                )
            }.sortedByDescending { group ->
                group.rows.maxOfOrNull { it.updatedAt ?: 0L } ?: 0L
            }
        }

        /**
         * The wire model id is a routing path like
         * `account:bigmodel-start-plan/GLM-5.3-Flash`; only the last segment
         * names the model, the rest is provider plumbing the row has no room
         * for. Falls back to the input when no `/` is present.
         */
        private fun prettyModel(model: String?): String? =
            model?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: model

        private fun sortRows(rows: List<SessionRow>, sort: TaskSort): List<SessionRow> =
            when (sort) {
                TaskSort.Updated -> rows.sortedByDescending { it.updatedAt ?: 0L }
                TaskSort.Created -> rows.sortedByDescending { it.createdAt ?: it.updatedAt ?: 0L }
            }

        /** Project one task-index row into the shared list row shape. */
        private fun TaskIndexRow.toSessionRow(): SessionRow = SessionRow(
            sessionId = taskId,
            title = meta.displayTitle,
            workspacePath = workspacePath,
            workspaceLabel = null,
            subtitle = prettyModel(meta.model) ?: meta.provider,
            status = liveStatus ?: meta.status,
            isRunning = isRunning,
            updatedAt = meta.updatedAt,
            createdAt = meta.createdAt,
            hasCustomTitle = meta.titleOverridden,
            live = true,
            isUnread = isUnread,
        )

        /**
         * Turn an assistant preview into one readable list line.
         *
         * The host sends up to 120 characters of the model's own Markdown, so
         * the raw value starts with things like `## 结果` or `- 第一项` and reads
         * as noise in a one-line subtitle. Take the first line with actual
         * prose, drop the leading Markdown markers, and cap it.
         */
        private fun previewSnippet(preview: String?): String? {
            val first = preview
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { line ->
                    line.isNotBlank() &&
                        line.any { it.isLetterOrDigit() } &&
                        // A bare code fence or horizontal rule carries no meaning.
                        !line.all { it in "#-*_> \t`~" }
                }
                ?: return null

            val cleaned = first
                .trimStart('#', '-', '*', '>', ' ', '\t')
                .replace(Regex("`+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.isEmpty()) return null
            return if (cleaned.length <= SNIPPET_MAX) {
                cleaned
            } else {
                cleaned.take(SNIPPET_MAX).trimEnd() + "…"
            }
        }

        /** Fits the subtitle inside one ellipsised line. */
        private const val SNIPPET_MAX = 48
    }
}
