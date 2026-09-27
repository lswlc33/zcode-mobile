package dev.zcodemobile.protocol


/**
 * Builds v4 `CommandEnvelope`s — the complete command surface.
 *
 * Shape per `packages/shared/src/zcode-protocol-v4/command.ts`:
 * ```
 * { commandId, clientId, sessionId, baseRevision?, baseLogEpoch?,
 *   type, payload, issuedAt }
 * ```
 * `commandId` is client-generated and must stay stable across retries so the
 * host can deduplicate; `issuedAt` is client-clock telemetry only and is never
 * used for arbitration.
 *
 * **CAS is enforced here, not discovered at runtime.** The host rejects a
 * CAS-required command that omits `baseRevision` with a parse error, and a
 * row-targeting command that omits `baseLogEpoch` with `stale` /
 * `proto.staleLogEpoch` — both are programming errors on our side, so the
 * builders throw rather than shipping a command that cannot succeed.
 */
object Commands {

    /** Values accepted by `submissionModeSchema`. `auto` is runtime-only. */
    enum class Mode(val wire: String) {
        Build("build"),
        Edit("edit"),
        Plan("plan"),
        Yolo("yolo"),
    }

    /** How a new input relates to a turn that is already running. */
    enum class Delivery(val wire: String) {
        StartNow("startNow"),
        Queue("queue"),
        Guide("guide"),
    }

    enum class Followup(val wire: String) {
        Queue("queue"),
        Guide("guide"),
    }

    /** `action` values used by elicitation-style interactions. */
    enum class Action(val wire: String) {
        Accept("accept"),
        Decline("decline"),
        Cancel("cancel"),
    }

    /** What `editUserQuery` does to the files of the turn being rewound. */
    enum class WorkspaceMode(val wire: String) {
        /** Only switch the conversation branch. */
        Preserve("preserve"),

        /** Restore that turn's file changes first, then branch. */
        Rewind("rewind"),
    }

    enum class Feedback(val wire: String) {
        Like("like"),
        Dislike("dislike"),
    }

    enum class WorkflowScope(val wire: String) {
        Project("project"),
        Global("global"),
    }

    /**
     * `COMMANDS_REQUIRING_BASE_REVISION` (`command.ts:296-312`).
     *
     * A conversation revision is meaningless to a command that only appends
     * input or reads state, so the host demands one exactly for the commands
     * that mutate existing projected state.
     */
    val REQUIRES_BASE_REVISION: Set<String> = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback", "sendQueuedNow", "editQueueItem",
        "reorderQueueItem", "deleteQueueItem", "setAutoDrain",
        "switchModelConfig", "switchCollaborationMode", "setFollowupMode",
        "pauseGoal", "resumeGoal",
    )

    /**
     * `ROW_TARGETING_COMMANDS` (`command.ts:314-320`) — a subset of the above.
     *
     * A `rowId` is only meaningful inside one projection generation, so these
     * also carry `baseLogEpoch`; the host checks it *before* `baseRevision`.
     */
    val ROW_TARGETING: Set<String> = setOf(
        "applyFileRewind", "forkAssistant", "editUserQuery", "retryTurn",
        "setAssistantFeedback",
    )

    /** `conversationRowTargetSchema` — display rowId plus stable entityId. */
    data class RowTarget(val rowId: Long, val entityId: String) {
        fun toWire(): Map<String, Any?> = linkedMapOf(
            "rowId" to rowId,
            "entityId" to entityId,
        )
    }

    // ── session lifecycle ─────────────────────────────────────────────────

    /**
     * Create a session. `envelope.sessionId` is `null` — this is the only
     * command allowed to be session-less.
     *
     * With no [firstInput] the host creates an empty `draft` session; with one
     * it writes the turn header and user row directly. New sessions are
     * `deferred` persistence and are promoted on first send.
     */
    fun createSession(
        clientId: String,
        workspaceId: String,
        firstInputText: String? = null,
        firstInputAttachments: List<AttachmentRef> = emptyList(),
        firstInputModelSelection: ModelSelection? = null,
        firstInputMode: Mode? = null,
        firstInputPlanEnabled: Boolean? = null,
        configProvider: String? = null,
        configModel: String? = null,
        configThought: String? = null,
        configModelSelection: ModelSelection? = null,
        configFollowupMode: Followup? = null,
        configMode: Mode? = null,
        configPlanEnabled: Boolean? = null,
        mcpServers: List<Map<String, Any?>>? = null,
        offPeakToolEnabled: Boolean? = null,
        dynamicWorkflowEnabled: Boolean? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> {
        val firstInput = firstInputText?.let { text ->
            buildMap<String, Any?> {
                put("text", text)
                if (firstInputAttachments.isNotEmpty()) {
                    put("attachments", firstInputAttachments.map { it.toWire() })
                }
                firstInputModelSelection?.let { put("modelSelection", it.toWire()) }
                firstInputMode?.let { put("mode", it.wire) }
                firstInputPlanEnabled?.let { put("planEnabled", it) }
            }
        }

        // Deliberately not `sessionConfigStateSchema.partial()`: the snapshot
        // schema defaults `mode` to "build", which would turn "no mode
        // requested" into "switch back to build" and clobber a workspace
        // default of yolo.
        val config = buildMap<String, Any?> {
            configModelSelection?.let { put("modelSelection", it.toWire()) }
            configProvider?.let { put("provider", it) }
            configModel?.let { put("model", it) }
            configThought?.let { put("thought", it) }
            configFollowupMode?.let { put("followupMode", it.wire) }
            configMode?.let { put("mode", it.wire) }
            configPlanEnabled?.let { put("planEnabled", it) }
        }

        val payload = buildMap<String, Any?> {
            put("workspaceId", workspaceId)
            firstInput?.let { put("firstInput", it) }
            if (config.isNotEmpty()) put("config", config)
            mcpServers?.let { put("mcpServers", it) }
            offPeakToolEnabled?.let { put("offPeakToolEnabled", it) }
            dynamicWorkflowEnabled?.let { put("dynamicWorkflowEnabled", it) }
        }

        return envelope(
            commandId = commandId,
            clientId = clientId,
            sessionId = null,
            baseRevision = null,
            baseLogEpoch = null,
            type = "createSession",
            payload = payload,
        )
    }

    /** Create a side-chat session under the parent named by `envelope.sessionId`. */
    fun createSelectionSideSession(
        clientId: String,
        parentSessionId: String,
        firstInputText: String? = null,
        firstInputModelSelection: ModelSelection? = null,
        baseRevision: Long? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = parentSessionId,
        baseRevision = baseRevision,
        baseLogEpoch = null,
        type = "createSelectionSideSession",
        payload = buildMap {
            firstInputText?.let { text ->
                put(
                    "firstInput",
                    buildMap<String, Any?> {
                        put("text", text)
                        firstInputModelSelection?.let { put("modelSelection", it.toWire()) }
                    },
                )
            }
        },
    )

    /** Rename a session. Sticky: auto-titling stops once a custom title is set. */
    fun renameSession(
        clientId: String,
        sessionId: String,
        title: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "renameSession",
        payload = linkedMapOf("title" to title),
    )

    /**
     * Close a session.
     *
     * This is `closeSession` semantics — the runtime is released and the
     * session leaves the active registry, but the message store has no delete
     * API, so history survives.
     */
    fun deleteSession(
        clientId: String,
        sessionId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "deleteSession",
        payload = emptyMap<String, Any?>(),
    )

    // ── input ─────────────────────────────────────────────────────────────

    /**
     * Send a user message to an existing session.
     *
     * `requestedDelivery` may be omitted to let the host apply
     * `inputRouting.mode`; pass [Delivery.StartNow] to interrupt a running turn.
     *
     * When `inputRouting.mode` is `choice` the host requires
     * [heldQueueDisposition] plus the exact [expectedHeldQueueItemIds] the user
     * was shown — the ids guard against another device mutating the queue
     * between the confirmation opening and the send.
     */
    fun sendText(
        clientId: String,
        sessionId: String,
        text: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        requestedDelivery: Delivery? = null,
        mode: Mode? = null,
        planEnabled: Boolean? = null,
        attachments: List<AttachmentRef> = emptyList(),
        modelSelection: ModelSelection? = null,
        heldQueueDisposition: HeldQueueDisposition? = null,
        expectedHeldQueueItemIds: List<String>? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "sendText",
        payload = buildMap {
            put("text", text)
            requestedDelivery?.let { put("requestedDelivery", it.wire) }
            mode?.let { put("mode", it.wire) }
            planEnabled?.let { put("planEnabled", it) }
            modelSelection?.let { put("modelSelection", it.toWire()) }
            heldQueueDisposition?.let { put("heldQueueDisposition", it.wire) }
            expectedHeldQueueItemIds?.let { put("expectedHeldQueueItemIds", it) }
            // Only committed uploads may be referenced; the host validates the
            // ref against its own staging table.
            if (attachments.isNotEmpty()) put("attachments", attachments.map { it.toWire() })
        },
    )

    /** What to do with a paused queue when sending under `choice` routing. */
    enum class HeldQueueDisposition(val wire: String) {
        ClearQueueAndSend("clearQueueAndSend"),
        KeepQueueAndSend("keepQueueAndSend"),
    }

    /** Set or replace the session goal (legacy `goalSession action:"set"`). */
    fun sendGoalCommand(
        clientId: String,
        sessionId: String,
        text: String,
        displayText: String? = null,
        modelSelection: ModelSelection? = null,
        mode: Mode? = null,
        planEnabled: Boolean? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "sendGoalCommand",
        payload = buildMap {
            put("text", text)
            displayText?.let { put("displayText", it) }
            modelSelection?.let { put("modelSelection", it.toWire()) }
            mode?.let { put("mode", it.wire) }
            planEnabled?.let { put("planEnabled", it) }
        },
    )

    // ── turn control ──────────────────────────────────────────────────────

    /**
     * Stop the running turn.
     *
     * [expectedForegroundExecutionId] comes from `activeWorks`; passing it makes
     * the host refuse a late stop that would otherwise kill an unrelated later
     * execution, answering `noop` / `guard.stopTargetChanged`.
     */
    fun stop(
        clientId: String,
        sessionId: String,
        expectedForegroundExecutionId: String? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "stop",
        payload = buildMap {
            expectedForegroundExecutionId?.let { put("expectedForegroundExecutionId", it) }
        },
    )

    /** Compact the conversation. CAS-exempt; admission is idempotent by commandId. */
    fun compact(
        clientId: String,
        sessionId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "compact",
        payload = emptyMap<String, Any?>(),
    )

    fun pauseGoal(
        clientId: String,
        sessionId: String,
        baseRevision: Long?,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "pauseGoal",
        payload = emptyMap<String, Any?>(),
    )

    fun resumeGoal(
        clientId: String,
        sessionId: String,
        baseRevision: Long?,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "resumeGoal",
        payload = emptyMap<String, Any?>(),
    )

    // ── transcript editing ────────────────────────────────────────────────

    /**
     * Edit a user message — this is also the conversation-rewind entry point.
     *
     * There is no separate rewind command. The target must be the latest real
     * user row (`guard.latestQueryEditOnly`). [workspaceMode] defaults to
     * `preserve`; `rewind` restores that turn's files first and can come back
     * `blocked` when the workspace rewind is unsafe.
     */
    fun editUserQuery(
        clientId: String,
        sessionId: String,
        target: RowTarget,
        newText: String,
        attachments: List<AttachmentRef> = emptyList(),
        workspaceMode: WorkspaceMode? = null,
        baseRevision: Long,
        baseLogEpoch: String,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "editUserQuery",
        payload = buildMap {
            put("target", target.toWire())
            put("newText", newText)
            workspaceMode?.let { put("workspaceMode", it.wire) }
            if (attachments.isNotEmpty()) put("attachments", attachments.map { it.toWire() })
        },
    )

    /** Re-run an assistant turn. The target must be the latest assistant row. */
    fun retryTurn(
        clientId: String,
        sessionId: String,
        target: RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "retryTurn",
        payload = linkedMapOf("target" to target.toWire()),
    )

    /** Fork the conversation at an assistant row into a new session. */
    fun forkAssistant(
        clientId: String,
        sessionId: String,
        target: RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "forkAssistant",
        payload = linkedMapOf("target" to target.toWire()),
    )

    /**
     * Undo the file changes of a turn **without** truncating chat history.
     *
     * Distinct from [editUserQuery] with `rewind`, which does both.
     */
    fun applyFileRewind(
        clientId: String,
        sessionId: String,
        target: RowTarget,
        baseRevision: Long,
        baseLogEpoch: String,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "applyFileRewind",
        payload = linkedMapOf("target" to target.toWire()),
    )

    /** Thumbs up / down on an assistant message; `null` clears the rating. */
    fun setAssistantFeedback(
        clientId: String,
        sessionId: String,
        target: RowTarget,
        feedback: Feedback?,
        baseRevision: Long,
        baseLogEpoch: String,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "setAssistantFeedback",
        payload = linkedMapOf<String, Any?>(
            "target" to target.toWire(),
            "feedback" to feedback?.wire,
        ),
    )

    // ── queue ─────────────────────────────────────────────────────────────

    /**
     * Promote a queued item to run now.
     *
     * `queueItemId` shares an id space with the core's `pendingInputId`, so the
     * full queue item must be read before calling — a text-only fallback would
     * lose the source command id and attachments.
     */
    fun sendQueuedNow(
        clientId: String,
        sessionId: String,
        queueItemId: String,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "sendQueuedNow",
        payload = linkedMapOf("queueItemId" to queueItemId),
    )

    /** Rewrite a queued item in place, keeping its position. */
    fun editQueueItem(
        clientId: String,
        sessionId: String,
        queueItemId: String,
        newText: String,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "editQueueItem",
        payload = linkedMapOf(
            "queueItemId" to queueItemId,
            "newText" to newText,
        ),
    )

    /** Move a queued item; `beforeQueueItemId = null` moves it to the tail. */
    fun reorderQueueItem(
        clientId: String,
        sessionId: String,
        queueItemId: String,
        beforeQueueItemId: String?,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "reorderQueueItem",
        payload = linkedMapOf(
            "queueItemId" to queueItemId,
            "beforeQueueItemId" to beforeQueueItemId,
        ),
    )

    /** Drop a queued item. A miss is `noop` / `queue.itemMissing`, not a failure. */
    fun deleteQueueItem(
        clientId: String,
        sessionId: String,
        queueItemId: String,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "deleteQueueItem",
        payload = linkedMapOf("queueItemId" to queueItemId),
    )

    /**
     * Turn automatic queue draining on or off.
     *
     * Turning it on also wakes an idle paused queue, so the flag is not merely
     * bookkeeping.
     */
    fun setAutoDrain(
        clientId: String,
        sessionId: String,
        autoDrain: Boolean,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "setAutoDrain",
        payload = linkedMapOf("autoDrain" to autoDrain),
    )

    // ── interactions ──────────────────────────────────────────────────────

    /**
     * Answer a blocking interaction — this is how a permission prompt or an
     * `AskUserQuestion` gets resolved from the phone.
     *
     * [optionId] comes from the pending interaction's option list; [freeText]
     * carries the optional reason a deny may accept. Resolution is
     * first-come-first-served and late repeats are idempotent successes.
     */
    fun resolveInteraction(
        clientId: String,
        sessionId: String,
        interactionId: String,
        optionId: String? = null,
        freeText: String? = null,
        action: Action? = null,
        content: Map<String, Any?>? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "resolveInteraction",
        payload = linkedMapOf(
            "interactionId" to interactionId,
            "answer" to buildMap {
                optionId?.let { put("optionId", it) }
                freeText?.let { put("freeText", it) }
                action?.let { put("action", it.wire) }
                content?.let { put("content", it) }
            },
        ),
    )

    /** Permanently stop the auto-resolution countdown for one question. */
    fun snoozeInteractionAutoResolution(
        clientId: String,
        sessionId: String,
        interactionId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "snoozeInteractionAutoResolution",
        payload = linkedMapOf("interactionId" to interactionId),
    )

    // ── workspace hook review ─────────────────────────────────────────────

    /**
     * Identity of a workspace hook review flow.
     *
     * `bundleDigest` is a `sha256:<64-hex>`-shaped digest; `generation` guards
     * against answering a superseded review.
     */
    data class HookReviewTarget(
        val sessionId: String,
        val taskId: String,
        val runId: String,
        val workspaceIdentity: String,
        val bundleDigest: String,
        val reviewFlowId: String,
        val generation: Int,
        val interactionId: String,
        val remoteSessionId: String? = null,
    ) {
        fun toWire(): Map<String, Any?> = buildMap {
            put("sessionId", sessionId)
            put("taskId", taskId)
            put("runId", runId)
            remoteSessionId?.let { put("remoteSessionId", it) }
            put("workspaceIdentity", workspaceIdentity)
            put("bundleDigest", bundleDigest)
            put("reviewFlowId", reviewFlowId)
            put("generation", generation)
            put("interactionId", interactionId)
        }
    }

    fun respondWorkspaceHookReview(
        clientId: String,
        target: HookReviewTarget,
        action: HookReviewDecisionAction,
        reviewItemIds: List<String>,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        // The host requires these to agree, else `workspace_hooks_snapshot_mismatch`.
        sessionId = target.sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "respondWorkspaceHookReview",
        payload = buildMap {
            putAll(target.toWire())
            put(
                "decision",
                linkedMapOf(
                    "action" to action.wire,
                    "reviewItemIds" to reviewItemIds,
                ),
            )
        },
    )

    enum class HookReviewDecisionAction(val wire: String) {
        TrustSelected("trust_selected"),
    }

    fun toggleWorkspaceHookReviewItem(
        clientId: String,
        target: HookReviewTarget,
        reviewItemId: String,
        enabled: Boolean,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = target.sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "toggleWorkspaceHookReviewItem",
        payload = buildMap {
            putAll(target.toWire())
            put("reviewItemId", reviewItemId)
            put("enabled", enabled)
        },
    )

    /** Revoke previously granted trust for specific hook review items. */
    fun revokeWorkspaceHookTrust(
        clientId: String,
        sessionId: String,
        target: HookReviewTarget,
        reviewItemIds: List<String>,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "revokeWorkspaceHookTrust",
        payload = buildMap {
            putAll(target.toWire())
            put("reviewItemIds", reviewItemIds)
        },
    )

    /** Open (or reuse) the hook review flow for this workspace. */
    fun requestWorkspaceHookReview(
        clientId: String,
        sessionId: String,
        workspaceIdentity: String,
        bundleDigest: String,
        remoteSessionId: String? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "requestWorkspaceHookReview",
        payload = buildMap {
            put("sessionId", sessionId)
            remoteSessionId?.let { put("remoteSessionId", it) }
            put("workspaceIdentity", workspaceIdentity)
            put("bundleDigest", bundleDigest)
        },
    )

    // ── model / mode ──────────────────────────────────────────────────────

    /**
     * Switch the model for this session.
     *
     * `thought` is required by the host even when the chosen model exposes no
     * levels, so callers pass whatever the model's level list declares (or the
     * session's current value). Same value answers `noop` / `config.unchanged`.
     */
    fun switchModelConfig(
        clientId: String,
        sessionId: String,
        provider: String,
        model: String,
        thought: String,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "switchModelConfig",
        payload = linkedMapOf(
            "provider" to provider,
            "model" to model,
            "thought" to thought,
        ),
    )

    /** Switch agent collaboration mode. `auto` is runtime-only and not offered. */
    fun switchCollaborationMode(
        clientId: String,
        sessionId: String,
        mode: Mode,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "switchCollaborationMode",
        payload = linkedMapOf("mode" to mode.wire),
    )

    /** Toggle how a new input relates to a running turn (`queue` vs `guide`). */
    fun setFollowupMode(
        clientId: String,
        sessionId: String,
        followup: Followup,
        baseRevision: Long,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "setFollowupMode",
        payload = linkedMapOf("mode" to followup.wire),
    )

    // ── background work / workflows (revision-free by design) ─────────────

    /** Cancel a background task. `workId` is the task id. */
    fun cancelBackgroundWork(
        clientId: String,
        sessionId: String,
        workId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "cancelBackgroundWork",
        payload = linkedMapOf("workId" to workId),
    )

    /** Resume a cancelled or interrupted workflow run. `workId` is the run id. */
    fun resumeWorkflowRun(
        clientId: String,
        sessionId: String,
        workId: String,
        name: String? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "resumeWorkflowRun",
        payload = buildMap {
            put("workId", workId)
            name?.let { put("name", it) }
        },
    )

    /** Start a saved workflow in a new session. */
    fun startSavedWorkflow(
        clientId: String,
        sessionId: String,
        name: String,
        scope: WorkflowScope? = null,
        args: Map<String, Any?>? = null,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "startSavedWorkflow",
        payload = buildMap {
            put("name", name)
            scope?.let { put("scope", it.wire) }
            args?.let { put("args", it) }
        },
    )

    /**
     * Amend a running workflow run's settings.
     *
     * Both settings are three-state: omitted keeps, `null` resets to the
     * default, a value sets. Send only what the user actually changed.
     */
    fun amendWorkflowRunSettings(
        clientId: String,
        sessionId: String,
        workId: String,
        subagentModel: String? = null,
        maxConcurrency: Int? = null,
        clearSubagentModel: Boolean = false,
        clearMaxConcurrency: Boolean = false,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "amendWorkflowRunSettings",
        payload = buildMap {
            put("workId", workId)
            when {
                clearSubagentModel -> put("subagentModel", null)
                subagentModel != null -> put("subagentModel", subagentModel)
            }
            when {
                clearMaxConcurrency -> put("maxConcurrency", null)
                maxConcurrency != null -> put("maxConcurrency", maxConcurrency)
            }
        },
    )

    /** Drop a shared context that is staged but not yet consumed. */
    fun discardSharedContext(
        clientId: String,
        sessionId: String,
        contextId: String,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = "discardSharedContext",
        payload = linkedMapOf("contextId" to contextId),
    )

    // ── generic ───────────────────────────────────────────────────────────

    /**
     * Build an envelope for a command this module does not model.
     *
     * Prefer the named builders: they enforce the CAS requirements, which this
     * one cannot check for an unknown type.
     */
    fun envelopeFor(
        type: String,
        clientId: String,
        sessionId: String?,
        payload: Any?,
        baseRevision: Long? = null,
        baseLogEpoch: String? = null,
        commandId: String = newCommandId(),
    ): Map<String, Any?> = envelope(
        commandId = commandId,
        clientId = clientId,
        sessionId = sessionId,
        baseRevision = baseRevision,
        baseLogEpoch = baseLogEpoch,
        type = type,
        payload = payload,
    )

    private fun envelope(
        commandId: String,
        clientId: String,
        sessionId: String?,
        baseRevision: Long?,
        baseLogEpoch: String?,
        type: String,
        payload: Any?,
    ): Map<String, Any?> {
        // Fail at build time rather than shipping a command the host will
        // reject: both of these are caller bugs, not runtime conditions.
        if (type in REQUIRES_BASE_REVISION && baseRevision == null) {
            throw IllegalArgumentException("$type requires baseRevision (CAS command)")
        }
        if (type in ROW_TARGETING && baseLogEpoch == null) {
            throw IllegalArgumentException("$type requires baseLogEpoch (row-targeting command)")
        }
        if (type != "createSession" && sessionId == null) {
            throw IllegalArgumentException("only createSession may omit sessionId")
        }

        return buildMap {
            put("commandId", commandId)
            put("clientId", clientId)
            put("sessionId", sessionId)
            baseRevision?.let { put("baseRevision", it) }
            baseLogEpoch?.let { put("baseLogEpoch", it) }
            put("type", type)
            put("payload", payload)
            put("issuedAt", nowMillis())
        }
    }

    /** `commandId` is documented as UUID v7; a v4 UUID is accepted in practice. */
    private fun newCommandId(): String = randomUuid().toString()
}

/** `modelSelectionSchema` — `.strict()`, so nothing extra may be added. */
data class ModelSelection(
    val providerId: String,
    val modelId: String,
    val reasoningLevel: String? = null,
) {
    fun toWire(): Map<String, Any?> = buildMap {
        put("providerId", providerId)
        put("modelId", modelId)
        reasoningLevel?.let { put("options", linkedMapOf("reasoningLevel" to it)) }
    }
}

/** A blocking prompt the agent is waiting on (permission ask, question, hook review). */
data class PendingInteraction(
    val interactionId: String,
    val kind: String?,
    val title: String?,
    val description: String?,
    val options: List<InteractionOption>,
    val raw: Map<String, Any?>,
)

data class InteractionOption(
    val optionId: String,
    val name: String?,
    val kind: String?,
    val description: String?,
    val raw: Map<String, Any?>,
)

/** Parse `snapshot.pendingInteractions` into typed prompts. */
fun parsePendingInteractions(snapshot: Map<String, Any?>): List<PendingInteraction> =
    Json.asList(snapshot["pendingInteractions"]).mapNotNull { entry ->
        val m = Json.asMap(entry)
        val id = Json.asString(m["interactionId"])
            ?: Json.asString(m["requestId"])
            ?: return@mapNotNull null
        PendingInteraction(
            interactionId = id,
            kind = Json.asString(m["kind"]) ?: Json.asString(m["type"]),
            title = Json.asString(m["title"]),
            description = Json.asString(m["description"]),
            options = Json.asList(m["options"]).map { o ->
                val om = Json.asMap(o)
                InteractionOption(
                    optionId = Json.asString(om["optionId"]) ?: "",
                    name = Json.asString(om["name"]),
                    kind = Json.asString(om["kind"]),
                    description = Json.asString(om["description"]),
                    raw = om,
                )
            },
            raw = m,
        )
    }
