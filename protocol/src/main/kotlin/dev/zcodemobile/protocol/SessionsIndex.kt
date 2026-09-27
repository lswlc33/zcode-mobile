package dev.zcodemobile.protocol

/**
 * One row of the workspace session list.
 *
 * Mirrors `sessionSummarySchema`
 * (`packages/shared/src/zcode-protocol-v4/sessions-index.ts`). The desktop
 * keeps this list deliberately thin — it carries what a sidebar row needs
 * (title, phase, activity, a one-line preview) and nothing that would require
 * loading the conversation.
 */
data class SessionIndexEntry(
    val sessionId: String,
    val workspaceId: String,
    val title: String,
    /** `custom` = the user renamed it; `generated`/`default` are automatic. */
    val titleSource: String?,
    val phase: String?,
    val sessionEnded: Boolean,
    val hasBackgroundWork: Boolean,
    val goalStatus: String?,
    val parentSessionId: String?,
    val lastActivityAt: Long?,
    val lastAssistantPreview: String?,
    val createdAt: Long?,
    val raw: Map<String, Any?>,
) {
    /** A session the user renamed keeps that title; auto-titling stops. */
    val hasCustomTitle: Boolean get() = titleSource == "custom"

    val isRunning: Boolean get() = phase == "running" || hasBackgroundWork

    /** Display title, falling back rather than rendering an empty row. */
    val displayTitle: String get() = title.ifBlank { "(无标题)" }
}

/** Conflated latest state of the workspace session list. */
data class SessionsIndexState(
    val workspaceId: String? = null,
    val logEpoch: String? = null,
    val seq: Long = 0,
    val sessions: List<SessionIndexEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = sessions.isEmpty()

    fun byId(sessionId: String): SessionIndexEntry? =
        sessions.firstOrNull { it.sessionId == sessionId }
}

/**
 * Reducer for the `sessions-index/<workspaceId>` topic.
 *
 * Two ops only. `session.upserted` conflates on `sessionId` — it *replaces*
 * the existing row, so it must not be treated as an append.
 */
object SessionsIndex {

    const val TOPIC_PREFIX = "sessions-index/"

    fun topic(workspaceId: String): String = TOPIC_PREFIX + workspaceId

    fun parseTopic(topic: String): String? =
        topic.removePrefix(TOPIC_PREFIX)
            .takeIf { topic.startsWith(TOPIC_PREFIX) && it.isNotEmpty() }

    fun applySnapshot(frame: Map<String, Any?>, snapshot: Map<String, Any?>): SessionsIndexState =
        SessionsIndexState(
            workspaceId = Json.asString(snapshot["workspaceId"]),
            logEpoch = Json.asString(snapshot["logEpoch"]),
            seq = Json.asLong(frame["toSeq"]) ?: 0,
            sessions = Json.asList(snapshot["sessions"]).map { toEntry(Json.asMap(it)) },
        )

    /**
     * Fold a delta batch. Newest activity first, so the list matches what the
     * desktop sidebar shows rather than insertion order.
     */
    fun applyDeltas(
        current: SessionsIndexState,
        deltas: List<Any?>,
        onUnknownOp: ((String) -> Unit)? = null,
    ): SessionsIndexState {
        var sessions = current.sessions
        for (d in deltas) {
            val op = Json.asMap(d)
            when (val name = Json.asString(op["op"])) {
                "session.upserted" -> {
                    val entry = toEntry(Json.asMap(op["session"]))
                    val idx = sessions.indexOfFirst { it.sessionId == entry.sessionId }
                    sessions = if (idx >= 0) {
                        sessions.toMutableList().also { it[idx] = entry }
                    } else {
                        sessions + entry
                    }
                }
                "session.removed" -> {
                    val id = Json.asString(op["sessionId"]) ?: continue
                    sessions = sessions.filterNot { it.sessionId == id }
                }
                else -> if (name != null) onUnknownOp?.invoke(name)
            }
        }
        return current.copy(sessions = sessions.sortedByDescending { it.lastActivityAt ?: 0L })
    }

    fun toEntry(m: Map<String, Any?>): SessionIndexEntry = SessionIndexEntry(
        sessionId = Json.asString(m["sessionId"]) ?: "",
        workspaceId = Json.asString(m["workspaceId"]) ?: "",
        title = Json.asString(m["title"]) ?: "",
        titleSource = Json.asString(m["titleSource"]),
        phase = Json.asString(m["phase"]),
        sessionEnded = Json.asBool(m["sessionEnded"]) ?: false,
        hasBackgroundWork = Json.asBool(m["hasBackgroundWork"]) ?: false,
        goalStatus = Json.asString(m["goalStatus"]),
        parentSessionId = Json.asString(m["parentSessionId"]),
        lastActivityAt = Json.asLong(m["lastActivityAt"]),
        lastAssistantPreview = Json.asString(m["lastAssistantPreview"]),
        createdAt = Json.asLong(m["createdAt"]),
        raw = m,
    )
}
