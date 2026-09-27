package dev.zcodemobile.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The v4 surface added on top of the original read/write path: the full command
 * set, the two new topic subscriptions, the settings projection, and the
 * queue / availability / goal projection.
 */
class ProtocolSurfaceTest {

    private val target = Commands.RowTarget(rowId = 2, entityId = "msg_abc")

    // ── envelope rules ────────────────────────────────────────────────────

    @Test
    fun `createSession is the only command allowed to omit sessionId`() {
        val env = Commands.createSession(
            clientId = "c",
            workspaceId = "E:\\ws",
            firstInputText = "hi",
        )
        assertEquals("createSession", env["type"])
        assertTrue(env.containsKey("sessionId"), "sessionId must be present as an explicit null")
        assertNull(env["sessionId"])
        assertEquals("E:\\ws", Json.asMap(env["payload"])["workspaceId"])

        // Every other command must carry one.
        try {
            Commands.envelopeFor("stop", clientId = "c", sessionId = null, payload = emptyMap<String, Any?>())
            fail("expected a sessionId check")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sessionId"))
        }
    }

    @Test
    fun `CAS commands refuse to build without a base revision`() {
        // 15 commands require it; spot-check one from each family.
        val casCommands = Commands.REQUIRES_BASE_REVISION
        assertEquals(15, casCommands.size)
        assertTrue(casCommands.containsAll(Commands.ROW_TARGETING))

        // For the named builders this is enforced by the type system: their
        // `baseRevision` parameter is non-null, so `switchCollaborationMode(...)`
        // without one does not compile. The runtime check below is the net for
        // the generic entry point, which cannot be typed.
        try {
            Commands.envelopeFor(
                type = "switchCollaborationMode",
                clientId = "c", sessionId = "s",
                payload = mapOf("mode" to "yolo"),
                baseRevision = null,
            )
            fail("a CAS command should not build without baseRevision")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("baseRevision"))
        }
    }

    @Test
    fun `row-targeting commands refuse to build without a log epoch`() {
        assertEquals(5, Commands.ROW_TARGETING.size)

        // The named builder types this parameter as non-null; the generic entry
        // point is the one that needs a runtime net.
        try {
            Commands.envelopeFor(
                type = "editUserQuery",
                clientId = "c", sessionId = "s",
                payload = mapOf("target" to target.toWire(), "newText" to "x"),
                baseRevision = 3,
                baseLogEpoch = null,
            )
            fail("a row-targeting command should not build without baseLogEpoch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("baseLogEpoch"))
        }
    }

    @Test
    fun `non-CAS commands build without a revision`() {
        // Deliberately revision-free in the source: a fake CAS failure here
        // would only cause harm.
        for (env in listOf(
            Commands.compact("c", "s"),
            Commands.cancelBackgroundWork("c", "s", "work"),
            Commands.resumeWorkflowRun("c", "s", "run"),
            Commands.startSavedWorkflow("c", "s", "wf"),
            Commands.amendWorkflowRunSettings("c", "s", "run"),
        )) {
            assertFalse(env.containsKey("baseRevision"))
            assertNotNull(env["issuedAt"])
            assertNotNull(env["commandId"])
        }
    }

    @Test
    fun `every command type has a builder that produces it`() {
        val envelopes = listOf(
            Commands.createSession("c", "ws"),
            Commands.createSelectionSideSession("c", "parent"),
            Commands.sendText("c", "s", "hi"),
            Commands.sendGoalCommand("c", "s", "goal"),
            Commands.stop("c", "s"),
            Commands.compact("c", "s"),
            Commands.forkAssistant("c", "s", target, 1, "e"),
            Commands.applyFileRewind("c", "s", target, 1, "e"),
            Commands.editUserQuery("c", "s", target, "x", baseRevision = 1, baseLogEpoch = "e"),
            Commands.retryTurn("c", "s", target, 1, "e"),
            Commands.setAssistantFeedback("c", "s", target, Commands.Feedback.Like, 1, "e"),
            Commands.sendQueuedNow("c", "s", "q", 1),
            Commands.editQueueItem("c", "s", "q", "t", 1),
            Commands.reorderQueueItem("c", "s", "q", null, 1),
            Commands.deleteQueueItem("c", "s", "q", 1),
            Commands.setAutoDrain("c", "s", true, 1),
            Commands.resolveInteraction("c", "s", "i"),
            Commands.respondWorkspaceHookReview(
                "c", hookTarget(), Commands.HookReviewDecisionAction.TrustSelected, listOf("r"),
            ),
            Commands.toggleWorkspaceHookReviewItem("c", hookTarget(), "r", true),
            Commands.revokeWorkspaceHookTrust("c", "s", hookTarget(), listOf("r")),
            Commands.requestWorkspaceHookReview("c", "s", "wsid", "sha256:${"a".repeat(64)}"),
            Commands.snoozeInteractionAutoResolution("c", "s", "i"),
            Commands.switchModelConfig("c", "s", "p", "m", "high", baseRevision = 1),
            Commands.switchCollaborationMode("c", "s", Commands.Mode.Build, baseRevision = 1),
            Commands.setFollowupMode("c", "s", Commands.Followup.Queue, baseRevision = 1),
            Commands.pauseGoal("c", "s", baseRevision = 1),
            Commands.resumeGoal("c", "s", baseRevision = 1),
            Commands.cancelBackgroundWork("c", "s", "w"),
            Commands.resumeWorkflowRun("c", "s", "w"),
            Commands.startSavedWorkflow("c", "s", "n"),
            Commands.amendWorkflowRunSettings("c", "s", "w"),
            Commands.renameSession("c", "s", "t"),
            Commands.deleteSession("c", "s"),
            Commands.discardSharedContext("c", "s", "ctx"),
        )

        val types = envelopes.map { Json.asString(it["type"]) }
        assertEquals(34, types.size)
        assertEquals(34, types.toSet().size, "a command type was built twice")
        assertEquals(
            setOf(
                "createSession", "createSelectionSideSession", "sendText", "sendGoalCommand",
                "stop", "compact", "forkAssistant", "applyFileRewind", "editUserQuery",
                "retryTurn", "setAssistantFeedback", "sendQueuedNow", "editQueueItem",
                "reorderQueueItem", "deleteQueueItem", "setAutoDrain", "resolveInteraction",
                "respondWorkspaceHookReview", "toggleWorkspaceHookReviewItem",
                "revokeWorkspaceHookTrust", "requestWorkspaceHookReview",
                "snoozeInteractionAutoResolution", "switchModelConfig",
                "switchCollaborationMode", "setFollowupMode", "pauseGoal", "resumeGoal",
                "cancelBackgroundWork", "resumeWorkflowRun", "startSavedWorkflow",
                "amendWorkflowRunSettings", "renameSession", "deleteSession",
                "discardSharedContext",
            ),
            types.toSet(),
            "a builder emitted an unexpected type",
        )
    }

    @Test
    fun `hook review carries the session id the host cross-checks`() {
        // envelope.sessionId must equal payload.sessionId, or the host answers
        // workspace_hooks_snapshot_mismatch.
        val env = Commands.toggleWorkspaceHookReviewItem("c", hookTarget(), "r", true)
        assertEquals("sess-1", env["sessionId"])
        assertEquals("sess-1", Json.asMap(env["payload"])["sessionId"])
    }

    @Test
    fun `sendText models the held queue decision`() {
        val env = Commands.sendText(
            clientId = "c", sessionId = "s", text = "hi",
            requestedDelivery = Commands.Delivery.Queue,
            heldQueueDisposition = Commands.HeldQueueDisposition.ClearQueueAndSend,
            expectedHeldQueueItemIds = listOf("q1", "q2"),
        )
        val p = Json.asMap(env["payload"])
        assertEquals("queue", p["requestedDelivery"])
        assertEquals("clearQueueAndSend", p["heldQueueDisposition"])
        assertEquals(listOf("q1", "q2"), p["expectedHeldQueueItemIds"])
    }

    @Test
    fun `amendWorkflowRunSettings distinguishes keep from reset`() {
        // omitted = keep, explicit null = back to default, value = set.
        val keep = Json.asMap(
            Commands.amendWorkflowRunSettings("c", "s", "w").let { it["payload"] }
        )
        assertFalse(keep.containsKey("subagentModel"))
        assertFalse(keep.containsKey("maxConcurrency"))

        val reset = Json.asMap(
            Commands.amendWorkflowRunSettings(
                "c", "s", "w", clearSubagentModel = true, maxConcurrency = 4,
            ).let { it["payload"] }
        )
        assertTrue(reset.containsKey("subagentModel"))
        assertNull(reset["subagentModel"])
        assertEquals(4, reset["maxConcurrency"])
    }

    @Test
    fun `setAssistantFeedback can clear a rating with an explicit null`() {
        val env = Commands.setAssistantFeedback("c", "s", target, null, 1, "e")
        assertTrue(Json.asMap(env["payload"]).containsKey("feedback"))
        assertNull(Json.asMap(env["payload"])["feedback"])
    }

    // ── sessions index ────────────────────────────────────────────────────

    @Test
    fun `sessions index snapshot parses the summary fields`() {
        val state = SessionsIndex.applySnapshot(
            mapOf("toSeq" to 42L),
            mapOf(
                "workspaceId" to "E:\\ws",
                "logEpoch" to "epoch-1",
                "sessions" to listOf(
                    mapOf(
                        "sessionId" to "sess-1",
                        "workspaceId" to "E:\\ws",
                        "title" to "hi",
                        "titleSource" to "generated",
                        "phase" to "completedSuccess",
                        "sessionEnded" to true,
                        "hasBackgroundWork" to false,
                        "lastActivityAt" to 1000L,
                        "lastAssistantPreview" to "Hello",
                        "createdAt" to 500L,
                    ),
                ),
            ),
        )

        assertEquals("E:\\ws", state.workspaceId)
        assertEquals(42L, state.seq)
        val e = state.sessions.single()
        assertEquals("hi", e.title)
        assertEquals("Hello", e.lastAssistantPreview)
        assertTrue(e.sessionEnded)
        assertFalse(e.hasCustomTitle)
        assertFalse(e.isRunning)
    }

    @Test
    fun `session upserted replaces rather than appends`() {
        val base = SessionsIndex.applySnapshot(
            emptyMap(),
            mapOf(
                "sessions" to listOf(
                    summary("sess-1", "old", 1000L),
                    summary("sess-2", "other", 2000L),
                ),
            ),
        )

        val next = SessionsIndex.applyDeltas(
            base,
            listOf(mapOf("op" to "session.upserted", "session" to summary("sess-1", "new", 3000L))),
        )

        assertEquals(2, next.sessions.size)
        assertEquals("new", next.byId("sess-1")!!.title)
        // Newest activity first.
        assertEquals("sess-1", next.sessions.first().sessionId)
    }

    @Test
    fun `session removed drops the row`() {
        val base = SessionsIndex.applySnapshot(
            emptyMap(),
            mapOf("sessions" to listOf(summary("sess-1", "a", 1L), summary("sess-2", "b", 2L))),
        )
        val next = SessionsIndex.applyDeltas(
            base,
            listOf(mapOf("op" to "session.removed", "sessionId" to "sess-1")),
        )
        assertEquals(listOf("sess-2"), next.sessions.map { it.sessionId })
    }

    @Test
    fun `unknown session index ops are reported not thrown`() {
        val base = SessionsIndexState()
        val seen = ArrayList<String>()
        SessionsIndex.applyDeltas(base, listOf(mapOf("op" to "session.future")), seen::add)
        assertEquals(listOf("session.future"), seen)
    }

    @Test
    fun `topic round-trips for both new subscriptions`() {
        assertEquals(
            "sessions-index/E:\\ws",
            SessionsIndex.topic("E:\\ws"),
        )
        assertEquals("E:\\ws", SessionsIndex.parseTopic(SessionsIndex.topic("E:\\ws")))
        assertNull(SessionsIndex.parseTopic("conversation/sess-1"))

        assertEquals("workspace-config/E:\\ws", WorkspaceConfig.topic("E:\\ws"))
        assertEquals("E:\\ws", WorkspaceConfig.parseTopic(WorkspaceConfig.topic("E:\\ws")))
        assertNull(WorkspaceConfig.parseTopic("sessions-index/E:\\ws"))
    }

    // ── workspace config ──────────────────────────────────────────────────

    @Test
    fun `workspace config separates an unknown capability from an empty one`() {
        val state = WorkspaceConfig.applySnapshot(
            mapOf(
                "workspaceId" to "E:\\ws",
                "config" to mapOf(
                    "configOptions" to listOf(
                        mapOf(
                            "id" to "model", "name" to "模型", "type" to "select",
                            "currentValue" to "cn:glm-5.3",
                            "options" to listOf(
                                // capability unknown — no key at all
                                mapOf("value" to "a", "name" to "A"),
                                // capability known to be empty
                                mapOf("value" to "b", "name" to "B", "modelThoughtLevels" to emptyList<String>()),
                                // capability known to have levels
                                mapOf(
                                    "value" to "c", "name" to "C",
                                    "modelThoughtLevels" to listOf("low", "high"),
                                ),
                            ),
                        ),
                    ),
                    "slashCommands" to listOf(
                        mapOf("name" to "/compact", "description" to "压缩上下文"),
                    ),
                ),
            ),
        )

        val option = state.option("model")!!
        assertEquals("cn:glm-5.3", option.currentString)
        assertNull(option.options[0].modelThoughtLevels)
        assertEquals(emptyList<String>(), option.options[1].modelThoughtLevels)
        assertEquals(listOf("low", "high"), option.options[2].modelThoughtLevels)
        assertEquals("/compact", state.slashCommands.single().name)
    }

    @Test
    fun `config updated replaces the whole directory`() {
        val base = WorkspaceConfig.applySnapshot(
            mapOf(
                "config" to mapOf(
                    "configOptions" to listOf(mapOf("id" to "mode", "name" to "模式", "type" to "select", "currentValue" to "yolo")),
                    "slashCommands" to emptyList<Any?>(),
                ),
            ),
        )
        val next = WorkspaceConfig.applyDeltas(
            base,
            listOf(
                mapOf(
                    "op" to "config.updated",
                    "config" to mapOf(
                        "configOptions" to emptyList<Any?>(),
                        "slashCommands" to listOf(mapOf("name" to "/x", "description" to "d")),
                    ),
                ),
            ),
        )
        assertTrue(next.configOptions.isEmpty())
        assertEquals("/x", next.slashCommands.single().name)
    }

    // ── settings ──────────────────────────────────────────────────────────

    @Test
    fun `settings projection keeps known keys and drops credentials`() {
        val raw = mapOf<String, Any?>(
            "messageStreamShowReasoning" to true,
            "taskAutoArchiveOlderThanDays" to 3L,
            "locale" to "zh-CN",
            // Not in the catalog, and can carry user:password@host.
            "httpProxy" to "http://user:secret@127.0.0.1:7890",
            "desktopWindowSize" to mapOf("width" to 1096),
            "recentProjects" to listOf("E:\\ws"),
        )

        val keep = AppSettingsCatalog.editableKeys + AppSettingsCatalog.readOnly.map { it.key }
        val settings = AppSettings(Json.asMap(Json.decode(Json.encode(raw.filterKeys { it in keep }))))

        assertEquals(true, settings.bool("messageStreamShowReasoning"))
        assertEquals(3, settings.int("taskAutoArchiveOlderThanDays"))
        assertEquals("zh-CN", settings.string("locale"))
        assertEquals(listOf("E:\\ws"), settings.recentProjects())
        assertNull(settings.string("httpProxy"))
        assertFalse(settings.values.containsKey("desktopWindowSize"))

        val rendered = Json.encode(settings.values)
        assertFalse(rendered.contains("secret"), "a proxy credential reached the projection")
    }

    @Test
    fun `settings catalog exposes only keys the host actually returns`() {
        // Guard against a typo'd key silently rendering a control that can
        // never take effect.
        assertTrue(AppSettingsCatalog.editableKeys.contains("memoryEnabled"))
        assertFalse(AppSettingsCatalog.editableKeys.contains("httpProxy"))
        assertTrue(AppSettingsCatalog.editable.size >= 10)
    }

    // ── conversation projection ───────────────────────────────────────────

    @Test
    fun `snapshot projects queue availability and goal`() {
        val state = ConversationReducer.applySnapshot(
            mapOf("toSeq" to 10L),
            mapOf(
                "sessionId" to "sess-1",
                "logEpoch" to "e",
                "revision" to 7L,
                "control" to mapOf(
                    "phase" to "running",
                    "canStop" to true,
                    "sessionEnded" to false,
                    "activeWorks" to listOf(
                        mapOf("kind" to "primaryTurn", "foregroundExecutionId" to "exec-1"),
                    ),
                ),
                "meta" to mapOf("title" to "t", "titleSource" to "custom"),
                "inputRouting" to mapOf("mode" to "choice", "reasonCode" to "held"),
                "availability" to mapOf(
                    "compact" to mapOf("allowed" to true),
                    "sendQueuedNow" to mapOf("allowed" to false, "reasonCode" to "sendQueuedNowRequiresRunning"),
                ),
                "queue" to mapOf(
                    "autoDrain" to false,
                    "pauseReason" to "stopped",
                    "items" to listOf(
                        mapOf(
                            "queueItemId" to "q1",
                            "kind" to "sendText",
                            "text" to "queued msg",
                            "order" to 1L,
                            "clientId" to "c",
                            "attachments" to listOf(mapOf("ref" to "r")),
                            "dispatch" to mapOf("state" to "queued"),
                        ),
                        mapOf("queueItemId" to "q2", "kind" to "compact", "text" to ""),
                    ),
                ),
                "goal" to mapOf(
                    "targetId" to "g1", "objective" to "ship it",
                    "status" to "active", "iteration" to 2L,
                ),
                "backgroundWorks" to listOf(
                    mapOf("workId" to "w1", "kind" to "bash", "title" to "build", "status" to "running"),
                ),
                "subagents" to mapOf("revision" to 1L, "running" to listOf("a"), "endedTotal" to 3L),
                "config" to mapOf("mode" to "yolo", "thoughtLevels" to listOf("low")),
            ),
        )

        assertEquals("sess-1", state.sessionId)
        assertEquals("custom", state.titleSource)
        assertTrue(state.isRunning)
        assertEquals("exec-1", state.foregroundExecutionId)
        assertTrue(state.needsQueueDecision)
        assertEquals("held", state.inputRoutingReason)
        assertTrue(state.allowed("compact"))
        assertFalse(state.allowed("sendQueuedNow"))
        assertEquals("sendQueuedNowRequiresRunning", state.unavailableReason("sendQueuedNow"))

        assertEquals(2, state.queue.items.size)
        assertTrue(state.queue.isPaused)
        assertEquals("stopped", state.queue.pauseReason)
        assertEquals("queued msg", state.queue.items[0].text)
        assertEquals(1, state.queue.items[0].attachmentCount)
        assertTrue(state.queue.items[1].isMaintenance)

        assertEquals("ship it", state.goal!!.objective)
        assertTrue(state.goal!!.isActive)
        assertTrue(state.hasBackgroundWork)
        assertEquals(3L, state.subagents.endedTotal)
    }

    @Test
    fun `state updated clears pending interactions on a present empty array`() {
        // Branch on key presence, not truthiness: the host clears the list with
        // a present-but-empty array, and a deep merge would leave the prompt up.
        val withPrompt = ConversationState(
            pending = listOf(
                PendingInteraction("i1", "permission", "Allow?", null, emptyList(), emptyMap()),
            ),
        )

        val cleared = ConversationReducer.applyDeltas(
            withPrompt,
            listOf(mapOf("op" to "state.updated", "patch" to mapOf("pendingInteractions" to emptyList<Any?>()))),
        )
        assertTrue(cleared.pending.isEmpty())

        // An unrelated patch leaves it alone.
        val untouched = ConversationReducer.applyDeltas(
            withPrompt,
            listOf(mapOf("op" to "state.updated", "patch" to mapOf("revision" to 9L))),
        )
        assertEquals(1, untouched.pending.size)
        assertEquals(9L, untouched.revision)
    }

    @Test
    fun `state updated can null out the goal`() {
        val withGoal = ConversationState(
            goal = GoalState("g1", "obj", null, "active", 1, 0),
        )
        val cleared = ConversationReducer.applyDeltas(
            withGoal,
            listOf(mapOf("op" to "state.updated", "patch" to mapOf("goal" to null))),
        )
        assertNull(cleared.goal)
    }

    @Test
    fun `state updated replaces the queue wholesale`() {
        val base = ConversationState(
            queue = QueueState(
                items = listOf(QueueItem("q1", "sendText", "a", 1, "queued", "c", 0, emptyMap())),
                autoDrain = false,
            ),
        )
        val next = ConversationReducer.applyDeltas(
            base,
            listOf(
                mapOf(
                    "op" to "state.updated",
                    "patch" to mapOf("queue" to mapOf("items" to emptyList<Any?>(), "autoDrain" to true)),
                ),
            ),
        )
        assertTrue(next.queue.isEmpty)
        assertTrue(next.queue.autoDrain)
        assertFalse(next.queue.isPaused)
    }

    @Test
    fun `row upserted replaces by rowId rather than duplicating`() {
        val base = ConversationReducer.applySnapshot(
            emptyMap(),
            mapOf("rows" to mapOf("window" to listOf(row(1, "userInput", "hi")))),
        )
        val next = ConversationReducer.applyDeltas(
            base,
            listOf(mapOf("op" to "row.upserted", "row" to row(1, "userInput", "edited"))),
        )
        assertEquals(1, next.rows.size)
        assertEquals("edited", next.rows[0].text)
    }

    @Test
    fun `row removed drops that row and everything after it`() {
        val base = ConversationReducer.applySnapshot(
            emptyMap(),
            mapOf(
                "rows" to mapOf(
                    "window" to listOf(row(1, "userInput", "a"), row(2, "assistantText", "b"), row(3, "assistantText", "c")),
                ),
            ),
        )
        val next = ConversationReducer.applyDeltas(
            base,
            listOf(mapOf("op" to "row.removed", "fromRowId" to 2L)),
        )
        assertEquals(listOf(1L), next.rows.map { it.rowId })
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private fun hookTarget() = Commands.HookReviewTarget(
        sessionId = "sess-1",
        taskId = "sess-1",
        runId = "run-1",
        workspaceIdentity = "E:\\ws",
        bundleDigest = "sha256:${"a".repeat(64)}",
        reviewFlowId = "flow-1",
        generation = 1,
        interactionId = "i1",
    )

    private fun summary(id: String, title: String, activity: Long) = mapOf<String, Any?>(
        "sessionId" to id,
        "workspaceId" to "E:\\ws",
        "title" to title,
        "phase" to "completedSuccess",
        "sessionEnded" to true,
        "hasBackgroundWork" to false,
        "lastActivityAt" to activity,
        "createdAt" to 1L,
    )

    private fun row(id: Long, kind: String, text: String) = mapOf<String, Any?>(
        "rowId" to id,
        "kind" to kind,
        "text" to text,
    )
}
