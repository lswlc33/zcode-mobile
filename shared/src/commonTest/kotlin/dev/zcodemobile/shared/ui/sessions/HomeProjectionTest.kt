package dev.zcodemobile.shared.ui.sessions

import dev.zcodemobile.protocol.SessionIndexEntry
import dev.zcodemobile.protocol.SessionsIndexState
import dev.zcodemobile.protocol.TaskIndexRow
import dev.zcodemobile.protocol.TaskIndexState
import dev.zcodemobile.protocol.TaskMeta
import dev.zcodemobile.protocol.TaskSummary
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The home list projection: which rows exist, what they say, and — the part the
 * bug report was about — the order they hold while several sessions stream.
 *
 * Every case here is a shape that used to reshuffle the list: two folders
 * running at once, a session ticking while its neighbour idles, the push and
 * the stream disagreeing about a row.
 */
class HomeProjectionTest {

    /** Minute-aligned so `base + n` stays inside one activity bucket. */
    private val base = 1_700_000_100_000L / HomeProjection.ACTIVITY_BUCKET_MS *
        HomeProjection.ACTIVITY_BUCKET_MS
    private val minute = HomeProjection.ACTIVITY_BUCKET_MS

    // ── row merging ───────────────────────────────────────────────────────

    @Test
    fun `live index fills the bridged workspace and the snapshot keeps the rest`() {
        val index = SessionsIndexState(
            workspaceId = BRIDGED,
            sessions = listOf(liveEntry("s1", BRIDGED, title = "当前项目会话")),
        )
        val tasks = listOf(
            summary("s1", BRIDGED, title = "旧标题"),
            summary("s2", OTHER, title = "另一个项目"),
        )

        val rows = HomeProjection.rows(index, tasks, BRIDGED)

        assertEquals(setOf("s1", "s2"), rows.map { it.sessionId }.toSet())
        // The stream wins for the workspace it watches...
        assertEquals("当前项目会话", rows.first { it.sessionId == "s1" }.title)
        // ...and the other workspace survives instead of being dropped.
        assertEquals(OTHER, rows.first { it.sessionId == "s2" }.workspacePath)
    }

    @Test
    fun `a task closed on the desktop leaves the stale snapshot behind`() {
        val index = SessionsIndexState(
            workspaceId = BRIDGED,
            sessions = listOf(liveEntry("kept", BRIDGED)),
        )
        val tasks = listOf(summary("kept", BRIDGED), summary("closed", BRIDGED))

        val rows = HomeProjection.rows(index, tasks, BRIDGED)

        assertEquals(listOf("kept"), rows.map { it.sessionId })
    }

    @Test
    fun `model and preview come from different sources without duplication`() {
        val index = SessionsIndexState(
            workspaceId = BRIDGED,
            sessions = listOf(liveEntry("s1", BRIDGED, preview = "跑完了 12 个用例")),
        )
        val tasks = listOf(summary("s1", BRIDGED, model = "acct:plan/GLM-5.3-Flash"))
        val merged = HomeProjection.rows(index, tasks, BRIDGED)

        val push = pushRow("s1", BRIDGED, updatedAt = base, model = "acct:plan/GLM-5.3-Flash")
        val groups = HomeProjection.groups(pushIndex(push), merged, TaskSort.Updated)

        assertEquals("GLM-5.3-Flash · 跑完了 12 个用例", groups.single().rows.single().subtitle)
    }

    @Test
    fun `the preview line keeps its prose and drops the markdown markers`() {
        val index = SessionsIndexState(
            workspaceId = BRIDGED,
            sessions = listOf(liveEntry("s1", BRIDGED, preview = "- 已经跑完 12 个用例")),
        )

        val rows = HomeProjection.rows(index, listOf(summary("s1", BRIDGED)), BRIDGED)

        assertEquals("已经跑完 12 个用例", rows.single().subtitle)
    }

    // ── ordering: the reported bug ────────────────────────────────────────

    @Test
    fun `a ticking session no longer jumps over its neighbour`() {
        // Two projects, one session each, both created in the same minute.
        val older = pushRow("older", DOCS, updatedAt = base + 10_000, createdAt = base - 2 * minute)
        val newer = pushRow("newer", APP, updatedAt = base + 20_000, createdAt = base - minute)

        val first = HomeProjection.groups(pushIndex(older, newer), emptyList(), TaskSort.Updated)
        assertEquals(listOf("newer", "older"), first.flatMap { it.rows }.map { it.sessionId })
        assertEquals(listOf(APP, DOCS), first.map { it.workspacePath })

        // The other session reports activity a moment later. Ordering by raw
        // recency this swapped both rows and their project sections back and
        // forth for as long as the two kept running.
        val ticked = pushRow("older", DOCS, updatedAt = base + 30_000, createdAt = base - 2 * minute)
        val second = HomeProjection.groups(pushIndex(ticked, newer), emptyList(), TaskSort.Updated)

        assertEquals(listOf("newer", "older"), second.flatMap { it.rows }.map { it.sessionId })
        assertEquals(listOf(APP, DOCS), second.map { it.workspacePath })

        // Guard the premise: the raw recency order did flip, so this really is
        // the case the reports describe.
        val raw = listOf(ticked, newer).sortedByDescending { it.meta.updatedAt }
        assertEquals(listOf("older", "newer"), raw.map { it.taskId })
    }

    @Test
    fun `a session that went idle drops below the one still running`() {
        val idle = pushRow("idle", DOCS, updatedAt = base)
        val running = pushRow("run", APP, updatedAt = base + 2 * minute)

        val groups = HomeProjection.groups(pushIndex(idle, running), emptyList(), TaskSort.Updated)

        assertEquals(listOf("run", "idle"), groups.flatMap { it.rows }.map { it.sessionId })
        assertEquals(listOf(APP, DOCS), groups.map { it.workspacePath })
    }

    @Test
    fun `pinned rows lead their project and their project leads the list`() {
        val fresh = pushRow("fresh", APP, updatedAt = base + 5 * minute)
        val pinned = pushRow("pinned", DOCS, updatedAt = base, pinned = true)
        val plain = pushRow("plain", DOCS, updatedAt = base + 3 * minute)

        val groups = HomeProjection.groups(pushIndex(fresh, pinned, plain), emptyList(), TaskSort.Updated)

        assertEquals(DOCS, groups.first().workspacePath)
        assertEquals(listOf("pinned", "plain"), groups.first().rows.map { it.sessionId })
        assertTrue(groups.first().rows.first().isPinned)
    }

    @Test
    fun `creation order ignores activity entirely`() {
        val older = pushRow("older", APP, updatedAt = base + 9 * minute, createdAt = base)
        val newer = pushRow("newer", APP, updatedAt = base, createdAt = base + minute)

        val groups = HomeProjection.groups(pushIndex(older, newer), emptyList(), TaskSort.Created)

        assertEquals(listOf("newer", "older"), groups.single().rows.map { it.sessionId })
    }

    @Test
    fun `the timeline is globally ordered, not one block per project`() {
        val appOld = pushRow("app-old", APP, updatedAt = base)
        val appNew = pushRow("app-new", APP, updatedAt = base + 4 * minute)
        val docsMid = pushRow("docs-mid", DOCS, updatedAt = base + 2 * minute)

        val groups = HomeProjection.groups(
            pushIndex(appOld, appNew, docsMid), emptyList(), TaskSort.Updated,
        )
        val flat = HomeProjection.timeline(groups, TaskSort.Updated)

        assertEquals(listOf("app-new", "docs-mid", "app-old"), flat.map { it.sessionId })
    }

    // ── grouping and labels ───────────────────────────────────────────────

    @Test
    fun `archived tasks are dropped and the fallback covers an uncovered project`() {
        val push = pushIndex(
            pushRow("live", APP, updatedAt = base),
            pushRow("gone", APP, updatedAt = base, archived = true),
        )
        val fallback = HomeProjection.rows(
            SessionsIndexState(),
            listOf(summary("snap", DOCS, updatedAt = base)),
            bridged = null,
        )

        val groups = HomeProjection.groups(push, fallback, TaskSort.Updated)

        assertEquals(setOf(APP, DOCS), groups.map { it.workspacePath }.toSet())
        assertTrue(groups.flatMap { it.rows }.none { it.sessionId == "gone" })
    }

    @Test
    fun `a project whose tasks are all archived leaves the list entirely`() {
        val push = pushIndex(
            pushRow("live", APP, updatedAt = base),
            pushRow("hidden", DOCS, updatedAt = base, archived = true),
        )
        val fallback = HomeProjection.rows(
            SessionsIndexState(),
            listOf(summary("hidden", DOCS, updatedAt = base)),
            bridged = null,
        )

        val groups = HomeProjection.groups(push, fallback, TaskSort.Updated)

        assertEquals(listOf(APP), groups.map { it.workspacePath })
    }

    @Test
    fun `every grouped row carries its project name`() {
        val groups = HomeProjection.groups(
            pushIndex(pushRow("s1", DOCS, updatedAt = base)),
            emptyList(),
            TaskSort.Updated,
        )

        assertEquals("docs", groups.single().rows.single().workspaceLabel)
    }

    @Test
    fun `project names come out of windows and posix paths alike`() {
        assertEquals("zcode-mobile", HomeProjection.label("E:\\open_trae_m\\zcode-mobile"))
        assertEquals("zcode-mobile", HomeProjection.label("/home/dev/zcode-mobile"))
        assertEquals("zcode-mobile", HomeProjection.label("E:\\open_trae_m\\zcode-mobile\\"))
        // A drive root has no segment left to name, so the drive letter stands.
        assertEquals("E:", HomeProjection.label("E:\\"))
    }

    @Test
    fun `a row with nothing to say reports no subtitle`() {
        val row = SessionRow(sessionId = "s", title = "t", workspacePath = DOCS)

        assertNull(row.subtitle)
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun pushIndex(vararg rows: TaskIndexRow): TaskIndexState =
        TaskIndexState(rows = rows.associateBy { it.taskId })

    private fun pushRow(
        id: String,
        path: String,
        updatedAt: Long,
        title: String = id,
        model: String? = null,
        createdAt: Long? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): TaskIndexRow = TaskIndexRow(
        taskId = id,
        workspacePath = path,
        meta = meta(id, title, path, createdAt ?: updatedAt, updatedAt, model),
        pinned = pinned,
        archived = archived,
        active = true,
        liveStatus = null,
        phase = null,
        raw = emptyMap(),
    )

    private fun summary(
        id: String,
        path: String,
        title: String = id,
        model: String? = null,
        updatedAt: Long = base,
    ): TaskSummary = TaskSummary(
        taskId = id,
        title = title,
        displayStatus = null,
        workspacePath = path,
        workspaceLabel = HomeProjection.label(path),
        model = model,
        provider = null,
        updatedAt = updatedAt,
        unreadAt = null,
        raw = emptyMap(),
    )

    private fun liveEntry(
        id: String,
        path: String,
        title: String = id,
        preview: String? = null,
    ): SessionIndexEntry = SessionIndexEntry(
        sessionId = id,
        workspaceId = path,
        title = title,
        titleSource = null,
        phase = null,
        sessionEnded = false,
        hasBackgroundWork = false,
        goalStatus = null,
        parentSessionId = null,
        lastActivityAt = base,
        lastAssistantPreview = preview,
        createdAt = base,
        raw = emptyMap(),
    )

    private fun meta(
        id: String,
        title: String,
        path: String,
        createdAt: Long?,
        updatedAt: Long?,
        model: String?,
    ): TaskMeta = TaskMeta(
        taskId = id,
        title = title,
        titleOverridden = false,
        workspacePath = path,
        createdAt = createdAt,
        updatedAt = updatedAt,
        mode = null,
        model = model,
        provider = null,
        status = null,
        unreadAt = null,
        raw = emptyMap(),
    )

    private companion object {
        const val APP = "E:\\work\\app"
        const val DOCS = "/home/dev/docs"
        const val OTHER = "E:\\work\\other"
        const val BRIDGED = "E:\\work\\app"
    }
}
