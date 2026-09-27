package dev.zcodemobile.app.ui.sessions

/**
 * One row in the session list, whatever it came from.
 *
 * The list has three sources — the window-wide `controller/tasks-index` push,
 * the live `sessions-index` stream for the bridged workspace and the bootstrap
 * task snapshot for anything else — and the screen should not have to know
 * which. All are projected into this shape by [HomeProjection] so the list
 * renders identically and the source is only a debug detail ([live]).
 */
data class SessionRow(
    val sessionId: String,
    val title: String,
    val workspacePath: String?,
    /** Project name, filled in by the projection once the rows are grouped. */
    val workspaceLabel: String? = null,
    /** Last-used model, wire id already shortened for display. */
    val model: String? = null,
    /** One line of the last assistant message, Markdown stripped. */
    val preview: String? = null,
    /** Raw phase or display status, for the status glyph. */
    val status: String? = null,
    val isRunning: Boolean = false,
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
    /** True when the user renamed it, so auto-titling has stopped. */
    val hasCustomTitle: Boolean = false,
    /** True when this row came from a live stream rather than a snapshot. */
    val live: Boolean = false,
    /** Unread mark from the task index (blue dot in the desktop sidebar). */
    val isUnread: Boolean = false,
    /** 置顶 in the task index: pinned rows lead their project section. */
    val isPinned: Boolean = false,
) {
    /** Stable key for the list; the id is unique across all three sources. */
    val key: String get() = sessionId

    /**
     * Secondary line: model, then the preview.
     *
     * Composed here rather than by each source so the two halves can come from
     * different ones (the push carries the model, the stream the preview)
     * without the line changing shape when the sources switch over.
     */
    val subtitle: String?
        get() = listOfNotNull(model, preview)
            .joinToString(" · ")
            .ifBlank { null }
}

/**
 * A project (workspace) section of the home list: its label, path, and the
 * tasks sorted inside it.
 */
data class SessionGroup(
    val workspacePath: String,
    val label: String,
    val rows: List<SessionRow>,
)

/** What the home list sorts its task rows by. */
enum class TaskSort(val label: String) {
    Updated("更新时间"),
    Created("创建时间"),
}

/** How the home screen organises tasks: grouped by project, or one flat timeline. */
enum class HomeView(val label: String) {
    Project("项目"),
    Time("时间"),
}
