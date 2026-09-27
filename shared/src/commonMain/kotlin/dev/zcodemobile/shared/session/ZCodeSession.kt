package dev.zcodemobile.shared.session

import dev.zcodemobile.protocol.AppSettings
import dev.zcodemobile.protocol.AttachmentRef
import dev.zcodemobile.protocol.BootstrapResult
import dev.zcodemobile.protocol.BridgeInfo
import dev.zcodemobile.protocol.ChannelClient
import dev.zcodemobile.protocol.CommandAck
import dev.zcodemobile.protocol.Commands
import dev.zcodemobile.protocol.ConversationApi
import dev.zcodemobile.protocol.ConversationReducer
import dev.zcodemobile.protocol.ConversationState
import dev.zcodemobile.protocol.GitRepoInfo
import dev.zcodemobile.protocol.Json
import dev.zcodemobile.protocol.ModelOption
import dev.zcodemobile.protocol.PlanEntry
import dev.zcodemobile.protocol.ProviderSettingsService
import dev.zcodemobile.protocol.ProviderSettingsView
import dev.zcodemobile.protocol.ProviderTestResult
import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.protocol.RelayTransport
import dev.zcodemobile.protocol.SessionsIndex
import dev.zcodemobile.protocol.SessionsIndexState
import dev.zcodemobile.protocol.TaskIndex
import dev.zcodemobile.protocol.TaskIndexFrame
import dev.zcodemobile.protocol.TaskIndexState
import dev.zcodemobile.protocol.TaskService
import dev.zcodemobile.protocol.TasksIndexSubscription
import dev.zcodemobile.protocol.TerminalService
import dev.zcodemobile.protocol.WebSocketFactory
import dev.zcodemobile.protocol.WorkspaceConfig
import dev.zcodemobile.protocol.WorkspaceConfigState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conversation state plus the local flags the UI needs.
 *
 * The protocol-level projection lives in [ConversationReducer]; this wrapper
 * only adds client-local concerns (in-flight command, last ack) so the
 * reducer stays a pure function that can be unit tested.
 */
data class ConversationUiState(
    val conversation: ConversationState = ConversationState(),
    val sending: Boolean = false,
    val lastCommand: String? = null,
    val lastError: String? = null,
    /** Committed uploads waiting to ride along with the next message. */
    val attachments: List<AttachmentRef> = emptyList(),
    val uploading: Boolean = false,
    val uploadProgress: String? = null,
    /** Selectable models on the desktop, loaded on demand. */
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    /** Repository and branch of the bridged workspace. */
    val git: GitRepoInfo = GitRepoInfo(),
    /** Terminal plan catalog for the current branch. */
    val plans: List<PlanEntry> = emptyList(),
    val plansLoading: Boolean = false,
    /** True while a non-send command is in flight, so controls can disable. */
    val busy: Boolean = false,
)

/** Remote shell panel state (the 侧栏 终端 tab). */
data class TerminalUiState(
    val id: String? = null,
    val cwd: String? = null,
    val output: String = "",
    val exited: Boolean = false,
    val exitCode: Int? = null,
) {
    val running: Boolean get() = id != null && !exited
}

enum class ConnState { Idle, Handshaking, Bootstrapping, Bridging, Ready, Reconnecting, Failed }

/**
 * Orchestrates one relay session: handshake → bootstrap → bridge → channel →
 * v4 handshake → conversation subscription.
 *
 * Mirrors the verified flows in `verify/Main.kt` (read) and
 * `verify/CommandProbe.kt` (write).
 */
class ZCodeSession(
    private val scope: CoroutineScope,
    webSocketFactory: WebSocketFactory,
    private val logger: (String) -> Unit = {},
) {
    private var transport: RelayTransport? = null
    private var channel: ChannelClient? = null
    private var api: ConversationApi? = null
    private var frameListenerId: Int? = null
    private var indexListenerId: Int? = null
    private var configListenerId: Int? = null
    private var tasksIndexSubscription: TasksIndexSubscription? = null
    private var terminalDataListenerId: Int? = null
    private var terminalExitListenerId: Int? = null
    private var terminalId: String? = null
    private var workspaceKey: String? = null
    private var subscriptionId: String? = null
    /** Session whose subscribe is in flight; frames may beat the ack back. */
    private var pendingSubscriptionSession: String? = null
    private var indexSubscriptionId: String? = null
    private var configSubscriptionId: String? = null

    /** Kept so the user can recover from a dropped socket without re-pasting. */
    private var lastLink: RemoteLink? = null
    private var userDisconnected = false

    /** What to reopen after a reconnect, captured as the session ran. */
    private var lastTaskId: String? = null

    private val _state = MutableStateFlow(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    /**
     * Attempt counter of the running auto-reconnect, 0 when not reconnecting.
     * The reconnect banner reads this to show 第 N 次.
     */
    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private val _bootstrap = MutableStateFlow<BootstrapResult?>(null)
    val bootstrap: StateFlow<BootstrapResult?> = _bootstrap.asStateFlow()

    private val _bridge = MutableStateFlow<BridgeInfo?>(null)
    val bridge: StateFlow<BridgeInfo?> = _bridge.asStateFlow()

    private val _ui = MutableStateFlow(ConversationUiState())
    val conversation: StateFlow<ConversationUiState> = _ui.asStateFlow()

    /** Live workspace session list (topic `sessions-index/<workspaceId>`). */
    private val _sessionsIndex = MutableStateFlow(SessionsIndexState())
    val sessionsIndex: StateFlow<SessionsIndexState> = _sessionsIndex.asStateFlow()

    /**
     * Window-wide task index push (`controller/tasks-index`).
     *
     * Carries every task the desktop window knows with its membership
     * (pinned / archived / active) — the sidebar's live data source and a
     * cross-workspace list for the home screen.
     */
    private val _taskIndex = MutableStateFlow(TaskIndexState())
    val taskIndex: StateFlow<TaskIndexState> = _taskIndex.asStateFlow()

    /** Workspace config directory (topic `workspace-config/<workspaceId>`). */
    private val _workspaceConfig = MutableStateFlow(WorkspaceConfigState())
    val workspaceConfig: StateFlow<WorkspaceConfigState> = _workspaceConfig.asStateFlow()

    /** Projected desktop application settings. */
    private val _settings = MutableStateFlow(AppSettings.Empty)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Remote terminal panel (`terminal` channel). */
    private val _terminal = MutableStateFlow(TerminalUiState())
    val terminal: StateFlow<TerminalUiState> = _terminal.asStateFlow()

    /** Provider registry view for the model settings screen. */
    private val _providerSettings = MutableStateFlow<ProviderSettingsView?>(null)
    val providerSettings: StateFlow<ProviderSettingsView?> = _providerSettings.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val factory = webSocketFactory
    private val fragments = HashMap<String, FragmentAssembly>()

    /**
     * Stable for the lifetime of this session: the host answers
     * `fault.command.clientMismatch` for any envelope whose `clientId`
     * differs from the handshake identity.
     */
    val clientId: String = "zcode-mobile-${PUuid.random()}"

    suspend fun connect(link: RemoteLink): BootstrapResult {
        disconnect()
        _error.value = null
        _state.value = ConnState.Handshaking
        userDisconnected = false
        lastLink = link

        val t = RelayTransport(link, factory, logger) { reason, code -> onTransportLost(reason, code) }
        transport = t
        try {
            t.connect(scope)
        } catch (e: Exception) {
            fail(e); throw e
        }

        _state.value = ConnState.Bootstrapping
        val boot = try {
            t.bootstrap()
        } catch (e: Exception) {
            fail(e); throw e
        }
        _bootstrap.value = boot
        return boot
    }

    /**
     * Switch the bridged workspace by rebuilding the whole connection.
     *
     * Re-bridging on the live transport was observed to wedge: after a second
     * `workspace-bridge-open` the host delivered the new channel's `initialize`
     * but never answered its first RPC, leaving the app showing the previous
     * session under the new title. The teardown→connect path is the one the
     * auto-reconnect already exercises successfully, so a workspace switch
     * goes through it too.
     */
    suspend fun rebridge(workspacePath: String, taskId: String?) {
        val link = lastLink ?: error("no link to reconnect")
        connect(link)
        openWorkspace(workspacePath, taskId)
    }

    /**
     * Begin opening a session: drop whatever conversation is on screen before
     * the (possibly slow) switch + subscribe.
     *
     * Without this, a failed open leaves the *previous* session's transcript
     * rendered under the tapped row's title — reading as two sessions' content
     * mixed together. An empty transcript plus the error is the honest state.
     */
    fun beginOpenSession(sessionId: String) {
        subscriptionId = null
        _ui.update {
            it.copy(conversation = ConversationState(sessionId = sessionId), plans = emptyList())
        }
    }

    /** Open the RPC bridge and complete the v4 handshake. Required first. */
    suspend fun openWorkspace(workspacePath: String, taskId: String? = null) {
        val t = transport ?: error("not connected")
        _state.value = ConnState.Bridging

        // Switching to a task of another workspace re-enters here while the
        // previous channel is still live; retire it first (see teardownChannel)
        // or its client keeps competing for the shared payload stream.
        teardownChannel()

        _bridge.value = t.openBridge(workspacePath, taskId)
        workspaceKey = workspacePath
        lastTaskId = taskId

        val c = ChannelClient(t, scope, logger)
        channel = c
        c.start()
        c.awaitInitialized()

        val a = ConversationApi(c)
        api = a
        try {
            a.handshake(clientId = clientId, appVersion = APP_VERSION)
        } catch (e: Exception) {
            _error.value = "v4 handshake failed: ${e.message}"
            _state.value = ConnState.Failed
            throw e
        }
        _state.value = ConnState.Ready
        loadGit()
        // The list and the config directory are workspace-scoped, so they are
        // opened with the bridge rather than with any one conversation.
        subscribeWorkspaceStreams(workspacePath)
    }

    /**
     * Open the two workspace-level topic streams.
     *
     * Each listener is registered **before** its subscribe: the snapshot
     * follows the ack immediately, so attaching afterwards drops it.
     */
    private suspend fun subscribeWorkspaceStreams(workspacePath: String) {
        val a = api ?: return

        if (indexListenerId == null) {
            indexListenerId = a.listenSessionsIndexFrames(workspacePath) { onIndexFrame(it) }
        }
        runCatching {
            val ack = Json.asMap(a.subscribeSessionsIndex(workspacePath)["ack"])
            indexSubscriptionId = Json.asString(ack["subscriptionId"])
        }.onFailure { logger("sessions-index subscribe failed: ${it.message}") }

        if (configListenerId == null) {
            configListenerId = a.listenWorkspaceConfigFrames(workspacePath) { onConfigFrame(it) }
        }
        runCatching {
            val ack = Json.asMap(a.subscribeWorkspaceConfig(workspacePath)["ack"])
            configSubscriptionId = Json.asString(ack["subscriptionId"])
        }.onFailure { logger("workspace-config subscribe failed: ${it.message}") }

        // Window-scoped (not workspace-scoped): one push covers every task of
        // the desktop window, so it is subscribed once per connection. Failure
        // is non-fatal — the list still works off the snapshot fallbacks.
        val ch = channel
        if (tasksIndexSubscription == null && ch != null) {
            runCatching {
                tasksIndexSubscription = TaskService(ch)
                    .subscribeTasksIndex { frame ->
                        when (frame) {
                            is TaskIndexFrame.Snapshot ->
                                _taskIndex.value = TaskIndex.applySnapshot(frame.frame, frame.snapshot)
                            is TaskIndexFrame.Deltas ->
                                _taskIndex.value = TaskIndex.applyDeltas(
                                    _taskIndex.value, frame.deltas,
                                    onUnknownOp = { logger("unhandled tasks-index op: $it") },
                                )
                        }
                    }
            }.onFailure { logger("tasks-index subscribe failed: ${it.message}") }
        }
    }

    /** Serializes [subscribe]: two rapid taps must not interleave their
     *  unsubscribe/subscribe pairs, or one of the two streams stays open. */
    private val subscribeMutex = Mutex()

    /**
     * Last stale subscription id already reported, so a stream that keeps
     * delivering after being retired cannot flood the log.
     */
    private var staleFrameLoggedFor: String? = null

    /**
     * Subscribe to a session and start receiving snapshot + deltas.
     *
     * The frame listener is registered first: the snapshot frame follows
     * immediately after the subscribe ack, so attaching afterwards misses it.
     */
    suspend fun subscribe(sessionId: String) = subscribeMutex.withLock {
        val a = api ?: error("workspace not opened")
        val ws = workspaceKey ?: error("workspace not opened")

        if (frameListenerId == null) {
            frameListenerId = a.listenFrames(ws) { candidate -> onWireFrame(candidate) }
        }

        // Retire the previous conversation subscription before opening the new
        // one. Without this the host keeps streaming the old session, and its
        // rows/deltas arrive on the same workspace stream as the new session's
        // — the two transcripts visibly cross. The window where
        // `subscriptionId` is null also makes the frame filter in
        // [onWireFrame] drop anything still in flight from the old stream.
        val previous = subscriptionId
        subscriptionId = null
        if (previous != null) {
            runCatching { a.unsubscribe(ws, previous) }
                .onFailure { logger("unsubscribe previous subscription failed: ${it.message}") }
        }

        // The host can push the snapshot frame before the subscribe ack makes
        // it back over the wire. Without this marker `onWireFrame` sees
        // `subscriptionId == null`, drops the one-and-only snapshot as a
        // straggler, and the screen waits forever. While it is set, a frame
        // whose own sessionId matches is adopted instead of dropped.
        pendingSubscriptionSession = sessionId
        val res = try {
            a.subscribe(ws, sessionId)
        } finally {
            pendingSubscriptionSession = null
        }
        val ack = Json.asMap(res["ack"])
        subscriptionId = Json.asString(ack["subscriptionId"])
        staleFrameLoggedFor = null
        // Reset only the conversation projection. Assigning a fresh
        // ConversationUiState here wiped every sibling field — git, models and
        // staged attachments all live on the same state object and are
        // populated before subscribe runs, so the branch row silently vanished.
        //
        // Skipped when the snapshot already landed ahead of the ack (adopted
        // in [onWireFrame]): resetting would discard the only snapshot this
        // subscription gets and strand the screen on "loading" forever.
        val adopted = _ui.value.conversation.let { it.sessionId == sessionId && it.hasSnapshot }
        if (!adopted) {
            _ui.update {
                it.copy(
                    conversation = ConversationState(
                        sessionId = sessionId,
                        logEpoch = Json.asString(ack["logEpoch"]),
                    ),
                    plans = emptyList(),
                )
            }
        }
    }

    // ── commands ──────────────────────────────────────────────────────────

    /**
     * Submit a command built against the current revision.
     *
     * On `stale` the command is re-sent **once** with the server's
     * `revisionAtDecision` and the *same* `commandId`. Reusing the id is what
     * makes the retry safe: it is the host's idempotency key, so a first
     * attempt that actually landed is answered `duplicate` rather than applied
     * twice.
     */
    private suspend fun submit(
        label: String,
        build: (revision: Long, commandId: String) -> Map<String, Any?>,
    ): CommandAck? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        val conv = _ui.value.conversation
        val sessionId = conv.sessionId ?: return null

        val commandId = PUuid.random().toString()
        _ui.update { it.copy(busy = true, lastError = null) }
        return try {
            var ack = a.sendCommand(ws, build(conv.revision, commandId))
            val serverRevision = ack.revisionAtDecision
            if (ack.stale && serverRevision != null) {
                logger("$label stale at rev=${conv.revision}, retrying at $serverRevision")
                ack = a.sendCommand(ws, build(serverRevision, commandId))
            }
            report(ack)
            ack
        } catch (e: Exception) {
            _ui.update { it.copy(lastError = e.message) }
            logger("$label failed: ${e.message}")
            null
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    /**
     * Fetch an earlier page of the transcript.
     *
     * The snapshot only carries a tail window, so this is the only way to read
     * history older than the initial page.
     */
    suspend fun loadOlder(pageSize: Int = 60) {
        val a = api ?: return
        val ws = workspaceKey ?: return
        val conv = _ui.value.conversation
        val sessionId = conv.sessionId ?: return
        val oldest = conv.rows.firstOrNull()?.rowId ?: return
        if (conv.loadingOlder || !conv.hasMoreOlder) return

        _ui.update { it.copy(conversation = it.conversation.copy(loadingOlder = true)) }
        runCatching {
            val res = a.rowsRange(ws, sessionId, beforeRowId = oldest, limit = pageSize)
            // `hasMore` is absent on some hosts; fall back to "did we get a full page".
            val returned = Json.asList(res["rows"]).size
            val hasMore = Json.asBool(res["hasMore"]) ?: (returned >= pageSize)
            _ui.update {
                it.copy(
                    conversation = ConversationReducer.prependOlder(
                        it.conversation, res["rows"], hasMore,
                    )
                )
            }
        }.onFailure {
            logger("loadOlder failed: ${it.message}")
            _ui.update { it.copy(conversation = it.conversation.copy(loadingOlder = false)) }
        }
    }

    /**
     * Upload a file and stage it for the next message.
     *
     * Uploading eagerly (rather than at send time) means the transfer is
     * already done and validated by the time the user hits send, and a failure
     * surfaces while they are still looking at the picker result.
     */
    suspend fun attachFile(fileName: String, mime: String, bytes: ByteArray): AttachmentRef? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        val sessionId = _ui.value.conversation.sessionId ?: return null

        _ui.update { it.copy(uploading = true, uploadProgress = null, lastError = null) }
        return try {
            val ref = a.uploadAttachment(
                workspacePath = ws,
                sessionId = sessionId,
                fileName = fileName,
                mime = mime,
                bytes = bytes,
            ) { sent, total ->
                _ui.update { it.copy(uploadProgress = if (total > 1) "$sent/$total" else null) }
            }
            _ui.update {
                it.copy(attachments = it.attachments + ref, uploadProgress = null)
            }
            ref
        } catch (e: Exception) {
            logger("attach failed: ${e.message}")
            _ui.update { it.copy(lastError = "附件上传失败：${e.message}", uploadProgress = null) }
            null
        } finally {
            _ui.update { it.copy(uploading = false) }
        }
    }

    fun removeAttachment(ref: String) {
        _ui.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.ref == ref })
        }
    }

    /** Surface a client-side failure through the same channel as protocol ones. */
    fun reportError(message: String) {
        _ui.update { it.copy(lastError = message) }
    }

    /**
     * Send a user message.
     *
     * When the snapshot reports `inputRouting.mode = "choice"` the host refuses
     * a bare send, so the caller must pass [heldQueueDisposition] — that is the
     * "clear the queue and send / keep the queue and send now" decision.
     */
    suspend fun sendMessage(
        text: String,
        delivery: Commands.Delivery? = null,
        mode: Commands.Mode? = null,
        planEnabled: Boolean? = null,
        heldQueueDisposition: Commands.HeldQueueDisposition? = null,
        expectedHeldQueueItemIds: List<String>? = null,
    ): CommandAck? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        val state = _ui.value
        val conv = state.conversation
        val sessionId = conv.sessionId ?: return null
        val staged = state.attachments
        // A message may be attachments-only.
        if (text.isBlank() && staged.isEmpty()) return null

        _ui.update { it.copy(sending = true, lastError = null) }
        return try {
            val ack = a.sendText(
                workspacePath = ws,
                sessionId = sessionId,
                text = text,
                baseRevision = conv.revision.takeIf { it > 0 },
                baseLogEpoch = conv.logEpoch,
                requestedDelivery = delivery,
                mode = mode,
                planEnabled = planEnabled,
                attachments = staged,
                heldQueueDisposition = heldQueueDisposition,
                expectedHeldQueueItemIds = expectedHeldQueueItemIds,
            )
            // Clear staged uploads only once the host accepted them.
            if (ack.accepted || ack.duplicate) {
                _ui.update { it.copy(attachments = emptyList()) }
            }
            report(ack)
            ack
        } catch (e: Exception) {
            _ui.update { it.copy(lastError = e.message) }
            null
        } finally {
            _ui.update { it.copy(sending = false) }
        }
    }

    suspend fun resolve(
        interactionId: String,
        optionId: String? = null,
        freeText: String? = null,
        action: Commands.Action? = null,
    ): CommandAck? = submit("resolveInteraction") { revision, id ->
        Commands.resolveInteraction(
            clientId = requireClientId(),
            sessionId = sessionId(),
            interactionId = interactionId,
            optionId = optionId,
            freeText = freeText,
            action = action,
            baseRevision = revision,
            commandId = id,
        )
    }

    /** Stop the auto-resolution countdown for a question. */
    suspend fun snoozeInteraction(interactionId: String): CommandAck? =
        submit("snoozeInteraction") { revision, id ->
            Commands.snoozeInteractionAutoResolution(
                clientId = requireClientId(),
                sessionId = sessionId(),
                interactionId = interactionId,
                baseRevision = revision,
                commandId = id,
            )
        }

    /**
     * Stop the running turn.
     *
     * The foreground execution id is quoted when the snapshot knows it, so a
     * stop that arrives after the turn already ended cannot kill a later one.
     */
    suspend fun stop(): CommandAck? = submit("stop") { revision, id ->
        Commands.stop(
            clientId = requireClientId(),
            sessionId = sessionId(),
            expectedForegroundExecutionId = _ui.value.conversation.foregroundExecutionId,
            baseRevision = revision,
            commandId = id,
        )
    }

    /** Compact the conversation. Revision-free by design. */
    suspend fun compact(): CommandAck? = submit("compact") { _, id ->
        Commands.compact(
            clientId = requireClientId(),
            sessionId = sessionId(),
            commandId = id,
        )
    }

    // ── goal ──────────────────────────────────────────────────────────────

    suspend fun setGoal(text: String): CommandAck? = submit("sendGoalCommand") { revision, id ->
        Commands.sendGoalCommand(
            clientId = requireClientId(),
            sessionId = sessionId(),
            text = text,
            baseRevision = revision,
            commandId = id,
        )
    }

    suspend fun pauseGoal(): CommandAck? = submit("pauseGoal") { revision, id ->
        Commands.pauseGoal(requireClientId(), sessionId(), revision, commandId = id)
    }

    suspend fun resumeGoal(): CommandAck? = submit("resumeGoal") { revision, id ->
        Commands.resumeGoal(requireClientId(), sessionId(), revision, commandId = id)
    }

    // ── transcript editing ────────────────────────────────────────────────

    /**
     * Edit a user message, optionally rewinding the files of that turn.
     *
     * `rewind` can come back `blocked`; the ack's reason code says why, and the
     * message is not applied in that case.
     */
    suspend fun editUserQuery(
        rowId: Long,
        entityId: String,
        newText: String,
        workspaceMode: Commands.WorkspaceMode = Commands.WorkspaceMode.Preserve,
    ): CommandAck? = submit("editUserQuery") { revision, id ->
        Commands.editUserQuery(
            clientId = requireClientId(),
            sessionId = sessionId(),
            target = Commands.RowTarget(rowId, entityId),
            newText = newText,
            workspaceMode = workspaceMode,
            baseRevision = revision,
            baseLogEpoch = logEpoch(),
            commandId = id,
        )
    }

    /** Preview what a file rewind would touch, before committing to it. */
    suspend fun previewFileRewind(rowId: Long, entityId: String): Map<String, Any?>? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        val conv = _ui.value.conversation
        return runCatching {
            a.fileRewindPreview(
                workspacePath = ws,
                sessionId = sessionId(),
                target = Commands.RowTarget(rowId, entityId),
                baseRevision = conv.revision,
                baseLogEpoch = logEpoch(),
            )
        }.onFailure { _ui.update { s -> s.copy(lastError = it.message) } }.getOrNull()
    }

    suspend fun retryTurn(rowId: Long, entityId: String): CommandAck? =
        submit("retryTurn") { revision, id ->
            Commands.retryTurn(
                clientId = requireClientId(),
                sessionId = sessionId(),
                target = Commands.RowTarget(rowId, entityId),
                baseRevision = revision,
                baseLogEpoch = logEpoch(),
                commandId = id,
            )
        }

    /** Fork the conversation into a new session. Returns the new session id. */
    suspend fun forkAssistant(rowId: Long, entityId: String): String? =
        submit("forkAssistant") { revision, id ->
            Commands.forkAssistant(
                clientId = requireClientId(),
                sessionId = sessionId(),
                target = Commands.RowTarget(rowId, entityId),
                baseRevision = revision,
                baseLogEpoch = logEpoch(),
                commandId = id,
            )
        }?.createdSessionId

    /** Undo a turn's file changes without truncating the conversation. */
    suspend fun applyFileRewind(rowId: Long, entityId: String): CommandAck? =
        submit("applyFileRewind") { revision, id ->
            Commands.applyFileRewind(
                clientId = requireClientId(),
                sessionId = sessionId(),
                target = Commands.RowTarget(rowId, entityId),
                baseRevision = revision,
                baseLogEpoch = logEpoch(),
                commandId = id,
            )
        }

    /** Rate an assistant message; `null` clears the rating. */
    suspend fun setFeedback(rowId: Long, entityId: String, feedback: Commands.Feedback?): CommandAck? =
        submit("setAssistantFeedback") { revision, id ->
            Commands.setAssistantFeedback(
                clientId = requireClientId(),
                sessionId = sessionId(),
                target = Commands.RowTarget(rowId, entityId),
                feedback = feedback,
                baseRevision = revision,
                baseLogEpoch = logEpoch(),
                commandId = id,
            )
        }

    // ── queue ─────────────────────────────────────────────────────────────

    suspend fun sendQueuedNow(queueItemId: String): CommandAck? =
        submit("sendQueuedNow") { revision, id ->
            Commands.sendQueuedNow(
                requireClientId(), sessionId(), queueItemId, revision, commandId = id,
            )
        }

    suspend fun editQueueItem(queueItemId: String, newText: String): CommandAck? =
        submit("editQueueItem") { revision, id ->
            Commands.editQueueItem(
                requireClientId(), sessionId(), queueItemId, newText, revision, commandId = id,
            )
        }

    /** Move a queued item; `beforeQueueItemId = null` sends it to the tail. */
    suspend fun reorderQueueItem(queueItemId: String, beforeQueueItemId: String?): CommandAck? =
        submit("reorderQueueItem") { revision, id ->
            Commands.reorderQueueItem(
                requireClientId(), sessionId(), queueItemId, beforeQueueItemId, revision,
                commandId = id,
            )
        }

    suspend fun deleteQueueItem(queueItemId: String): CommandAck? =
        submit("deleteQueueItem") { revision, id ->
            Commands.deleteQueueItem(
                requireClientId(), sessionId(), queueItemId, revision, commandId = id,
            )
        }

    /** Turn automatic draining on or off; turning it on also wakes the queue. */
    suspend fun setAutoDrain(autoDrain: Boolean): CommandAck? =
        submit("setAutoDrain") { revision, id ->
            Commands.setAutoDrain(
                requireClientId(), sessionId(), autoDrain, revision, commandId = id,
            )
        }

    // ── background work ───────────────────────────────────────────────────

    suspend fun cancelBackgroundWork(workId: String): CommandAck? =
        submit("cancelBackgroundWork") { _, id ->
            Commands.cancelBackgroundWork(requireClientId(), sessionId(), workId, commandId = id)
        }

    // ── model / mode control ─────────────────────────────────────────────

    /** Fetch the desktop's model catalog once per screen. */
    suspend fun loadModels() {
        val a = api ?: return
        if (_ui.value.modelsLoading || _ui.value.models.isNotEmpty()) return
        _ui.update { it.copy(modelsLoading = true) }
        runCatching {
            val conv = _ui.value.conversation
            val catalog = a.loadModels(conv.provider, conv.model)
            _ui.update { it.copy(models = catalog.models, modelsLoading = false) }
        }.onFailure {
            logger("loadModels failed: ${it.message}")
            _ui.update { it.copy(modelsLoading = false) }
        }
    }

    /**
     * Change only the thinking budget.
     *
     * The host's `switchModelConfig` takes provider+model+thought together, so
     * this re-sends the current pair rather than inventing a separate command.
     */
    suspend fun switchThought(level: String): CommandAck? {
        val conv = _ui.value.conversation
        val provider = conv.provider ?: return null
        val model = conv.model ?: return null
        return submit("switchModelConfig") { revision, id ->
            Commands.switchModelConfig(
                clientId = requireClientId(),
                sessionId = sessionId(),
                provider = provider,
                model = model,
                thought = level,
                baseRevision = revision,
                commandId = id,
            )
        }
    }

    suspend fun switchModel(option: ModelOption, thought: String? = null): CommandAck? =
        submit("switchModelConfig") { revision, id ->
            Commands.switchModelConfig(
                clientId = requireClientId(),
                sessionId = sessionId(),
                provider = option.providerId,
                model = option.modelId,
                // The host requires a level even for models that expose none.
                thought = thought
                    ?: option.thoughtLevels.firstOrNull()
                    ?: _ui.value.conversation.thoughtLevel
                    ?: "disabled",
                baseRevision = revision,
                commandId = id,
            )
        }

    suspend fun switchMode(mode: Commands.Mode): CommandAck? =
        submit("switchCollaborationMode") { revision, id ->
            Commands.switchCollaborationMode(
                requireClientId(), sessionId(), mode, revision, commandId = id,
            )
        }

    suspend fun setFollowup(followup: Commands.Followup): CommandAck? =
        submit("setFollowupMode") { revision, id ->
            Commands.setFollowupMode(
                requireClientId(), sessionId(), followup, revision, commandId = id,
            )
        }

    // ── session management ────────────────────────────────────────────────

    /**
     * Create a session in the bridged workspace and return its id.
     *
     * `createSession` is session-less, so it is submitted through the raw
     * channel rather than [submit] (which needs an existing session id).
     */
    suspend fun createSession(title: String? = null, initialText: String? = null): String? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        _ui.update { it.copy(busy = true, lastError = null) }
        return try {
            val ack = a.createSession(
                workspacePath = ws,
                workspaceId = ws,
                firstInputText = initialText?.takeIf { it.isNotBlank() },
                configMode = null,
            )
            report(ack)
            ack.createdSessionId?.also { id ->
                if (!title.isNullOrBlank()) renameSession(id, title)
            }
        } catch (e: Exception) {
            _ui.update { it.copy(lastError = "新建会话失败：${e.message}") }
            null
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    /** Rename a session. Sticky: auto-titling stops once a custom title is set. */
    suspend fun renameSession(sessionId: String, title: String): CommandAck? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        return runCatching {
            a.renameSession(ws, sessionId, title, baseRevision = revisionFor(sessionId))
        }.onFailure { _ui.update { s -> s.copy(lastError = "重命名失败：${it.message}") } }
            .onSuccess { report(it) }
            .getOrNull()
    }

    /** Close a session. The history survives; this is not a record delete. */
    suspend fun deleteSession(sessionId: String): CommandAck? {
        val a = api ?: return null
        val ws = workspaceKey ?: return null
        return runCatching {
            a.deleteSession(ws, sessionId, baseRevision = revisionFor(sessionId))
        }.onFailure { _ui.update { s -> s.copy(lastError = "删除失败：${it.message}") } }
            .onSuccess { report(it) }
            .getOrNull()
    }

    // ── task index management (zcode-task) ────────────────────────────────

    private fun tasks(): TaskService? = channel?.let(::TaskService)

    /**
     * Rename through the task index — the path the web sidebar uses, so it
     * works for any task without a v4 CAS baseline.
     */
    suspend fun renameTaskByIndex(taskId: String, workspacePath: String, title: String): Boolean =
        taskAction("重命名失败") {
            it.renameTask(taskId, workspacePath, title)
        }

    suspend fun setTaskPinned(taskId: String, workspacePath: String, pinned: Boolean): Boolean =
        taskAction("置顶失败") { it.setTaskPinned(taskId, workspacePath, pinned) }

    suspend fun setTaskUnread(taskId: String, workspacePath: String, unread: Boolean): Boolean =
        taskAction("未读标记失败") { it.setTaskUnread(taskId, workspacePath, unread) }

    suspend fun setTaskArchived(taskId: String, workspacePath: String, archived: Boolean): Boolean =
        taskAction("归档失败") {
            if (archived) it.archiveTask(taskId, workspacePath) else it.unarchiveTask(taskId, workspacePath)
        }

    /**
     * Run one zcode-task mutation. Success is silent: the tasks-index push
     * refreshes the list, so the mutation has no local state to update.
     */
    private suspend fun taskAction(label: String, block: suspend (TaskService) -> Any?): Boolean {
        val t = tasks() ?: return false
        return runCatching { block(t) }
            .onFailure {
                logger("task action failed: ${it.message}")
                _ui.update { s -> s.copy(lastError = "$label：${it.message}") }
            }
            .isSuccess
    }

    // ── plans ─────────────────────────────────────────────────────────────

    /** Load the terminal plan catalog for the open session. */
    suspend fun loadPlans() {
        val a = api ?: return
        val ws = workspaceKey ?: return
        val sessionId = _ui.value.conversation.sessionId ?: return
        if (_ui.value.plansLoading) return
        _ui.update { it.copy(plansLoading = true) }
        runCatching { a.plans(ws, sessionId) }
            .onSuccess { _ui.update { s -> s.copy(plans = it, plansLoading = false) } }
            .onFailure {
                logger("loadPlans failed: ${it.message}")
                _ui.update { s -> s.copy(plansLoading = false) }
            }
    }

    // ── settings ──────────────────────────────────────────────────────────

    /** Read the projected application settings. */
    suspend fun loadSettings() {
        val a = api ?: return
        _ui.update { it.copy(busy = true) }
        runCatching { a.settings().load() }
            .onSuccess { _settings.value = it }
            .onFailure {
                logger("loadSettings failed: ${it.message}")
                _ui.update { s -> s.copy(lastError = "读取设置失败：${it.message}") }
            }
        _ui.update { it.copy(busy = false) }
    }

    /** Patch one application setting. */
    suspend fun updateSetting(key: String, value: Any?): Boolean {
        val a = api ?: return false
        return runCatching {
            a.settings().update(mapOf(key to value))
            // Reflect it locally so the control does not snap back while the
            // next `load()` is in flight.
            _settings.update { current -> AppSettings(current.values + (key to value)) }
            true
        }.onFailure {
            logger("updateSetting($key) failed: ${it.message}")
            _ui.update { s -> s.copy(lastError = "保存设置失败：${it.message}") }
        }.getOrDefault(false)
    }

    /** Load repository + branch state for the bridged workspace. */
    suspend fun loadGit() {
        val a = api ?: return
        val ws = workspaceKey ?: return
        runCatching { a.gitInfo(ws) }
            .onSuccess { info ->
                logger("git: repo=${info.isRepository} branch=${info.currentBranch} n=${info.branches.size}")
                _ui.update { it.copy(git = info) }
            }
            .onFailure { logger("git info failed: ${it.message}") }
    }

    /** Switch branches; refreshes the cached state on success. */
    suspend fun switchBranch(branch: String): Boolean {
        val a = api ?: return false
        val ws = workspaceKey ?: return false
        val ok = runCatching { a.switchBranch(ws, branch) }.getOrDefault(false)
        if (ok) loadGit()
        return ok
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun requireClientId(): String = clientId

    private fun sessionId(): String =
        _ui.value.conversation.sessionId ?: error("no session subscribed")

    private fun logEpoch(): String =
        _ui.value.conversation.logEpoch ?: error("no log epoch for the open session")

    /**
     * Revision to send with a command aimed at [targetSessionId].
     *
     * Only the subscribed session has a projected revision; for any other
     * session (rename/delete from the list) there is none to send, and the host
     * treats those two as revision-optional.
     */
    private fun revisionFor(targetSessionId: String): Long? =
        _ui.value.conversation.revision
            .takeIf { it > 0 && _ui.value.conversation.sessionId == targetSessionId }

    /**
     * `stale` means the CAS revision was wrong — surfaced rather than swallowed
     * so the UI can resync instead of appearing to have silently failed.
     */
    private fun report(ack: CommandAck) {
        val note = when (ack.status) {
            "accepted", "duplicate" -> ack.status
            else -> ack.status + (ack.reasonCode?.let { " ($it)" } ?: "")
        }
        _ui.update { it.copy(lastCommand = note, lastError = null) }
        logger("command ${ack.commandId} → $note")
    }

    private fun fail(e: Throwable) {
        _state.value = ConnState.Failed
        _error.value = e.message
    }

    /**
     * An established socket went away on its own.
     *
     * Terminal close codes (dead link, the desktop stopped remote control,
     * closed workspace) go straight to [ConnState.Failed]: retrying cannot
     * fix them, and for 4012/4013 an automatic retry would fight whoever just
     * took over the single controller slot. Everything else — network blips,
     * abnormal closes, heartbeat timeouts — starts the auto-reconnect loop;
     * the relay only lets the desktop go away when it is genuinely gone.
     */
    private fun onTransportLost(reason: String, closeCode: Int?) {
        if (userDisconnected) return
        logger("transport lost (code=$closeCode): $reason")
        if (closeCode != null && closeCode in NO_RETRY_CLOSE_CODES) {
            _state.value = ConnState.Failed
            _error.value = reason
            return
        }
        startAutoReconnect(reason)
    }

    private var reconnectJob: Job? = null

    /**
     * Retry the dropped connection with backoff until it holds or the user
     * gives up. Each attempt rebuilds the whole chain — transport, bootstrap,
     * workspace bridge, session subscription — from the context captured while
     * the session was live, without wiping the transcript on screen.
     */
    private fun startAutoReconnect(reason: String) {
        reconnectJob?.cancel()
        _reconnectAttempt.value = 0
        _error.value = null
        _state.value = ConnState.Reconnecting
        reconnectJob = scope.launch {
            val attempts = 10
            for (attempt in 1..attempts) {
                if (userDisconnected || lastLink == null) return@launch
                _reconnectAttempt.value = attempt
                // 2s, 4s, 8s, then a flat 15s — a full pass is about two minutes.
                delay(if (attempt <= 3) (1L shl attempt) * 1000 else 15_000)
                if (userDisconnected) return@launch
                if (restore()) return@launch
            }
            if (!userDisconnected) {
                _state.value = ConnState.Failed
                _error.value = "自动重连 $attempts 次未成功（$reason）· 点“重新连接”手动重试"
            }
        }
    }

    /**
     * One auto-reconnect attempt: rebuild transport → bootstrap → bridge →
     * subscribe over the still-displayed state. Returns false to keep the
     * loop going; the UI keeps the old transcript visible throughout.
     */
    private suspend fun restore(): Boolean {
        val link = lastLink ?: return false
        val ws = workspaceKey
        val taskId = lastTaskId
        val sessionId = _ui.value.conversation.sessionId
        return try {
            teardownTransport()
            val t = RelayTransport(link, factory, logger) { reason, code -> onTransportLost(reason, code) }
            transport = t
            t.connect(scope)
            _bootstrap.value = t.bootstrap()
            if (ws != null) {
                openWorkspace(ws, taskId)
                if (sessionId != null) subscribe(sessionId)
            } else {
                _state.value = ConnState.Ready
            }
            _reconnectAttempt.value = 0
            true
        } catch (e: Exception) {
            logger("reconnect attempt failed: ${e.message}")
            if (!userDisconnected) _state.value = ConnState.Reconnecting
            false
        }
    }

    /** Skip the current backoff wait and retry immediately. */
    fun retryNow() {
        if (userDisconnected || lastLink == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _reconnectAttempt.value = 1
            if (!restore()) startAutoReconnect("重连失败")
        }
    }

    /** Give up on the auto-reconnect; the manual 重新连接 button stays available. */
    fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectAttempt.value = 0
        if (_state.value == ConnState.Reconnecting) {
            _state.value = ConnState.Failed
            _error.value = "已停止自动重连 · 点“重新连接”手动重试"
        }
    }

    /** Retry the last link, e.g. after being dropped by another controller. */
    suspend fun reconnect(): BootstrapResult? {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectAttempt.value = 0
        val link = lastLink ?: return null
        return runCatching { connect(link) }
            .onFailure { fail(it) }
            .getOrNull()
    }

    // ── remote terminal (侧栏 终端 tab) ───────────────────────────────────

    /**
     * Open a remote shell in [cwd]. Listen → create ordering applies here
     * too: early output between create and the first listen would be lost.
     */
    suspend fun startTerminal(cwd: String, cols: Int = 80, rows: Int = 24) {
        val c = channel ?: error("not connected")
        stopTerminal()
        val svc = TerminalService(c)
        val id = svc.create(cols, rows, cwd)
        terminalId = id
        _terminal.value = TerminalUiState(id = id, cwd = cwd)
        terminalDataListenerId = svc.listenData(id) { chunk ->
            _terminal.update { st ->
                if (st.id != id) st else st.copy(output = appendTerminal(st.output, chunk))
            }
        }
        terminalExitListenerId = svc.listenExit(id) { code ->
            _terminal.update { st -> if (st.id != id) st else st.copy(exited = true, exitCode = code) }
        }
    }

    suspend fun terminalWrite(data: String) {
        val id = terminalId ?: return
        TerminalService(channel ?: return).write(id, data)
    }

    suspend fun terminalResize(cols: Int, rows: Int) {
        val id = terminalId ?: return
        runCatching { TerminalService(channel ?: return).resize(id, cols, rows) }
    }

    fun stopTerminal() {
        terminalDataListenerId?.let { runCatching { TerminalService(channel ?: return).disposeEvent(it) } }
        terminalExitListenerId?.let { runCatching { TerminalService(channel ?: return).disposeEvent(it) } }
        terminalDataListenerId = null
        terminalExitListenerId = null
        terminalId = null
        _terminal.value = TerminalUiState()
    }

    /** Strip VT control noise; a phone reader wants the words, not the escape codes. */
    private fun appendTerminal(current: String, chunk: String): String {
        val esc = 0x1B.toChar()
        val csi = Regex("$esc\\[[0-9;?]*[a-zA-Z]")
        val osc = Regex("$esc\\][^$esc]*($esc\\\\|$esc\\u0007)")
        val clean = chunk
            .replace(csi, "")
            .replace(osc, "")
            .replace(Regex("$esc[@-_]"), "")
            .replace("\r", "")
        val combined = current + clean
        return if (combined.length > TERMINAL_MAX_CHARS) combined.takeLast(TERMINAL_MAX_CHARS) else combined
    }

    // ── provider settings (模型设置) ───────────────────────────────────────

    suspend fun loadProviderSettings() {
        val c = channel ?: error("not connected")
        _providerSettings.value = ProviderSettingsService(c).getView()
    }

    /**
     * Every mutation returns the fresh view, so the caller just replaces the
     * flow value — no extra getView round-trip.
     */
    suspend fun addProvider(name: String, apiKey: String, baseUrl: String): ProviderSettingsView {
        val svc = ProviderSettingsService(channel ?: error("not connected"))
        val created = svc.createPersonalProvider(name)
        val id = created.providers.lastOrNull()?.providerId
            ?: error("创建供应商失败：返回视图为空")
        // `name` rides the create call; the overlay takes only the strict
        // config keys (access/api) — see ProviderSettingsService doc.
        return svc.savePersonalProviderOverlay(id, apiKey, baseUrl).also {
            _providerSettings.value = it
        }
    }

    suspend fun deleteProvider(providerId: String): ProviderSettingsView {
        val v = ProviderSettingsService(channel ?: error("not connected")).deletePersonalProvider(providerId)
        _providerSettings.value = v
        return v
    }

    suspend fun addProviderModel(providerId: String, modelId: String): ProviderSettingsView {
        val v = ProviderSettingsService(channel ?: error("not connected")).addPersonalModel(providerId, modelId)
        _providerSettings.value = v
        return v
    }

    suspend fun deleteProviderModel(providerId: String, modelId: String): ProviderSettingsView {
        val v = ProviderSettingsService(channel ?: error("not connected")).deletePersonalModel(providerId, modelId)
        _providerSettings.value = v
        return v
    }

    suspend fun setProviderModelEnabled(providerId: String, modelId: String, enabled: Boolean): ProviderSettingsView {
        val v = ProviderSettingsService(channel ?: error("not connected"))
            .setPersonalModelEnabled(providerId, modelId, enabled)
        _providerSettings.value = v
        return v
    }

    suspend fun testProviderModel(providerId: String, modelId: String): ProviderTestResult {
        val ws = workspaceKey ?: error("workspace not opened")
        return ProviderSettingsService(channel ?: error("not connected"))
            .testModelConnectivity(ws, providerId, modelId)
    }

    // ── inbound frames ────────────────────────────────────────────────────

    private fun onWireFrame(candidate: Map<String, Any?>) {
        // Generation check. The workspace frame stream carries every
        // conversation subscription this channel has opened, and retiring a
        // subscription does not cancel frames already queued for delivery —
        // so without this filter a previous session's streaming rows/deltas
        // get projected onto the session now on screen (visible as two
        // conversations' content mixed together). `subscriptionId` is the
        // protocol's generation id for exactly this purpose; `null` means no
        // session is on screen, so nothing should be applied either.
        //
        // One exception: while a subscribe is in flight the host may deliver
        // its snapshot before the ack arrives. That frame is the only snapshot
        // this subscription will ever get, so dropping it strands the screen
        // on "loading" forever. Adopt it when its payload names the session we
        // are opening; a straggler from the retired subscription names a
        // different session and is still dropped.
        val frameSub = Json.asString(candidate["subscriptionId"])
        var active = subscriptionId
        if (active == null) {
            val opening = pendingSubscriptionSession
            if (opening != null && frameSub != null && frameSessionId(candidate) == opening) {
                subscriptionId = frameSub
                active = frameSub
                logger("adopted early snapshot for $opening (subscription=$frameSub)")
            }
        }
        if (active == null || (frameSub != null && frameSub != active)) {
            if (frameSub != null && staleFrameLoggedFor != frameSub) {
                staleFrameLoggedFor = frameSub
                logger("dropped stale frames (subscription=$frameSub, active=$active)")
            }
            return
        }
        when (Json.asString(candidate["kind"])) {
            "complete" -> applyTopicFrame(Json.asMap(candidate["frame"]))
            "fragment" -> onFragment(candidate)
        }
    }

    /** Session a snapshot frame belongs to, or null when it is not a snapshot. */
    private fun frameSessionId(candidate: Map<String, Any?>): String? {
        if (Json.asString(candidate["kind"]) != "complete") return null
        val payload = Json.asMap(Json.asMap(candidate["frame"])["payload"])
        if (Json.asString(payload["kind"]) != "snapshot") return null
        return Json.asString(Json.asMap(payload["snapshot"])["sessionId"])
    }

    private fun onFragment(candidate: Map<String, Any?>) {
        val id = Json.asString(candidate["logicalFrameId"]) ?: return
        val index = Json.asLong(candidate["fragmentIndex"])?.toInt() ?: return
        val count = Json.asLong(candidate["fragmentCount"])?.toInt() ?: 1
        val b64 = Json.asString(candidate["dataBase64"]) ?: return

        val entry = fragments.getOrPut(id) { FragmentAssembly(count) }
        entry.parts[index] = PBase64.decode(b64)
        if (!entry.isComplete()) return

        fragments.remove(id)
        val joined = entry.join()
        runCatching {
            applyTopicFrame(Json.asMap(Json.decode(joined.decodeToString())))
        }.onFailure { logger("fragment reassembly failed: ${it.message}") }
    }

    private fun applyTopicFrame(frame: Map<String, Any?>) {
        if (frame.isEmpty()) return
        val payload = Json.asMap(frame["payload"])
        when (Json.asString(payload["kind"])) {
            "snapshot" -> {
                val state = ConversationReducer.applySnapshot(
                    frame, Json.asMap(payload["snapshot"]),
                )
                _ui.update { it.copy(conversation = state) }
            }
            "deltas" -> {
                val ops = Json.asList(payload["deltas"])
                _ui.update {
                    it.copy(
                        conversation = ConversationReducer.applyDeltas(
                            it.conversation, ops,
                            onUnknownOp = { op -> logger("unhandled delta op: $op") },
                        )
                    )
                }
            }
        }
    }

    private fun onIndexFrame(candidate: Map<String, Any?>) {
        applyRoutedFrame(candidate) { frame ->
            val payload = Json.asMap(frame["payload"])
            when (Json.asString(payload["kind"])) {
                "snapshot" -> _sessionsIndex.value = SessionsIndex.applySnapshot(
                    frame, Json.asMap(payload["snapshot"]),
                )
                "deltas" -> _sessionsIndex.value = SessionsIndex.applyDeltas(
                    _sessionsIndex.value, Json.asList(payload["deltas"]),
                    onUnknownOp = { logger("unhandled sessions-index op: $it") },
                )
            }
        }
    }

    private fun onConfigFrame(candidate: Map<String, Any?>) {
        applyRoutedFrame(candidate) { frame ->
            val payload = Json.asMap(frame["payload"])
            when (Json.asString(payload["kind"])) {
                "snapshot" -> _workspaceConfig.value =
                    WorkspaceConfig.applySnapshot(Json.asMap(payload["snapshot"]))
                "deltas" -> _workspaceConfig.value = WorkspaceConfig.applyDeltas(
                    _workspaceConfig.value, Json.asList(payload["deltas"]),
                    onUnknownOp = { logger("unhandled workspace-config op: $it") },
                )
            }
        }
    }

    /** Shared complete/fragment unwrapping for the two workspace topics. */
    private fun applyRoutedFrame(
        candidate: Map<String, Any?>,
        apply: (Map<String, Any?>) -> Unit,
    ) {
        when (Json.asString(candidate["kind"])) {
            "complete" -> apply(Json.asMap(candidate["frame"]))
            "fragment" -> onFragment(candidate) { frame ->
                apply(frame)
                true
            }
        }
    }

    /**
     * Reassemble a fragment frame and hand the decoded frame to [apply].
     *
     * Returns false while more fragments are still outstanding.
     */
    private fun onFragment(
        candidate: Map<String, Any?>,
        apply: (Map<String, Any?>) -> Boolean,
    ): Boolean {
        val id = Json.asString(candidate["logicalFrameId"]) ?: return false
        val index = Json.asLong(candidate["fragmentIndex"])?.toInt() ?: return false
        val count = Json.asLong(candidate["fragmentCount"])?.toInt() ?: 1
        val b64 = Json.asString(candidate["dataBase64"]) ?: return false

        val entry = fragments.getOrPut(id) { FragmentAssembly(count) }
        entry.parts[index] = PBase64.decode(b64)
        if (!entry.isComplete()) return false

        fragments.remove(id)
        val joined = entry.join()
        return runCatching {
            apply(Json.asMap(Json.decode(joined.decodeToString())))
        }.onFailure { logger("fragment reassembly failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Close everything below the UI: transport, channel, api, listeners.
     *
     * A reconnect must call this too — listener ids belong to the *old* api
     * instance, and keeping them would silently skip re-registering on the
     * new one (every `if (xListenerId == null)` guard), leaving the session
     * mute after a successful reconnect.
     */
    private fun teardownTransport() {
        teardownChannel()
        transport?.close()
        transport = null
    }

    /**
     * Retire the channel level of a connection, keeping the transport.
     *
     * Re-opening a workspace (a task in another workspace tapped while a
     * session was streaming) needs this before opening the new bridge: the
     * previous [ChannelClient] would otherwise stay attached to the shared
     * payload stream and steal half of the new client's messages, and the
     * old conversation subscription would keep pushing rows of the session
     * the user just left.
     */
    private fun teardownChannel() {
        val a = api
        val c = channel
        val ws = workspaceKey
        val conversationSub = subscriptionId
        tasksIndexSubscription?.let { sub ->
            c?.let { runCatching { TaskService(it).unsubscribeTasksIndex(sub) } }
        }
        tasksIndexSubscription = null
        frameListenerId?.let { a?.disposeListener(it) }
        indexListenerId?.let { a?.disposeListener(it) }
        configListenerId?.let { a?.disposeListener(it) }
        frameListenerId = null
        indexListenerId = null
        configListenerId = null
        terminalDataListenerId?.let { id -> c?.let { runCatching { TerminalService(it).disposeEvent(id) } } }
        terminalExitListenerId?.let { id -> c?.let { runCatching { TerminalService(it).disposeEvent(id) } } }
        terminalDataListenerId = null
        terminalExitListenerId = null
        terminalId = null
        if (conversationSub != null && a != null && ws != null) {
            // Best effort: the transport may already be gone; the host also
            // retires subscriptions on bridge replacement.
            scope.launch {
                runCatching { a.unsubscribe(ws, conversationSub) }
                    .onFailure { logger("unsubscribe conversation failed: ${it.message}") }
            }
        }
        subscriptionId = null
        indexSubscriptionId = null
        configSubscriptionId = null
        c?.close()
        api = null
        channel = null
        fragments.clear()
        // Workspace-scoped projections: the next bridge fills them again.
        _sessionsIndex.value = SessionsIndexState()
        _workspaceConfig.value = WorkspaceConfigState()
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectAttempt.value = 0
        teardownTransport()
        workspaceKey = null
        lastTaskId = null
        _state.value = ConnState.Idle
        _bridge.value = null
        _ui.value = ConversationUiState()
        _sessionsIndex.value = SessionsIndexState()
        _workspaceConfig.value = WorkspaceConfigState()
        _taskIndex.value = TaskIndexState()
        _settings.value = AppSettings.Empty
        _terminal.value = TerminalUiState()
        _providerSettings.value = null
    }

    private class FragmentAssembly(val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        fun isComplete() = parts.all { it != null }
        fun join(): ByteArray {
            var total = 0
            for (p in parts) if (p != null) total += p.size
            val out = ByteArray(total)
            var off = 0
            for (p in parts) if (p != null) { p.copyInto(out, off); off += p.size }
            return out
        }
    }

    private companion object {
        const val APP_VERSION = "0.1.0"

        /** Terminal output is capped so a chatty process cannot eat the heap. */
        const val TERMINAL_MAX_CHARS = 120_000

        /**
         * Close codes where retrying is pointless or hostile — dead link,
         * relay session gone, or the desktop/web page deliberately took the
         * single controller slot (see [onTransportLost]).
         */
        val NO_RETRY_CLOSE_CODES = setOf(4004, 4009, 4010, 4011, 4012, 4013)
    }
}
