package dev.zcodemobile.app.ui.sessions

/**
 * One row in the session list, whatever it came from.
 *
 * The list has three sources — the window-wide `controller/tasks-index` push,
 * the live `sessions-index` stream for the bridged workspace and the bootstrap
 * task snapshot for anything else — and the screen should not have to know
 * which. All are projected into this shape so the list renders identically
 * and the source is only a debug detail ([live]).
 */
data class SessionRow(
    val sessionId: String,
    val title: String,
    val workspacePath: String?,
    val workspaceLabel: String?,
    /** Secondary line: model (falling back to provider), workspace, last assistant preview. */
    val subtitle: String?,
    /** Raw phase or display status, for the status glyph. */
    val status: String?,
    val isRunning: Boolean,
    val updatedAt: Long?,
    val createdAt: Long? = null,
    /** True when the user renamed it, so auto-titling has stopped. */
    val hasCustomTitle: Boolean = false,
    /** True when this row came from a live stream rather than a snapshot. */
    val live: Boolean = false,
    /** Unread mark from the task index (blue dot in the desktop sidebar). */
    val isUnread: Boolean = false,
) {
    /** Stable key for the list; the id is unique across both sources. */
    val key: String get() = sessionId
}

/**
 * A project (workspace) section of the home timeline: its label, path, and
 * the tasks sorted inside it.
 */
data class SessionGroup(
    val workspacePath: String,
    val label: String,
    val rows: List<SessionRow>,
)

/** What the home timeline sorts its task rows by. */
enum class TaskSort(val label: String) {
    Updated("更新时间"),
    Created("创建时间"),
}

/** How the home screen organises tasks: grouped by project, or one flat timeline. */
enum class HomeView(val label: String) {
    Project("项目"),
    Time("时间"),
}
