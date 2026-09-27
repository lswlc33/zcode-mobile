package dev.zcodemobile.protocol

/**
 * Conversation row. Discriminated by [kind], not `type`.
 *
 * Observed kinds: `userInput`, `assistantText`, `reasoning`, `toolCall`,
 * plus `turnHeader` / `artifact` / `subagent` / `hookInvocation` /
 * `timelineMarker`.
 */
data class Row(
    val rowId: Long,
    val kind: String,
    /**
     * Stable entity identity, required by every row-targeting command.
     *
     * `rowId` is only meaningful inside one projection generation, so a command
     * that quotes a row must send this too — and the host validates both
     * against the same projection.
     */
    val entityId: String?,
    val text: String?,
    val toolName: String?,
    val status: String?,
    val inputText: String?,
    val outputText: String?,
    val state: String?,
    val createdAt: Long?,
    /** `like` / `dislike` on an assistant row. */
    val feedback: String?,
    /** What the host says this row supports; absent keys are simply not offered. */
    val actions: RowActions,
    val raw: Map<String, Any?>,
) {
    /** Whether this row can be the target of a row-targeting command at all. */
    val hasEntity: Boolean get() = !entityId.isNullOrBlank()

    val isUserInput: Boolean get() = kind == "userInput"
    val isAssistant: Boolean get() = kind == "assistantText"

    /** Streaming rows have no actions yet — the turn is still open. */
    val isStreaming: Boolean get() = state == "streaming"
}

/** `rowActionsSchema` — the host declares which actions a row supports. */
data class RowActions(
    val canFork: Boolean = false,
    val canEdit: Boolean = false,
    val canRetry: Boolean = false,
    val canRewindFiles: Boolean = false,
    /** `rewind` (edit in place) or `fork` (branch into a new session). */
    val editDisposition: String? = null,
) {
    val hasAny: Boolean get() = canFork || canEdit || canRetry || canRewindFiles
}

/** Client-side projection of a v4 conversation. */
data class ConversationState(
    val sessionId: String? = null,
    val title: String? = null,
    val titleSource: String? = null,
    val phase: String? = null,
    val canStop: Boolean = false,
    val sessionEnded: Boolean = false,
    val rows: List<Row> = emptyList(),
    val totalCount: Int = 0,
    val logEpoch: String? = null,
    /** CAS base for commands. Sourced from `snapshot.revision` /
     *  `state.updated.patch.revision`. */
    val revision: Long = 0,
    val seq: Long = 0,
    val pending: List<PendingInteraction> = emptyList(),
    // ── composer state, from `snapshot.config` ──
    val model: String? = null,
    val provider: String? = null,
    val thoughtLevel: String? = null,
    val mode: String? = null,
    val followupMode: String? = null,
    val planEnabled: Boolean = false,
    // ── from `snapshot.usage` ──
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    /** Per-category split and cache hit rate behind the context pill. */
    val usage: ContextUsage = ContextUsage(),
    /** Thinking budget levels this model exposes (e.g. disabled/low/high/max). */
    val thoughtLevels: List<String> = emptyList(),
    // ── input routing / availability ──
    /** `startNow | enqueue | guide | reject | choice`. */
    val inputRoutingMode: String? = null,
    val inputRoutingReason: String? = null,
    /** Action gate map. Advisory UI state, never a substitute for the ack. */
    val availability: Map<String, ActionAvailability> = emptyMap(),
    // ── queue ──
    val queue: QueueState = QueueState(),
    // ── goal / background work ──
    val goal: GoalState? = null,
    val backgroundWorks: List<BackgroundWork> = emptyList(),
    val subagents: SubagentState = SubagentState(),
    /** Foreground execution id, for a late-`stop` guard. */
    val activeWorks: List<ActiveWork> = emptyList(),
    // ── history paging (`v4/conversation/rowsRange`) ──
    val hasMoreOlder: Boolean = true,
    val loadingOlder: Boolean = false,
    /** False until the first snapshot lands: "rows empty" then means "still
     *  loading", not "empty conversation". */
    val hasSnapshot: Boolean = false,
) {
    fun rowById(rowId: Long): Row? = rows.firstOrNull { it.rowId == rowId }

    /** 0f..1f, or null when the host reported no window. */
    val contextFraction: Float?
        get() {
            val used = contextUsed ?: return null
            val max = contextMax ?: return null
            if (max <= 0) return null
            return (used.toFloat() / max).coerceIn(0f, 1f)
        }

    /** Derived, per the source comment — never sent on the wire. */
    val hasBackgroundWork: Boolean
        get() = backgroundWorks.any { it.status == "running" }

    val isRunning: Boolean get() = phase == "running" || activeWorks.isNotEmpty()

    /** The foreground execution a `stop` should target, when one is known. */
    val foregroundExecutionId: String?
        get() = activeWorks.firstOrNull { it.foregroundExecutionId != null }?.foregroundExecutionId

    fun allowed(action: String): Boolean = availability[action]?.allowed ?: true

    fun unavailableReason(action: String): String? =
        availability[action]?.takeIf { !it.allowed }?.reasonCode

    /**
     * True when sending would need an explicit queue decision.
     *
     * Under `choice` the host refuses a bare `sendText`, so the UI must ask
     * whether to clear or keep the paused queue first.
     */
    val needsQueueDecision: Boolean get() = inputRoutingMode == "choice"
}

/**
 * Whether an action is currently offered, and why not when it is not.
 *
 * Advisory only: the host still arbitrates, so a command submitted while
 * `allowed = false` may well be accepted.
 */
data class ActionAvailability(val allowed: Boolean, val reasonCode: String? = null)

/** One pending input in the session queue. */
data class QueueItem(
    val queueItemId: String,
    val kind: String,
    val text: String,
    val order: Long?,
    /** `queued | reserved | promoting`. */
    val state: String?,
    val clientId: String?,
    val attachmentCount: Int,
    val raw: Map<String, Any?>,
) {
    /** A `compact` entry is typed maintenance, not an editable prompt. */
    val isMaintenance: Boolean get() = kind == "compact"

    val isReserved: Boolean get() = state == "reserved" || state == "promoting"
}

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val autoDrain: Boolean = true,
    /** `stopped | manual | error`; absent on older snapshots. */
    val pauseReason: String? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** A paused queue is the state `sendQueuedNow` exists to escape. */
    val isPaused: Boolean get() = !autoDrain && items.isNotEmpty()
}

data class GoalState(
    val targetId: String,
    val objective: String,
    val summaryTitle: String?,
    /** `active | paused | verifying | verified | notSatisfied | failed`. */
    val status: String,
    val iteration: Long?,
    val timeUsedSeconds: Long?,
) {
    val isActive: Boolean get() = status == "active"
}

/** A background task, subagent or workflow run. */
data class BackgroundWork(
    val workId: String,
    /** `bash | subagent | workflow`. */
    val kind: String,
    val title: String,
    /** `running | resultPending | failed | cancelled`. */
    val status: String,
    val cancellable: Boolean,
) {
    val isRunning: Boolean get() = status == "running"
}

data class SubagentState(
    val revision: Long = 0,
    val running: List<String> = emptyList(),
    val endedTotal: Long = 0,
)

/** One foreground work item; carries the id a late `stop` must quote. */
data class ActiveWork(
    val kind: String,
    val foregroundExecutionId: String?,
)

/**
 * Pure reducer for the v4 snapshot/delta stream.
 *
 * Op names and semantics come from `zcode-protocol-v4/delta.ts`
 * (`conversationDeltaSchema`). Two of these are easy to get wrong and were
 * only pinned down by capturing live traffic:
 *
 *  - streaming text arrives as **`row.delta`** with `rowId`/`path`/`append`
 *    at the top level — there is no wrapper object and no `row.path.appended`
 *  - whole-row updates arrive as **`row.upserted`**, which *replaces* by
 *    `rowId`; treating it as an append duplicates the row
 *
 * `state.updated` carries a shallow patch whose keys replace wholesale —
 * `statePatchSchema` is a plain object, deliberately never deep-merged
 * (see the comment on `conversationSnapshotSchema`).
 */
object ConversationReducer {

    /** Field paths the host may append to; mirrors `streamablePathSchema`. */
    private val STREAMABLE = setOf("text", "inputText", "output.text", "summaryText")

    fun applySnapshot(frame: Map<String, Any?>, snapshot: Map<String, Any?>): ConversationState {
        val rowsObj = Json.asMap(snapshot["rows"])
        val control = Json.asMap(snapshot["control"])
        val meta = Json.asMap(snapshot["meta"])
        val config = Json.asMap(snapshot["config"])
        val usage = Json.asMap(Json.asMap(snapshot["usage"])["contextWindow"])

        return ConversationState(
            sessionId = Json.asString(snapshot["sessionId"]),
            title = Json.asString(meta["title"]),
            titleSource = Json.asString(meta["titleSource"]),
            phase = Json.asString(control["phase"]),
            canStop = Json.asBool(control["canStop"]) ?: false,
            sessionEnded = Json.asBool(control["sessionEnded"]) ?: false,
            rows = Json.asList(rowsObj["window"]).map { toRow(Json.asMap(it)) },
            totalCount = Json.asLong(rowsObj["totalCount"])?.toInt()
                ?: Json.asList(rowsObj["window"]).size,
            logEpoch = Json.asString(snapshot["logEpoch"]),
            revision = Json.asLong(snapshot["revision"]) ?: 0,
            seq = Json.asLong(frame["toSeq"]) ?: 0,
            pending = parsePendingInteractions(snapshot),
            model = Json.asString(config["model"]),
            provider = Json.asString(config["provider"]),
            thoughtLevel = Json.asString(config["thought"]),
            mode = Json.asString(config["mode"]),
            followupMode = Json.asString(config["followupMode"]),
            planEnabled = Json.asBool(config["planEnabled"]) ?: false,
            usage = parseContextUsage(snapshot),
            thoughtLevels = Json.asList(config["thoughtLevels"]).mapNotNull { Json.asString(it) },
            contextUsed = Json.asLong(usage["usedTokens"]),
            contextMax = Json.asLong(usage["maxTokens"]),
            inputRoutingMode = Json.asString(Json.asMap(snapshot["inputRouting"])["mode"]),
            inputRoutingReason = Json.asString(Json.asMap(snapshot["inputRouting"])["reasonCode"]),
            availability = parseAvailability(snapshot["availability"]),
            queue = parseQueue(snapshot["queue"]),
            goal = parseGoal(snapshot["goal"]),
            backgroundWorks = parseBackgroundWorks(snapshot["backgroundWorks"]),
            subagents = parseSubagents(snapshot["subagents"]),
            activeWorks = parseActiveWorks(control["activeWorks"]),
            hasSnapshot = true,
        )
    }

    /**
     * Fold a batch of deltas. Unknown ops are ignored rather than throwing:
     * the host ships new op kinds additively, and a phone client that hard
     * fails on an unfamiliar op would stop rendering mid-conversation.
     */
    fun applyDeltas(
        current: ConversationState,
        deltas: List<Any?>,
        onUnknownOp: ((String) -> Unit)? = null,
    ): ConversationState {
        var state = current
        for (d in deltas) {
            val op = Json.asMap(d)
            when (val name = Json.asString(op["op"])) {
                "row.appended" -> {
                    val row = toRow(Json.asMap(op["row"]))
                    val idx = state.rows.indexOfFirst { it.rowId == row.rowId }
                    state = if (idx >= 0) {
                        // The host can re-append a rowId that is already
                        // projected (rewind / replay edges). A second entry
                        // with the same rowId crashes LazyColumn duplicate
                        // keys, so a repeat replaces instead of appending.
                        state.copy(rows = state.rows.toMutableList().also { it[idx] = row })
                    } else {
                        state.copy(
                            rows = state.rows + row,
                            totalCount = state.totalCount + 1,
                        )
                    }
                }
                "row.upserted" -> {
                    val row = toRow(Json.asMap(op["row"]))
                    val idx = state.rows.indexOfFirst { it.rowId == row.rowId }
                    val rows = if (idx >= 0) {
                        state.rows.toMutableList().also { it[idx] = row }
                    } else {
                        state.rows + row
                    }
                    state = state.copy(
                        rows = rows,
                        totalCount = if (idx >= 0) state.totalCount else state.totalCount + 1,
                    )
                }
                "row.removed" -> {
                    // Removes this row and everything after it (edit/retry branch).
                    val from = Json.asLong(op["fromRowId"]) ?: return state
                    state = state.copy(
                        rows = state.rows.filterNot { it.rowId >= from },
                    )
                }
                "row.delta" -> {
                    val rowId = Json.asLong(op["rowId"]) ?: continue
                    val path = Json.asString(op["path"]) ?: continue
                    val append = Json.asString(op["append"]) ?: continue
                    if (path !in STREAMABLE || append.isEmpty()) continue
                    state = state.copy(
                        rows = state.rows.map { r ->
                            if (r.rowId != rowId) r else appendTo(r, path, append)
                        },
                    )
                }
                "state.updated" -> state = applyPatch(state, Json.asMap(op["patch"]))
                "workflowRun.updated" -> Unit // not projected on mobile
                else -> if (name != null) onUnknownOp?.invoke(name)
            }
        }
        return state
    }

    /**
     * Prepend a page of history fetched by `v4/conversation/rowsRange`.
     *
     * The host returns rows ascending and may overlap what the tail window
     * already contains, so duplicates are dropped by `rowId` rather than
     * assumed away.
     */
    fun prependOlder(
        state: ConversationState,
        older: Any?,
        hasMore: Boolean,
    ): ConversationState {
        val existing = state.rows.mapTo(HashSet()) { it.rowId }
        val prepended = Json.asList(older)
            .map { toRow(Json.asMap(it)) }
            .filter { it.rowId !in existing }
        return state.copy(
            rows = prepended + state.rows,
            hasMoreOlder = hasMore,
            loadingOlder = false,
        )
    }

    /**
     * Shallow patch: every present key replaces its whole subtree.
     *
     * Key **presence** is the signal, not truthiness — the host clears
     * `pendingInteractions` with a present-but-empty array, and a deep merge
     * would leave the dismissed prompt on screen.
     */
    private fun applyPatch(state: ConversationState, patch: Map<String, Any?>): ConversationState {
        if (patch.isEmpty()) return state

        val control = Json.asMap(patch["control"])
        val meta = Json.asMap(patch["meta"])
        val config = Json.asMap(patch["config"])
        val usage = Json.asMap(Json.asMap(patch["usage"])["contextWindow"])
        val routing = Json.asMap(patch["inputRouting"])

        return state.copy(
            revision = Json.asLong(patch["revision"]) ?: state.revision,
            phase = Json.asString(control["phase"]) ?: state.phase,
            canStop = Json.asBool(control["canStop"]) ?: state.canStop,
            sessionEnded = Json.asBool(control["sessionEnded"]) ?: state.sessionEnded,
            title = Json.asString(meta["title"]) ?: state.title,
            titleSource = Json.asString(meta["titleSource"]) ?: state.titleSource,
            // Present-but-empty must clear the list, so branch on key presence.
            pending = if (patch.containsKey("pendingInteractions")) {
                parsePendingInteractions(patch)
            } else {
                state.pending
            },
            // config / usage arrive as whole-subtree replacements too.
            model = Json.asString(config["model"]) ?: state.model,
            provider = Json.asString(config["provider"]) ?: state.provider,
            thoughtLevel = Json.asString(config["thought"]) ?: state.thoughtLevel,
            mode = Json.asString(config["mode"]) ?: state.mode,
            followupMode = Json.asString(config["followupMode"]) ?: state.followupMode,
            planEnabled = Json.asBool(config["planEnabled"]) ?: state.planEnabled,
            contextUsed = Json.asLong(usage["usedTokens"]) ?: state.contextUsed,
            contextMax = Json.asLong(usage["maxTokens"]) ?: state.contextMax,
            usage = if (patch.containsKey("usage")) parseContextUsage(patch) else state.usage,
            thoughtLevels = Json.asList(config["thoughtLevels"]).mapNotNull { Json.asString(it) }
                .ifEmpty { state.thoughtLevels },
            inputRoutingMode = Json.asString(routing["mode"]) ?: state.inputRoutingMode,
            inputRoutingReason = if (patch.containsKey("inputRouting")) {
                Json.asString(routing["reasonCode"])
            } else {
                state.inputRoutingReason
            },
            availability = if (patch.containsKey("availability")) {
                parseAvailability(patch["availability"])
            } else {
                state.availability
            },
            queue = if (patch.containsKey("queue")) parseQueue(patch["queue"]) else state.queue,
            // `goal: null` is a real value (no goal), so presence decides.
            goal = if (patch.containsKey("goal")) parseGoal(patch["goal"]) else state.goal,
            backgroundWorks = if (patch.containsKey("backgroundWorks")) {
                parseBackgroundWorks(patch["backgroundWorks"])
            } else {
                state.backgroundWorks
            },
            subagents = if (patch.containsKey("subagents")) {
                parseSubagents(patch["subagents"])
            } else {
                state.subagents
            },
            activeWorks = if (control.containsKey("activeWorks")) {
                parseActiveWorks(control["activeWorks"])
            } else {
                state.activeWorks
            },
        )
    }

    // ── snapshot sub-parsers ──────────────────────────────────────────────

    /**
     * `sessionActionAvailabilitySchema` — a map of action name to gate.
     *
     * Values are advisory: the host still arbitrates, and a command submitted
     * while `allowed = false` can be accepted.
     */
    fun parseAvailability(v: Any?): Map<String, ActionAvailability> =
        Json.asMap(v).mapValues { (_, gate) ->
            val g = Json.asMap(gate)
            ActionAvailability(
                allowed = Json.asBool(g["allowed"]) ?: true,
                reasonCode = Json.asString(g["reasonCode"]),
            )
        }

    fun parseQueue(v: Any?): QueueState {
        val q = Json.asMap(v)
        return QueueState(
            items = Json.asList(q["items"]).map { toQueueItem(Json.asMap(it)) },
            autoDrain = Json.asBool(q["autoDrain"]) ?: true,
            pauseReason = Json.asString(q["pauseReason"]),
        )
    }

    fun toQueueItem(m: Map<String, Any?>): QueueItem = QueueItem(
        queueItemId = Json.asString(m["queueItemId"]) ?: "",
        kind = Json.asString(m["kind"]) ?: "sendText",
        text = Json.asString(m["text"]) ?: "",
        order = Json.asLong(m["order"]),
        state = Json.asString(Json.asMap(m["dispatch"])["state"]),
        clientId = Json.asString(m["clientId"]),
        attachmentCount = Json.asList(m["attachments"]).size,
        raw = m,
    )

    fun parseGoal(v: Any?): GoalState? {
        val g = Json.asMap(v)
        if (g.isEmpty()) return null
        return GoalState(
            targetId = Json.asString(g["targetId"]) ?: "",
            objective = Json.asString(g["objective"]) ?: "",
            summaryTitle = Json.asString(g["summaryTitle"]),
            status = Json.asString(g["status"]) ?: "active",
            iteration = Json.asLong(g["iteration"]),
            timeUsedSeconds = Json.asLong(g["timeUsedSeconds"]),
        )
    }

    fun parseBackgroundWorks(v: Any?): List<BackgroundWork> =
        Json.asList(v).map { w ->
            val m = Json.asMap(w)
            BackgroundWork(
                workId = Json.asString(m["workId"]) ?: "",
                kind = Json.asString(m["kind"]) ?: "",
                title = Json.asString(m["title"]) ?: "",
                status = Json.asString(m["status"]) ?: "",
                cancellable = Json.asBool(m["cancellable"]) ?: false,
            )
        }

    fun parseSubagents(v: Any?): SubagentState {
        val s = Json.asMap(v)
        return SubagentState(
            revision = Json.asLong(s["revision"]) ?: 0,
            running = Json.asList(s["running"]).mapNotNull { Json.asString(it) },
            endedTotal = Json.asLong(s["endedTotal"]) ?: 0,
        )
    }

    fun parseActiveWorks(v: Any?): List<ActiveWork> =
        Json.asList(v).map { w ->
            val m = Json.asMap(w)
            ActiveWork(
                kind = Json.asString(m["kind"]) ?: "",
                foregroundExecutionId = Json.asString(m["foregroundExecutionId"]),
            )
        }

    private fun appendTo(row: Row, path: String, chunk: String): Row = when (path) {
        "text" -> row.copy(text = (row.text ?: "") + chunk)
        "inputText" -> row.copy(inputText = (row.inputText ?: "") + chunk)
        "output.text" -> row.copy(outputText = (row.outputText ?: "") + chunk)
        else -> row
    }

    fun toRow(m: Map<String, Any?>): Row {
        val actions = Json.asMap(m["actions"])
        return Row(
            rowId = Json.asLong(m["rowId"]) ?: 0L,
            kind = Json.asString(m["kind"]) ?: "unknown",
            entityId = Json.asString(m["entityId"]),
            text = Json.asString(m["text"]),
            toolName = Json.asString(m["toolName"]),
            status = Json.asString(m["status"]),
            inputText = Json.asString(m["inputText"]),
            outputText = Json.asString(Json.asMap(m["output"])["text"]),
            state = Json.asString(m["state"]),
            createdAt = Json.asLong(m["createdAt"]),
            feedback = Json.asString(m["feedback"]),
            actions = RowActions(
                canFork = Json.asBool(actions["canFork"]) ?: false,
                canEdit = Json.asBool(actions["canEdit"]) ?: false,
                canRetry = Json.asBool(actions["canRetry"]) ?: false,
                canRewindFiles = Json.asBool(actions["canRewindFiles"]) ?: false,
                editDisposition = Json.asString(actions["editDisposition"]),
            ),
            raw = m,
        )
    }
}
