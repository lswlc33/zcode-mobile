package dev.zcodemobile.protocol

/**
 * Task management surface: the `zcode-task` channel plus the window-level
 * `controller/tasks-index` push.
 *
 * The web sidebar answers every question from these two: reads and mutations
 * go to `zcode-task` (verified live 2026-09-26 — the old
 * `window-controller.mutateTask` no longer appears in sidebar traffic), and
 * the sidebar's live refresh is driven by the `controller/tasks-index` push,
 * which carries every task with its `membership` (pinned / archived / active)
 * and `liveStatus`.
 */
@ZCodeExperimental
class TaskService(private val channel: ChannelClient) {

    companion object {
        const val CHANNEL = "zcode-task"
        const val CONTROLLER_CHANNEL = "window-controller"

        /** Window-wide task rows with membership; the sidebar's live data source. */
        const val TOPIC_TASKS_INDEX = "controller/tasks-index"
        const val TOPIC_WORKSPACES = "controller/workspaces"

        const val EVENT_CONTROLLER_FRAMES = "onDynamicControllerFrame"
    }

    // ── reads ─────────────────────────────────────────────────────────────

    suspend fun listTasks(workspacePath: String): List<TaskMeta> =
        callMetaList("listTasks", workspacePath)

    suspend fun listPinnedTasks(workspacePath: String): List<TaskMeta> =
        callMetaList("listPinnedTasks", workspacePath)

    suspend fun listArchivedTasks(workspacePath: String): List<TaskMeta> =
        callMetaList("listArchivedTasks", workspacePath)

    suspend fun listPinnedTaskIds(): List<String> =
        Json.asList(channel.call(CHANNEL, "listPinnedTaskIds")).mapNotNull { Json.asString(it) }

    suspend fun listDeletedTaskIds(workspacePath: String): List<String> =
        Json.asList(channel.call(CHANNEL, "listDeletedTaskIds", mapOf("workspacePath" to workspacePath)))
            .mapNotNull { Json.asString(it) }

    // ── mutations ─────────────────────────────────────────────────────────

    /** Rename through the task index. No v4 CAS involved (the web does this too). */
    suspend fun renameTask(taskId: String, workspacePath: String, title: String): TaskMeta? =
        metaResult(channel.call(CHANNEL, "renameTask", mutation(taskId, workspacePath, "title" to title)))

    suspend fun setTaskPinned(taskId: String, workspacePath: String, pinned: Boolean): TaskMeta? =
        metaResult(channel.call(CHANNEL, "setTaskPinned", mutation(taskId, workspacePath, "pinned" to pinned)))

    /**
     * Mark read/unread. `expectedUnreadAt` is the compare-and-clear token the
     * host echoes when marking read; passing it makes a stale clear a no-op
     * instead of clobbering a newer unread state.
     */
    suspend fun setTaskUnread(
        taskId: String,
        workspacePath: String,
        unread: Boolean,
        expectedUnreadAt: Long? = null,
    ): TaskMeta? = metaResult(
        channel.call(
            CHANNEL, "setTaskUnread",
            mutation(taskId, workspacePath, "unread" to unread, "expectedUnreadAt" to expectedUnreadAt),
        )
    )

    suspend fun archiveTask(taskId: String, workspacePath: String): TaskMeta? =
        metaResult(channel.call(CHANNEL, "archiveTask", mutation(taskId, workspacePath)))

    suspend fun unarchiveTask(taskId: String, workspacePath: String): TaskMeta? =
        metaResult(channel.call(CHANNEL, "unarchiveTask", mutation(taskId, workspacePath)))

    /** Permanently hide an archived task from the list (CLI history survives). */
    suspend fun deleteArchivedTask(taskId: String, workspacePath: String): Boolean =
        channel.call(
            CHANNEL, "deleteArchivedTask",
            mapOf("taskId" to taskId, "workspacePath" to workspacePath),
        ) == true

    // ── controller/tasks-index push ───────────────────────────────────────

    /**
     * Subscribe to the window-wide task index.
     *
     * The frame listener must be registered **before** the subscribe call —
     * the snapshot follows the ack immediately, same ordering rule as the
     * v4 agent topics. Frames are handed over raw ([TaskIndexFrame]); folding
     * them into a [TaskIndexState] is the caller's job, because the state
     * lives across frames in the app layer.
     */
    suspend fun subscribeTasksIndex(
        onFrame: (TaskIndexFrame) -> Unit,
    ): TasksIndexSubscription? {
        val listenerId = channel.listen(
            CONTROLLER_CHANNEL, EVENT_CONTROLLER_FRAMES, null,
        ) { candidate -> onControllerFrame(candidate, onFrame) }
        return try {
            val res = Json.asMap(
                channel.call(
                    CONTROLLER_CHANNEL, "subscribeControllerV4",
                    mapOf("topic" to TOPIC_TASKS_INDEX, "visibility" to "foreground"),
                )
            )
            val ack = Json.asMap(res["ack"])
            TasksIndexSubscription(
                listenerId = listenerId,
                subscriptionId = Json.asString(ack["subscriptionId"]) ?: "",
            )
        } catch (e: Exception) {
            channel.disposeEvent(listenerId)
            throw e
        }
    }

    fun unsubscribeTasksIndex(subscription: TasksIndexSubscription) {
        if (subscription.subscriptionId.isNotEmpty()) {
            // Best-effort: a dropped socket must not mask whatever the caller
            // is doing next; the host drops subscriptions when the bridge dies.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    channel.call(
                        CONTROLLER_CHANNEL, "unsubscribeControllerV4",
                        mapOf("subscriptionId" to subscription.subscriptionId),
                        timeoutMs = 5_000,
                    )
                }
            }
        }
        channel.disposeEvent(subscription.listenerId)
    }

    private fun onControllerFrame(candidate: Any?, onFrame: (TaskIndexFrame) -> Unit) {
        val m = Json.asMap(candidate)
        if (Json.asString(m["topic"]) != TOPIC_TASKS_INDEX) return
        val payload = Json.asMap(m["payload"])
        when (Json.asString(payload["kind"])) {
            "snapshot" -> onFrame(
                TaskIndexFrame.Snapshot(m, Json.asMap(payload["snapshot"]))
            )
            "deltas" -> onFrame(TaskIndexFrame.Deltas(Json.asList(payload["deltas"])))
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private suspend fun callMetaList(method: String, workspacePath: String): List<TaskMeta> =
        Json.asList(channel.call(CHANNEL, method, mapOf("workspacePath" to workspacePath)))
            .map { TaskMeta.from(Json.asMap(it)) }

    /** `{taskId, workspacePath, ...extra}`, dropping null extras. */
    private fun mutation(
        taskId: String,
        workspacePath: String,
        vararg extra: Pair<String, Any?>,
    ): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "taskId" to taskId,
            "workspacePath" to workspacePath,
        )
        extra.forEach { (k, v) -> if (v != null) params[k] = v }
        return params
    }

    private fun metaResult(v: Any?): TaskMeta? = v?.let { TaskMeta.from(Json.asMap(it)) }
}

data class TasksIndexSubscription(val listenerId: Int, val subscriptionId: String)

/** One frame of the `controller/tasks-index` topic, before folding. */
sealed interface TaskIndexFrame {
    /** Full replace — the host only sends this from seq 0. */
    data class Snapshot(
        val frame: Map<String, Any?>,
        val snapshot: Map<String, Any?>,
    ) : TaskIndexFrame

    data class Deltas(val deltas: List<Any?>) : TaskIndexFrame
}

/**
 * One task row as the host's task index projects it.
 *
 * `membership` decides which sidebar section a task belongs to — it is index
 * state, not derivable from [TaskMeta] alone.
 */
data class TaskIndexRow(
    val taskId: String,
    val workspacePath: String,
    val meta: TaskMeta,
    val pinned: Boolean,
    val archived: Boolean,
    val active: Boolean,
    val liveStatus: String?,
    /** `idle | running | waiting | completed | error` — the row badge. */
    val phase: String?,
    val raw: Map<String, Any?>,
) {
    /** `pinned` section shows pinned-and-not-archived, matching the host. */
    val isPinnedVisible: Boolean get() = pinned && !archived

    /** `timeline` section: neither pinned nor archived. */
    val isTimeline: Boolean get() = !pinned && !archived

    val isRunning: Boolean get() = liveStatus == "running" || phase == "running"
    val isUnread: Boolean get() = meta.unreadAt != null && meta.unreadAt > 0

    val sortKey: String get() = "${workspacePath} $taskId"
}

/** Conflated latest state of the `controller/tasks-index` topic. */
data class TaskIndexState(
    val logEpoch: String? = null,
    val seq: Long = 0,
    /** Keyed by taskId (a session id, unique across workspaces). */
    val rows: Map<String, TaskIndexRow> = emptyMap(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    val pinned: List<TaskIndexRow>
        get() = rows.values.filter { it.isPinnedVisible }

    val timeline: List<TaskIndexRow>
        get() = rows.values.filter { it.isTimeline }

    val archived: List<TaskIndexRow>
        get() = rows.values.filter { it.archived }

    fun byId(taskId: String): TaskIndexRow? = rows[taskId]
}

/**
 * Reducer for the `controller/tasks-index` topic.
 *
 * Two delta ops: `task.upserted` replaces the row by address, `task.removed`
 * drops it. The snapshot replaces everything — the host sends it from seq 0.
 */
object TaskIndex {

    fun applySnapshot(frame: Map<String, Any?>, snapshot: Map<String, Any?>): TaskIndexState {
        val rows = Json.asList(snapshot["tasks"]).mapNotNull { row ->
            toRow(Json.asMap(row))?.let { it.taskId to it }
        }.toMap()
        return TaskIndexState(
            logEpoch = Json.asString(snapshot["logEpoch"]) ?: Json.asString(frame["logEpoch"]),
            seq = Json.asLong(frame["toSeq"]) ?: 0,
            rows = rows,
        )
    }

    fun applyDeltas(
        current: TaskIndexState,
        deltas: List<Any?>,
        onUnknownOp: ((String) -> Unit)? = null,
    ): TaskIndexState {
        var rows = current.rows
        for (d in deltas) {
            val op = Json.asMap(d)
            when (val name = Json.asString(op["op"])) {
                "task.upserted" -> {
                    val row = toRow(Json.asMap(op["task"])) ?: continue
                    rows = rows + (row.taskId to row)
                }
                "task.removed" -> {
                    val address = Json.asMap(op["address"])
                    val id = Json.asString(address["taskId"]) ?: continue
                    rows = rows - id
                }
                else -> if (name != null) onUnknownOp?.invoke(name)
            }
        }
        return current.copy(rows = rows, seq = maxOf(current.seq, 0L))
    }

    fun toRow(m: Map<String, Any?>): TaskIndexRow? {
        val meta = TaskMeta.from(Json.asMap(m["meta"]))
        val address = Json.asMap(m["address"])
        val taskId = Json.asString(address["taskId"]) ?: meta.taskId
        if (taskId.isEmpty()) return null
        val membership = Json.asMap(m["membership"])
        val activity = Json.asMap(m["activity"])
        return TaskIndexRow(
            taskId = taskId,
            workspacePath = Json.asString(address["workspacePath"]) ?: meta.workspacePath,
            meta = meta,
            pinned = Json.asBool(membership["pinned"]) ?: false,
            archived = Json.asBool(membership["archived"]) ?: false,
            active = Json.asBool(membership["active"]) ?: false,
            liveStatus = Json.asString(m["liveStatus"]),
            phase = Json.asString(activity["phase"]),
            raw = m,
        )
    }
}

/**
 * Task metadata as persisted by the task index
 * (`ZCodeTaskMeta`, `packages/shared/src/zcode-task-types-core.ts`).
 */
data class TaskMeta(
    val taskId: String,
    val title: String,
    /** The user renamed it; auto-titling stops. */
    val titleOverridden: Boolean,
    val workspacePath: String,
    val createdAt: Long?,
    val updatedAt: Long?,
    val mode: String?,
    val model: String?,
    val provider: String?,
    /** `running | completed | error` — last prompt outcome. */
    val status: String?,
    /** Millis of the last unread mark; absent/0 = read. */
    val unreadAt: Long?,
    val raw: Map<String, Any?>,
) {
    val displayTitle: String get() = title.ifBlank { "(无标题)" }

    companion object {
        fun from(m: Map<String, Any?>): TaskMeta = TaskMeta(
            taskId = Json.asString(m["taskId"]) ?: "",
            title = Json.asString(m["title"]) ?: "",
            titleOverridden = Json.asBool(m["titleOverridden"]) ?: false,
            workspacePath = Json.asString(m["workspacePath"]) ?: "",
            createdAt = Json.asLong(m["createdAt"]),
            updatedAt = Json.asLong(m["updatedAt"]),
            mode = Json.asString(m["mode"]),
            model = Json.asString(m["model"]),
            provider = Json.asString(m["provider"]),
            status = Json.asString(m["status"]),
            unreadAt = Json.asLong(m["unreadAt"]),
            raw = m,
        )
    }
}
