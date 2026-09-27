package dev.zcodemobile.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reducer tests for the `controller/tasks-index` topic and the `TaskMeta`
 * projection. Frame shapes mirror the host schema
 * (`windowHostControllerTaskFrameSchema`).
 */
class TaskIndexTest {

    private fun rowJson(
        taskId: String,
        title: String,
        workspacePath: String = "E:\\proj",
        pinned: Boolean = false,
        archived: Boolean = false,
        active: Boolean = false,
        liveStatus: String = "idle",
        unreadAt: Long? = null,
        status: String? = "completed",
        updatedAt: Long = 1_000,
        createdAt: Long = 900,
    ): Map<String, Any?> = mapOf(
        "address" to mapOf("taskId" to taskId, "workspacePath" to workspacePath),
        "meta" to mapOf(
            "taskId" to taskId,
            "title" to title,
            "workspacePath" to workspacePath,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
            "status" to status,
            "unreadAt" to unreadAt,
        ),
        "membership" to mapOf("pinned" to pinned, "archived" to archived, "active" to active),
        "sourceAvailability" to "online",
        "liveStatus" to liveStatus,
    )

    private fun snapshotFrame(vararg rows: Map<String, Any?>): Map<String, Any?> = mapOf(
        "topic" to TaskService.TOPIC_TASKS_INDEX,
        "logEpoch" to "epoch-1",
        "fromSeq" to 0L,
        "toSeq" to 3L,
        "payload" to mapOf(
            "kind" to "snapshot",
            "snapshot" to mapOf("protocolVersion" to 1L, "logEpoch" to "epoch-1", "tasks" to rows.toList()),
        ),
    )

    @Test
    fun `snapshot fills rows keyed by taskId`() {
        val frame = snapshotFrame(
            rowJson("sess_a", "Alpha"),
            rowJson("sess_b", "Beta", workspacePath = "E:\\other"),
        )
        val payload = Json.asMap(frame["payload"])
        val state = TaskIndex.applySnapshot(frame, Json.asMap(payload["snapshot"]))

        assertEquals(2, state.rows.size)
        assertEquals("Alpha", state.byId("sess_a")?.meta?.title)
        assertEquals("E:\\other", state.byId("sess_b")?.workspacePath)
        assertEquals("epoch-1", state.logEpoch)
    }

    @Test
    fun `membership decides the section a row lands in`() {
        val frame = snapshotFrame(
            rowJson("sess_p", "Pinned", pinned = true),
            rowJson("sess_a", "Archived", archived = true),
            rowJson("sess_t", "Timeline"),
        )
        val payload = Json.asMap(frame["payload"])
        val state = TaskIndex.applySnapshot(frame, Json.asMap(payload["snapshot"]))

        assertEquals(listOf("sess_p"), state.pinned.map { it.taskId })
        assertEquals(listOf("sess_t"), state.timeline.map { it.taskId })
        assertEquals(listOf("sess_a"), state.archived.map { it.taskId })
        // Pinned-then-archived reads as archived, matching the host projection.
        assertTrue(state.rows.values.none { it.pinned && it.archived && it.isPinnedVisible })
    }

    @Test
    fun `upserted replaces by taskId and removed drops it`() {
        val frame = snapshotFrame(rowJson("sess_a", "Alpha"))
        val payload = Json.asMap(frame["payload"])
        var state = TaskIndex.applySnapshot(frame, Json.asMap(payload["snapshot"]))

        state = TaskIndex.applyDeltas(
            state,
            listOf(mapOf("op" to "task.upserted", "task" to rowJson("sess_a", "Alpha 2", liveStatus = "running"))),
        )
        assertEquals("Alpha 2", state.byId("sess_a")?.meta?.title)
        assertTrue(state.byId("sess_a")!!.isRunning)
        assertEquals(1, state.rows.size)

        state = TaskIndex.applyDeltas(
            state,
            listOf(mapOf("op" to "task.removed", "address" to mapOf("taskId" to "sess_a", "workspacePath" to "E:\\proj"))),
        )
        assertTrue(state.isEmpty)
    }

    @Test
    fun `unknown ops are reported not thrown`() {
        var seen: String? = null
        val state = TaskIndex.applyDeltas(
            TaskIndexState(),
            listOf(mapOf("op" to "workspace.upserted", "workspace" to emptyMap<String, Any?>())),
        ) { seen = it }
        assertEquals("workspace.upserted", seen)
        assertTrue(state.isEmpty)
    }

    @Test
    fun `unread derives from unreadAt`() {
        val frame = snapshotFrame(
            rowJson("sess_u", "Unread", unreadAt = 1_700_000_000_000),
            rowJson("sess_r", "Read"),
        )
        val payload = Json.asMap(frame["payload"])
        val state = TaskIndex.applySnapshot(frame, Json.asMap(payload["snapshot"]))

        assertTrue(state.byId("sess_u")!!.isUnread)
        assertFalse(state.byId("sess_r")!!.isUnread)
    }

    @Test
    fun `meta projection keeps the display fields and tolerates missing ones`() {
        val meta = TaskMeta.from(
            mapOf(
                "taskId" to "sess_x",
                "title" to "",
                "workspacePath" to "E:\\proj",
                "createdAt" to 5L,
                "updatedAt" to 9L,
            ),
        )
        assertEquals("(无标题)", meta.displayTitle)
        assertEquals(5L, meta.createdAt)
        assertEquals(9L, meta.updatedAt)
        assertNull(meta.status)
        assertFalse(meta.titleOverridden)
    }
}
