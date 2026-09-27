package dev.zcodemobile.app.ui.sessions

import dev.zcodemobile.protocol.SessionIndexEntry
import dev.zcodemobile.protocol.SessionsIndexState
import dev.zcodemobile.protocol.TaskIndexRow
import dev.zcodemobile.protocol.TaskIndexState
import dev.zcodemobile.protocol.TaskSummary

/**
 * The home list projection: three desktop sources folded into one row shape
 * and one order that several parallel sessions cannot shake.
 *
 * Sources:
 *  - `controller/tasks-index` — window-wide and live, and the only one that
 *    knows membership (pinned / archived / unread).
 *  - `sessions-index/<workspaceId>` — live, but only for the bridged
 *    workspace: current phase plus the one-line assistant preview.
 *  - the bootstrap snapshot — every workspace, frozen at connect time.
 *
 * Everything here is pure so the ordering rules can be tested on the JVM: the
 * churn they prevent only shows up with several sessions streaming at once,
 * which is exactly what is awkward to reproduce by hand.
 */
object HomeProjection {

    /**
     * Activity, quantised to whole minutes before it becomes an order key.
     *
     * `updatedAt` / `lastActivityAt` advance during a running turn, so ordering
     * by the raw value re-ranks the list on every upsert the desktop pushes.
     * With several sessions running in parallel — in one folder or across
     * folders — rows and whole project sections traded places every few seconds
     * while the user was reading them, and `animateItem` turned each swap into
     * a visible slide. Quantising collapses the noise into "which minute was it
     * last active in": sessions active in the same minute keep a fixed relative
     * order, so the list only moves when activity is genuinely a minute behind.
     */
    const val ACTIVITY_BUCKET_MS = 60_000L

    private const val UNTITLED = "(无标题)"

    /** Fits the preview inside one ellipsised line of the row subtitle. */
    private const val PREVIEW_MAX = 48

    /**
     * One row per task, merging the connect-time snapshot with the live index.
     *
     * The live stream is authoritative for the workspace it watches: it adds
     * tasks created since the bootstrap and drops ones closed since, so the
     * snapshot rows for that workspace that it no longer lists are retired
     * here. Every other workspace keeps the snapshot, which is why the two
     * sources are merged rather than one replacing the other.
     */
    fun rows(
        index: SessionsIndexState,
        tasks: List<TaskSummary>,
        bridged: String?,
    ): List<SessionRow> {
        val byId = LinkedHashMap<String, SessionRow>(tasks.size * 2)
        tasks.forEach { t ->
            byId[t.taskId] = SessionRow(
                sessionId = t.taskId,
                title = t.title.ifBlank { UNTITLED },
                workspacePath = t.workspacePath,
                workspaceLabel = t.workspaceLabel,
                model = prettyModel(t.model) ?: t.provider,
                status = t.displayStatus,
                isRunning = t.displayStatus == "running",
                updatedAt = t.updatedAt,
                isUnread = t.unread,
            )
        }

        val live = index.sessions.takeIf { bridged != null && index.sessions.isNotEmpty() }
        if (live != null) {
            val liveIds = live.mapTo(HashSet()) { it.sessionId }
            byId.entries.removeAll { (id, row) -> row.workspacePath == bridged && id !in liveIds }
            live.forEach { entry -> byId[entry.sessionId] = entry.toRow(byId[entry.sessionId]) }
        }
        return byId.values.toList()
    }

    /**
     * Group tasks into project sections for the home screen.
     *
     * The push decides membership (archived excluded, pinned shown) and is
     * merged with the snapshot/index row for what it does not carry — preview
     * and workspace label — so a row does not change shape when the push takes
     * over from the fallback. A workspace the push does not cover (topic not
     * subscribed, or its snapshot not in yet) keeps the merged rows.
     *
     * Pinned tasks lead inside their own project rather than forming a separate
     * section, because on a phone the project grouping is the primary one; a
     * project holding a pinned task is lifted to the top for the same reason.
     */
    fun groups(
        index: TaskIndexState,
        fallback: List<SessionRow>,
        sort: TaskSort,
    ): List<SessionGroup> {
        val fallbackById = fallback.associateBy { it.sessionId }
        val pushRows = index.rows.values
        // Coverage is "the push knows this project at all", not "it lists rows
        // for it": a project whose tasks are all archived must not have them
        // resurrected from the snapshot.
        val covered = pushRows.mapTo(HashSet()) { it.workspacePath }
        val pushed = pushRows.filter { !it.archived }.groupBy { it.workspacePath }

        val byPath = LinkedHashMap<String, MutableList<SessionRow>>()
        pushed.forEach { (path, rows) ->
            byPath[path] = rows.mapTo(mutableListOf()) { it.toSessionRow(fallbackById[it.taskId]) }
        }
        fallback.forEach { row ->
            val path = row.workspacePath.orEmpty()
            if (path.isNotBlank() && path !in covered) {
                byPath.getOrPut(path) { mutableListOf() } += row
            }
        }

        return byPath
            .filterKeys { it.isNotBlank() }
            .map { (path, rows) ->
                val label = label(path)
                SessionGroup(
                    workspacePath = path,
                    label = label,
                    // The label only exists once the path is grouped; hanging
                    // it on the rows lets the flat timeline name the folder.
                    rows = orderRows(
                        rows.map { it.copy(workspaceLabel = it.workspaceLabel ?: label) },
                        sort,
                    ),
                )
            }
            .sortedWith(groupOrder(sort))
    }

    /**
     * The flat 时间 view: one globally ordered timeline.
     *
     * Flattening the project sections in section order (what the screen used to
     * do) is not a timeline — with two projects the rows of the less recent one
     * still came first, and every section move shifted a whole block at once.
     */
    fun timeline(groups: List<SessionGroup>, sort: TaskSort): List<SessionRow> =
        orderRows(groups.flatMap { it.rows }, sort)

    /** Order one project's rows (or a whole timeline) by the active sort. */
    fun orderRows(rows: List<SessionRow>, sort: TaskSort): List<SessionRow> =
        rows.sortedWith(rowOrder(sort))

    /**
     * Project name: the last path segment, Windows or POSIX.
     *
     * Only `\\` was stripped before, so a desktop on macOS or Linux showed the
     * full path as the section header.
     */
    fun label(path: String): String {
        val trimmed = path.trimEnd('\\', '/')
        val name = trimmed.substringAfterLast('\\').substringAfterLast('/')
        return name.ifBlank { trimmed.ifBlank { path } }
    }

    // ── ordering ──────────────────────────────────────────────────────────

    /**
     * Row order: pinned first, then activity, then immutable tie-breaks.
     *
     * The tie-breaks (creation time, then the session id) are what keep the
     * order fixed while several sessions stream: anything active within the
     * same [ACTIVITY_BUCKET_MS] bucket ties on activity and falls back to an
     * order that never changes, instead of trading places on each upsert.
     */
    private fun rowOrder(sort: TaskSort): Comparator<SessionRow> = when (sort) {
        TaskSort.Updated -> compareByDescending<SessionRow> { it.isPinned }
            .thenByDescending { bucket(it.updatedAt) }
            .thenByDescending { it.createdAt ?: it.updatedAt ?: 0L }
            .thenBy { it.sessionId }

        TaskSort.Created -> compareByDescending<SessionRow> { it.isPinned }
            .thenByDescending { it.createdAt ?: it.updatedAt ?: 0L }
            .thenBy { it.sessionId }
    }

    /**
     * Section order: a project with a pinned task first, then the same activity
     * bucket as its most recent task, then the name.
     *
     * The old rule was `max(updatedAt)` of the members, so a single session
     * ticking in one folder lifted that whole folder above the others and back
     * again for as long as both were running.
     */
    private fun groupOrder(sort: TaskSort): Comparator<SessionGroup> =
        compareByDescending<SessionGroup> { group -> group.rows.any { it.isPinned } }
            .thenByDescending { group -> group.rows.maxOfOrNull { activity(it, sort) } ?: 0L }
            .thenBy { it.label }
            .thenBy { it.workspacePath }

    private fun activity(row: SessionRow, sort: TaskSort): Long = when (sort) {
        TaskSort.Updated -> bucket(row.updatedAt)
        TaskSort.Created -> row.createdAt ?: row.updatedAt ?: 0L
    }

    private fun bucket(at: Long?): Long = (at ?: 0L).coerceAtLeast(0L) / ACTIVITY_BUCKET_MS

    // ── projections ───────────────────────────────────────────────────────

    /**
     * Push row, enriched from the snapshot/index row for the same task.
     *
     * Membership, status and unread come from the push; model, preview and
     * label only exist in the other projections. Merging instead of choosing
     * keeps the row's two lines from changing shape when the push comes and
     * goes across a reconnect.
     */
    private fun TaskIndexRow.toSessionRow(fallback: SessionRow?): SessionRow = SessionRow(
        sessionId = taskId,
        title = meta.displayTitle,
        workspacePath = workspacePath,
        workspaceLabel = fallback?.workspaceLabel,
        model = prettyModel(meta.model) ?: meta.provider ?: fallback?.model,
        preview = fallback?.preview,
        status = liveStatus ?: meta.status,
        isRunning = isRunning,
        // Recency: whichever source saw the task more recently. Both values
        // only ever grow, so the order key stays monotone either way.
        updatedAt = newest(meta.updatedAt, fallback?.updatedAt),
        createdAt = meta.createdAt ?: fallback?.createdAt,
        hasCustomTitle = meta.titleOverridden,
        live = true,
        isUnread = isUnread,
        isPinned = pinned,
    )

    private fun SessionIndexEntry.toRow(snapshot: SessionRow?): SessionRow = SessionRow(
        sessionId = sessionId,
        title = displayTitle,
        workspacePath = snapshot?.workspacePath ?: workspaceId,
        workspaceLabel = snapshot?.workspaceLabel,
        model = snapshot?.model,
        preview = previewSnippet(lastAssistantPreview),
        status = phase,
        isRunning = isRunning,
        updatedAt = newest(lastActivityAt, snapshot?.updatedAt),
        createdAt = createdAt ?: snapshot?.createdAt,
        hasCustomTitle = hasCustomTitle,
        live = true,
        isUnread = snapshot?.isUnread ?: false,
    )

    private fun newest(a: Long?, b: Long?): Long? =
        maxOf(a ?: 0L, b ?: 0L).takeIf { it > 0L }

    /**
     * The wire model id is a routing path like
     * `account:bigmodel-start-plan/GLM-5.3-Flash`; only the last segment names
     * the model, the rest is provider plumbing the row has no room for. Falls
     * back to the input when no `/` is present.
     */
    private fun prettyModel(model: String?): String? =
        model?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: model

    /**
     * Turn an assistant preview into one readable list line.
     *
     * The host sends up to 120 characters of the model's own Markdown, so the
     * raw value starts with things like `## 结果` or `- 第一项` and reads as
     * noise in a one-line subtitle. Take the first line with actual prose, drop
     * the leading Markdown markers, and cap it.
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
        return if (cleaned.length <= PREVIEW_MAX) {
            cleaned
        } else {
            cleaned.take(PREVIEW_MAX).trimEnd() + "…"
        }
    }
}
