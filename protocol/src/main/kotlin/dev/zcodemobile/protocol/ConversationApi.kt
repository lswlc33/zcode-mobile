package dev.zcodemobile.protocol

/**
 * Thin facade over the `zcode-agent` channel for the v4 surface.
 *
 * Every method name and argument shape here was confirmed against the live
 * relay. The host dispatches through `ProxyChannel.fromService`, so `call`
 * arguments travel as a positional array — a one-parameter method is still a
 * one-element list.
 *
 * Commands go through [sendCommand], which takes a prebuilt envelope. The
 * envelope builders in [Commands] enforce the CAS requirements, so a command
 * that cannot possibly be accepted is rejected before it reaches the wire.
 */
@ZCodeExperimental
class ConversationApi(private val channel: ChannelClient) {

    /**
     * The identity registered with the clientHello.
     *
     * The host rejects any command envelope whose `clientId` differs from the
     * one used at handshake time (`fault.command.clientMismatch`), so command
     * builders must be fed this value rather than generating their own.
     */
    var clientId: String? = null
        private set

    companion object {
        const val CHANNEL = ChannelProtocol.CHANNEL_ZCODE_AGENT

        /** v4 frame stream for one workspace; drives `onDynamicConversationFrame`. */
        const val EVENT_FRAMES = "onDynamicConversationFrame"

        /** Workspace session-list stream. */
        const val EVENT_SESSIONS_INDEX = "onDynamicSessionsIndexFrame"

        /** Workspace config-directory stream. */
        const val EVENT_WORKSPACE_CONFIG = "onDynamicWorkspaceConfigFrame"

        /** `PROTOCOL_V4_LIMITS.attachmentChunkMaxBytes` */
        private const val CHUNK_BYTES = 512 * 1024

        /** `PROTOCOL_V4_LIMITS.attachmentMaxBytes` */
        private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

        /** `PROTOCOL_V4_LIMITS.rowsRangeMaxLimit` */
        const val ROWS_RANGE_MAX_LIMIT = 200
    }

    /** Host hello. Must be read before the clientHello is sent. */
    suspend fun hello(): Map<String, Any?> =
        Json.asMap(channel.call(CHANNEL, "helloConversationV4"))

    /**
     * Register this connection.
     *
     * `capabilities` on the host side is `.strict()`, so only keys the host
     * itself declared in [hello] may be echoed back; sending an unknown key
     * makes the whole clientHello fail to parse and the connection never
     * completes its handshake.
     */
    suspend fun initialize(
        clientId: String,
        clientKind: String = "mobileApp",
        appVersion: String = "0.1.0",
        hostCapabilities: Map<String, Any?> = emptyMap(),
        workspaceHookReviewUi: Boolean = true,
    ) {
        val caps = linkedMapOf<String, Any?>("workspaceHookReviewUi" to workspaceHookReviewUi)
        if (hostCapabilities["workflowRunDeltas"] == true) caps["workflowRunDeltas"] = true

        channel.call(
            CHANNEL,
            "initializeConversationV4",
            linkedMapOf(
                "kind" to "clientHello",
                "protocolVersion" to 3,
                "clientId" to clientId,
                "clientKind" to clientKind,
                "appVersion" to appVersion,
                "capabilities" to caps,
            ),
        )
        this.clientId = clientId
    }

    /** Convenience: hello → initialize in one step. Returns the host hello. */
    suspend fun handshake(
        clientId: String = this.clientId ?: "zcode-mobile-${java.util.UUID.randomUUID()}",
        clientKind: String = "mobileApp",
        appVersion: String = "0.1.0",
    ): Map<String, Any?> {
        val hello = hello()
        initialize(
            clientId = clientId,
            clientKind = clientKind,
            appVersion = appVersion,
            hostCapabilities = Json.asMap(hello["capabilities"]),
        )
        return hello
    }

    // ── commands ──────────────────────────────────────────────────────────

    /**
     * Submit a command envelope. Throws only on transport failure — a rejected
     * command still returns a [CommandAck] with `status = "rejected"`.
     */
    suspend fun sendCommand(workspacePath: String, envelope: Map<String, Any?>): CommandAck =
        CommandAck.parse(
            Json.asMap(
                channel.call(
                    CHANNEL,
                    "sendConversationCommandV4",
                    linkedMapOf("workspacePath" to workspacePath, "envelope" to envelope),
                )
            )
        )

    /** Handshake identity, or a clear failure if [handshake] has not run. */
    fun requireClientId(): String =
        clientId ?: error("handshake() must complete before sending commands")

    /**
     * Send a user message.
     *
     * [heldQueueDisposition] and [expectedHeldQueueItemIds] are only needed when
     * the snapshot's `inputRouting.mode` is `choice`.
     */
    suspend fun sendText(
        workspacePath: String,
        sessionId: String,
        text: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        requestedDelivery: Commands.Delivery? = null,
        mode: Commands.Mode? = null,
        planEnabled: Boolean? = null,
        attachments: List<AttachmentRef> = emptyList(),
        modelSelection: ModelSelection? = null,
        heldQueueDisposition: Commands.HeldQueueDisposition? = null,
        expectedHeldQueueItemIds: List<String>? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.sendText(
            clientId = requireClientId(),
            sessionId = sessionId,
            text = text,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
            requestedDelivery = requestedDelivery,
            mode = mode,
            planEnabled = planEnabled,
            attachments = attachments,
            modelSelection = modelSelection,
            heldQueueDisposition = heldQueueDisposition,
            expectedHeldQueueItemIds = expectedHeldQueueItemIds,
        ),
    )

    /** Create a session. Returns the ack; the new id is in `result.sessionId`. */
    suspend fun createSession(
        workspacePath: String,
        workspaceId: String,
        firstInputText: String? = null,
        firstInputAttachments: List<AttachmentRef> = emptyList(),
        firstInputMode: Commands.Mode? = null,
        firstInputPlanEnabled: Boolean? = null,
        configProvider: String? = null,
        configModel: String? = null,
        configThought: String? = null,
        configMode: Commands.Mode? = null,
        configFollowupMode: Commands.Followup? = null,
        configPlanEnabled: Boolean? = null,
        offPeakToolEnabled: Boolean? = null,
        dynamicWorkflowEnabled: Boolean? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.createSession(
            clientId = requireClientId(),
            workspaceId = workspaceId,
            firstInputText = firstInputText,
            firstInputAttachments = firstInputAttachments,
            firstInputMode = firstInputMode,
            firstInputPlanEnabled = firstInputPlanEnabled,
            configProvider = configProvider,
            configModel = configModel,
            configThought = configThought,
            configMode = configMode,
            configFollowupMode = configFollowupMode,
            configPlanEnabled = configPlanEnabled,
            offPeakToolEnabled = offPeakToolEnabled,
            dynamicWorkflowEnabled = dynamicWorkflowEnabled,
        ),
    )

    suspend fun renameSession(
        workspacePath: String,
        sessionId: String,
        title: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.renameSession(
            clientId = requireClientId(),
            sessionId = sessionId,
            title = title,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    /** Close a session (the message history survives; this is not a delete). */
    suspend fun deleteSession(
        workspacePath: String,
        sessionId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.deleteSession(
            clientId = requireClientId(),
            sessionId = sessionId,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    /** Answer a pending permission prompt or question. */
    suspend fun resolveInteraction(
        workspacePath: String,
        sessionId: String,
        interactionId: String,
        optionId: String? = null,
        freeText: String? = null,
        action: Commands.Action? = null,
        content: Map<String, Any?>? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.resolveInteraction(
            clientId = requireClientId(),
            sessionId = sessionId,
            interactionId = interactionId,
            optionId = optionId,
            freeText = freeText,
            action = action,
            content = content,
        ),
    )

    suspend fun snoozeInteraction(
        workspacePath: String,
        sessionId: String,
        interactionId: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.snoozeInteractionAutoResolution(
            clientId = requireClientId(),
            sessionId = sessionId,
            interactionId = interactionId,
        ),
    )

    /** Stop the running turn. */
    suspend fun stop(
        workspacePath: String,
        sessionId: String,
        expectedForegroundExecutionId: String? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.stop(
            clientId = requireClientId(),
            sessionId = sessionId,
            expectedForegroundExecutionId = expectedForegroundExecutionId,
        ),
    )

    /** Compact the conversation. CAS-exempt, so no revision is required. */
    suspend fun compact(workspacePath: String, sessionId: String): CommandAck = sendCommand(
        workspacePath,
        Commands.compact(clientId = requireClientId(), sessionId = sessionId),
    )

    // ── transcript editing ────────────────────────────────────────────────

    /**
     * Edit a user message; this is also the conversation-rewind entry point.
     *
     * `rewind` restores that turn's files before branching and can come back
     * `blocked` when the workspace rewind is unsafe.
     */
    suspend fun editUserQuery(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        newText: String,
        baseRevision: Long,
        baseLogEpoch: String,
        workspaceMode: Commands.WorkspaceMode? = null,
        attachments: List<AttachmentRef> = emptyList(),
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.editUserQuery(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            newText = newText,
            attachments = attachments,
            workspaceMode = workspaceMode,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    suspend fun retryTurn(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.retryTurn(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    suspend fun forkAssistant(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.forkAssistant(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    /** Undo a turn's file changes without truncating the conversation. */
    suspend fun applyFileRewind(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.applyFileRewind(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    suspend fun setAssistantFeedback(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        feedback: Commands.Feedback?,
        baseRevision: Long,
        baseLogEpoch: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.setAssistantFeedback(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            feedback = feedback,
            baseRevision = baseRevision,
            baseLogEpoch = baseLogEpoch,
        ),
    )

    // ── goal / plan ───────────────────────────────────────────────────────

    suspend fun sendGoal(
        workspacePath: String,
        sessionId: String,
        text: String,
        planEnabled: Boolean? = null,
        mode: Commands.Mode? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.sendGoalCommand(
            clientId = requireClientId(),
            sessionId = sessionId,
            text = text,
            planEnabled = planEnabled,
            mode = mode,
        ),
    )

    suspend fun pauseGoal(
        workspacePath: String,
        sessionId: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.pauseGoal(requireClientId(), sessionId, baseRevision),
    )

    suspend fun resumeGoal(
        workspacePath: String,
        sessionId: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.resumeGoal(requireClientId(), sessionId, baseRevision),
    )

    // ── queue ─────────────────────────────────────────────────────────────

    suspend fun sendQueuedNow(
        workspacePath: String,
        sessionId: String,
        queueItemId: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.sendQueuedNow(requireClientId(), sessionId, queueItemId, baseRevision),
    )

    suspend fun editQueueItem(
        workspacePath: String,
        sessionId: String,
        queueItemId: String,
        newText: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.editQueueItem(
            requireClientId(), sessionId, queueItemId, newText, baseRevision,
        ),
    )

    suspend fun reorderQueueItem(
        workspacePath: String,
        sessionId: String,
        queueItemId: String,
        beforeQueueItemId: String?,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.reorderQueueItem(
            requireClientId(), sessionId, queueItemId, beforeQueueItemId, baseRevision,
        ),
    )

    suspend fun deleteQueueItem(
        workspacePath: String,
        sessionId: String,
        queueItemId: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.deleteQueueItem(requireClientId(), sessionId, queueItemId, baseRevision),
    )

    suspend fun setAutoDrain(
        workspacePath: String,
        sessionId: String,
        autoDrain: Boolean,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.setAutoDrain(requireClientId(), sessionId, autoDrain, baseRevision),
    )

    // ── model / mode ──────────────────────────────────────────────────────

    suspend fun switchModel(
        workspacePath: String,
        sessionId: String,
        provider: String,
        model: String,
        thought: String,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.switchModelConfig(
            clientId = requireClientId(),
            sessionId = sessionId,
            provider = provider,
            model = model,
            thought = thought,
            baseRevision = baseRevision,
        ),
    )

    suspend fun switchMode(
        workspacePath: String,
        sessionId: String,
        mode: Commands.Mode,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.switchCollaborationMode(
            clientId = requireClientId(),
            sessionId = sessionId,
            mode = mode,
            baseRevision = baseRevision,
        ),
    )

    suspend fun setFollowup(
        workspacePath: String,
        sessionId: String,
        followup: Commands.Followup,
        baseRevision: Long,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.setFollowupMode(
            clientId = requireClientId(),
            sessionId = sessionId,
            followup = followup,
            baseRevision = baseRevision,
        ),
    )

    // ── background work / workflows ───────────────────────────────────────

    suspend fun cancelBackgroundWork(
        workspacePath: String,
        sessionId: String,
        workId: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.cancelBackgroundWork(requireClientId(), sessionId, workId),
    )

    suspend fun resumeWorkflowRun(
        workspacePath: String,
        sessionId: String,
        workId: String,
        name: String? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.resumeWorkflowRun(requireClientId(), sessionId, workId, name),
    )

    suspend fun startSavedWorkflow(
        workspacePath: String,
        sessionId: String,
        name: String,
        scope: Commands.WorkflowScope? = null,
        args: Map<String, Any?>? = null,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.startSavedWorkflow(requireClientId(), sessionId, name, scope, args),
    )

    suspend fun amendWorkflowRunSettings(
        workspacePath: String,
        sessionId: String,
        workId: String,
        subagentModel: String? = null,
        maxConcurrency: Int? = null,
        clearSubagentModel: Boolean = false,
        clearMaxConcurrency: Boolean = false,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.amendWorkflowRunSettings(
            clientId = requireClientId(),
            sessionId = sessionId,
            workId = workId,
            subagentModel = subagentModel,
            maxConcurrency = maxConcurrency,
            clearSubagentModel = clearSubagentModel,
            clearMaxConcurrency = clearMaxConcurrency,
        ),
    )

    suspend fun discardSharedContext(
        workspacePath: String,
        sessionId: String,
        contextId: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.discardSharedContext(requireClientId(), sessionId, contextId),
    )

    // ── workspace hook review ─────────────────────────────────────────────

    suspend fun respondWorkspaceHookReview(
        workspacePath: String,
        target: Commands.HookReviewTarget,
        action: Commands.HookReviewDecisionAction,
        reviewItemIds: List<String>,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.respondWorkspaceHookReview(
            clientId = requireClientId(),
            target = target,
            action = action,
            reviewItemIds = reviewItemIds,
        ),
    )

    suspend fun toggleWorkspaceHookReviewItem(
        workspacePath: String,
        target: Commands.HookReviewTarget,
        reviewItemId: String,
        enabled: Boolean,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.toggleWorkspaceHookReviewItem(
            clientId = requireClientId(),
            target = target,
            reviewItemId = reviewItemId,
            enabled = enabled,
        ),
    )

    suspend fun revokeWorkspaceHookTrust(
        workspacePath: String,
        sessionId: String,
        target: Commands.HookReviewTarget,
        reviewItemIds: List<String>,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.revokeWorkspaceHookTrust(
            clientId = requireClientId(),
            sessionId = sessionId,
            target = target,
            reviewItemIds = reviewItemIds,
        ),
    )

    suspend fun requestWorkspaceHookReview(
        workspacePath: String,
        sessionId: String,
        workspaceIdentity: String,
        bundleDigest: String,
    ): CommandAck = sendCommand(
        workspacePath,
        Commands.requestWorkspaceHookReview(
            clientId = requireClientId(),
            sessionId = sessionId,
            workspaceIdentity = workspaceIdentity,
            bundleDigest = bundleDigest,
        ),
    )

    // ── queries ───────────────────────────────────────────────────────────

    /**
     * Look up the recorded outcome of previously submitted commands by their
     * `commandId`. This is the idempotency probe: a command that was accepted
     * but whose ack was lost can be resolved here.
     */
    suspend fun queryCommands(
        workspacePath: String,
        keys: List<Pair<String?, String>>,
    ): Map<String, Any?> = Json.asMap(
        channel.call(
            CHANNEL,
            "queryConversationCommandsV4",
            linkedMapOf(
                "workspacePath" to workspacePath,
                "commands" to keys.map { (sessionId, commandId) ->
                    linkedMapOf("sessionId" to sessionId, "commandId" to commandId)
                },
            ),
        )
    )

    /** Terminal `ExitPlanMode` catalog for the session's current branch. */
    suspend fun plans(workspacePath: String, sessionId: String): List<PlanEntry> =
        PlanEntry.parseAll(
            Json.asMap(
                channel.call(
                    CHANNEL,
                    "conversationPlansV4",
                    linkedMapOf("workspacePath" to workspacePath, "sessionId" to sessionId),
                )
            )
        )

    /**
     * Preview what `applyFileRewind` would do.
     *
     * Needs a CAS base like the command itself, because the preview is only
     * meaningful against the revision the user was looking at.
     */
    suspend fun fileRewindPreview(
        workspacePath: String,
        sessionId: String,
        target: Commands.RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
    ): Map<String, Any?> = Json.asMap(
        channel.call(
            CHANNEL,
            "conversationFileRewindPreviewV4",
            linkedMapOf(
                "workspacePath" to workspacePath,
                "sessionId" to sessionId,
                "target" to target.toWire(),
                "baseRevision" to baseRevision,
                "baseLogEpoch" to baseLogEpoch,
            ),
        )
    )

    // ── subscriptions ─────────────────────────────────────────────────────

    /** Subscribe to a conversation. Returns the ack (`subscriptionId`, `mode`, …). */
    suspend fun subscribe(workspacePath: String, sessionId: String): Map<String, Any?> =
        Json.asMap(
            channel.call(
                CHANNEL,
                "subscribeConversationV4",
                linkedMapOf("workspacePath" to workspacePath, "sessionId" to sessionId),
            )
        )

    suspend fun unsubscribe(workspacePath: String, subscriptionId: String) {
        channel.call(
            CHANNEL,
            "unsubscribeConversationV4",
            linkedMapOf("workspacePath" to workspacePath, "subscriptionId" to subscriptionId),
        )
    }

    /** Subscribe to the workspace session list. Returns the ack. */
    suspend fun subscribeSessionsIndex(
        workspacePath: String,
        runtimePolicy: String? = null,
    ): Map<String, Any?> = Json.asMap(
        channel.call(
            CHANNEL,
            "subscribeSessionsIndexV4",
            buildMap {
                put("workspacePath", workspacePath)
                runtimePolicy?.let { put("runtimePolicy", it) }
            },
        )
    )

    suspend fun unsubscribeSessionsIndex(workspacePath: String, subscriptionId: String) {
        channel.call(
            CHANNEL,
            "unsubscribeSessionsIndexV4",
            linkedMapOf("workspacePath" to workspacePath, "subscriptionId" to subscriptionId),
        )
    }

    /** Subscribe to the workspace config directory. Returns the ack. */
    suspend fun subscribeWorkspaceConfig(
        workspacePath: String,
        runtimePolicy: String? = null,
    ): Map<String, Any?> = Json.asMap(
        channel.call(
            CHANNEL,
            "subscribeWorkspaceConfigV4",
            buildMap {
                put("workspacePath", workspacePath)
                runtimePolicy?.let { put("runtimePolicy", it) }
            },
        )
    )

    suspend fun unsubscribeWorkspaceConfig(workspacePath: String, subscriptionId: String) {
        channel.call(
            CHANNEL,
            "unsubscribeWorkspaceConfigV4",
            linkedMapOf("workspacePath" to workspacePath, "subscriptionId" to subscriptionId),
        )
    }

    /** Fetch a window of older rows (cursor paging; limit is capped at 200). */
    suspend fun rowsRange(
        workspacePath: String,
        sessionId: String,
        beforeRowId: Long? = null,
        limit: Int = 60,
    ): Map<String, Any?> = Json.asMap(
        channel.call(
            CHANNEL,
            "conversationRowsRangeV4",
            buildMap {
                put("workspacePath", workspacePath)
                put("sessionId", sessionId)
                beforeRowId?.let { put("beforeRowId", it) }
                put("limit", limit.coerceIn(1, ROWS_RANGE_MAX_LIMIT))
            },
        )
    )

    /** Register the workspace conversation frame stream. */
    fun listenFrames(workspacePath: String, onFrame: (Map<String, Any?>) -> Unit): Int =
        listen(EVENT_FRAMES, workspacePath, onFrame)

    /** Register the workspace session-list stream. Listen before subscribing. */
    fun listenSessionsIndexFrames(
        workspacePath: String,
        onFrame: (Map<String, Any?>) -> Unit,
    ): Int = listen(EVENT_SESSIONS_INDEX, workspacePath, onFrame)

    /** Register the workspace config stream. Listen before subscribing. */
    fun listenWorkspaceConfigFrames(
        workspacePath: String,
        onFrame: (Map<String, Any?>) -> Unit,
    ): Int = listen(EVENT_WORKSPACE_CONFIG, workspacePath, onFrame)

    private fun listen(event: String, workspacePath: String, onFrame: (Map<String, Any?>) -> Unit) =
        channel.listen(
            CHANNEL,
            event,
            linkedMapOf("workspacePath" to workspacePath),
        ) { candidate -> onFrame(Json.asMap(candidate)) }

    fun disposeListener(id: Int) = channel.disposeEvent(id)

    // ── sibling services ──────────────────────────────────────────────────

    /** Model catalog from the desktop. See [ModelCatalogService] re: API keys. */
    suspend fun loadModels(currentProviderId: String?, currentModelId: String?): ModelCatalog =
        ModelCatalogService(channel).load(currentProviderId, currentModelId)

    /** Desktop application settings. */
    suspend fun settings(): SettingsService = SettingsService(channel)

    /** Repository + branch state for the workspace. */
    suspend fun gitInfo(workspacePath: String): GitRepoInfo =
        GitService(channel).info(workspacePath)

    suspend fun switchBranch(workspacePath: String, branch: String): Boolean =
        GitService(channel).switchBranch(workspacePath, branch)

    // ── attachments ───────────────────────────────────────────────────────

    /**
     * Upload one file and return the reference to put in `sendText.attachments`.
     *
     * The transaction is begin → chunk* → commit. Limits come from
     * `PROTOCOL_V4_LIMITS`: 512 KiB per chunk, 20 MiB per file, at most 64
     * chunks. A failure aborts the upload so the host can drop its staging
     * area rather than holding a partial file until it times out.
     */
    suspend fun uploadAttachment(
        workspacePath: String,
        sessionId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        uploadId: String = "upload-${java.util.UUID.randomUUID()}",
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
    ): AttachmentRef {
        require(bytes.size <= MAX_ATTACHMENT_BYTES) {
            "附件超过 ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MiB 上限"
        }

        val chunks = chunkOf(bytes)
        val checksum = "sha256:" + sha256Hex(bytes)

        try {
            channel.call(
                CHANNEL,
                "attachmentBeginV4",
                linkedMapOf(
                    "workspacePath" to workspacePath,
                    "sessionId" to sessionId,
                    "uploadId" to uploadId,
                    "fileName" to fileName,
                    "mime" to mime,
                    "totalBytes" to bytes.size,
                    "totalChunks" to chunks.size,
                    "checksum" to checksum,
                ),
            )

            chunks.forEachIndexed { index, chunk ->
                channel.call(
                    CHANNEL,
                    "attachmentChunkV4",
                    linkedMapOf(
                        "workspacePath" to workspacePath,
                        "sessionId" to sessionId,
                        "uploadId" to uploadId,
                        "chunkIndex" to index,
                        "dataBase64" to java.util.Base64.getEncoder().encodeToString(chunk),
                    ),
                )
                onProgress?.invoke(index + 1, chunks.size)
            }

            val result = Json.asMap(
                channel.call(
                    CHANNEL,
                    "attachmentCommitV4",
                    linkedMapOf(
                        "workspacePath" to workspacePath,
                        "sessionId" to sessionId,
                        "uploadId" to uploadId,
                    ),
                )
            )
            return AttachmentRef(
                ref = Json.asString(result["ref"])
                    ?: throw IllegalStateException("commit returned no ref"),
                fileName = Json.asString(result["fileName"]) ?: fileName,
                mime = Json.asString(result["mime"]) ?: mime,
                bytes = Json.asLong(result["bytes"]) ?: bytes.size.toLong(),
                previewRef = Json.asString(result["previewRef"]),
            )
        } catch (e: Exception) {
            runCatching {
                channel.call(
                    CHANNEL,
                    "attachmentAbortV4",
                    linkedMapOf(
                        "workspacePath" to workspacePath,
                        "sessionId" to sessionId,
                        "uploadId" to uploadId,
                    ),
                )
            }
            throw e
        }
    }

    private fun chunkOf(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_BYTES, bytes.size)
            out += bytes.copyOfRange(offset, end)
            offset = end
        }
        return out
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/** One terminal `ExitPlanMode` entry. */
data class PlanEntry(
    val planId: String?,
    val title: String?,
    val raw: Map<String, Any?>,
) {
    companion object {
        fun parseAll(result: Map<String, Any?>): List<PlanEntry> =
            Json.asList(result["plans"]).map { p ->
                val m = Json.asMap(p)
                PlanEntry(
                    planId = Json.asString(m["planId"]) ?: Json.asString(m["id"]),
                    title = Json.asString(m["title"]),
                    raw = m,
                )
            }
    }
}

/** A committed upload, ready to be referenced by `sendText`. */
data class AttachmentRef(
    val ref: String,
    val fileName: String,
    val mime: String,
    val bytes: Long,
    val previewRef: String?,
) {
    fun toWire(): Map<String, Any?> = buildMap {
        put("ref", ref)
        put("fileName", fileName)
        put("mime", mime)
        put("bytes", bytes)
        previewRef?.let { put("previewRef", it) }
    }

    val isImage: Boolean get() = mime.startsWith("image/")
}

/**
 * Outcome of a submitted command.
 *
 * `accepted` does not promise the work survives a host restart; only the
 * authoritative data (`sourceCommandId` on the resulting rows) confirms that.
 * `stale` means the CAS revision was wrong and the caller should resync.
 */
data class CommandAck(
    val commandId: String,
    val status: String,
    val reasonCode: String?,
    val message: String?,
    val revisionAtDecision: Long?,
    val result: Map<String, Any?>,
    val raw: Map<String, Any?>,
) {
    val accepted: Boolean get() = status == "accepted"
    val duplicate: Boolean get() = status == "duplicate"

    /** No state changed; the command was well-formed but had nothing to do. */
    val noop: Boolean get() = status == "noop"

    /** CAS mismatch — resync and retry with [revisionAtDecision]. */
    val stale: Boolean get() = status == "stale"

    /** The new session id, for `createSession`. */
    val createdSessionId: String? get() = Json.asString(result["sessionId"])

    /** `inputAccepted` delivery, for `sendText`. */
    val delivery: String? get() = Json.asString(result["delivery"])

    companion object {
        fun parse(m: Map<String, Any?>): CommandAck = CommandAck(
            commandId = Json.asString(m["commandId"]) ?: "",
            status = Json.asString(m["status"]) ?: "unknown",
            reasonCode = Json.asString(m["reasonCode"]),
            message = Json.asString(m["message"]),
            revisionAtDecision = Json.asLong(m["revisionAtDecision"]),
            result = Json.asMap(m["result"]),
            raw = m,
        )
    }
}
