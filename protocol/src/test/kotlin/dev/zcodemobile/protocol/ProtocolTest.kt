package dev.zcodemobile.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden tests pinned to values observed on the wire.
 *
 * Where a literal came from live traffic it is marked; those are the ones that
 * catch a regression that would otherwise only show up as "the phone renders
 * nothing".
 */
class ProtocolTest {

    // ── VQL ───────────────────────────────────────────────────────────────

    @Test
    fun `vql encodes small ints in one byte`() {
        val w = Vql.Writer()
        Vql.writeInt(w, 127)
        assertContentEquals(byteArrayOf(0x7f), w.toByteArray())

        val w2 = Vql.Writer()
        Vql.writeInt(w2, 128)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), w2.toByteArray())
    }

    @Test
    fun `vql round-trips a channel initialize message`() {
        // Live capture: the first frame the host sends after the bridge opens
        // decoded to exactly `04 01 06 c8 01 00`
        //   Array(1) + Int(200) + Undefined  ==  serialize([Initialize])
        val encoded = Vql.encodeMessage(listOf(200), null)
        assertContentEquals(
            byteArrayOf(0x04, 0x01, 0x06, 0xc8.toByte(), 0x01, 0x00),
            encoded,
        )

        val (header, body) = Vql.decodeMessage(encoded)
        assertEquals(listOf(200L), (header as List<*>).map { (it as Number).toLong() })
        assertEquals(null, body)
    }

    @Test
    fun `vql round-trips a promise request header`() {
        val header = listOf(100, 1, "zcode-agent", "helloConversationV4")
        val body = listOf<Any?>(linkedMapOf("workspacePath" to "E:\\open_trae_m"))

        val (h, b) = Vql.decodeMessage(Vql.encodeMessage(header, body))
        val hl = h as List<*>
        assertEquals(100L, (hl[0] as Number).toLong())
        assertEquals(1L, (hl[1] as Number).toLong())
        assertEquals("zcode-agent", hl[2])
        assertEquals("helloConversationV4", hl[3])

        val bl = (b as List<*>)
        assertEquals("E:\\open_trae_m", Json.asString(Json.asMap(bl[0])["workspacePath"]))
    }

    @Test
    fun `vql round-trips unicode and long strings across the vql boundary`() {
        val long = "中文消息 — emoji 🚀 " + "x".repeat(300)
        val (_, body) = Vql.decodeMessage(Vql.encodeMessage(emptyList<Any?>(), long))
        assertEquals(long, body)
    }

    @Test
    fun `vql rejects an unknown type tag rather than guessing`() {
        val bad = byteArrayOf(99)
        val threw = runCatching { Vql.decodeMessage(bad) }.isFailure
        assertTrue(threw, "an unknown VQL tag must not be silently accepted")
    }

    // ── CRC32 ─────────────────────────────────────────────────────────────

    @Test
    fun `crc32 matches the value observed in a live rpc-frame`() {
        // The 6-byte Initialize payload was reported by the host with
        // checksum.value = "b4ff6360"; verify our implementation reproduces it.
        val payload = byteArrayOf(0x04, 0x01, 0x06, 0xc8.toByte(), 0x01, 0x00)
        assertEquals("b4ff6360", Crc32.hex(payload))
    }

    @Test
    fun `crc32 is formatted as 8 lowercase hex chars`() {
        val hex = Crc32.hex("zcode".toByteArray())
        assertEquals(8, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" }, "got $hex")
    }

    // ── link parsing ──────────────────────────────────────────────────────

    @Test
    fun `parses a percent-encoded relay link`() {
        val raw = "https://zcode.z.ai/remote/v4" +
            "?sid=d_QVhdHsDAk7nrURU3dThDmU" +
            "&hash=lTfwvocMJkZhz7eVHu%2BwR2SNPGq3AG5g%2FUgHF%2FGO2js%3D" +
            "&t=1790325109310" +
            "&mid=6765fd52-8970-4479-9050-4b2a6c45d89b" +
            "&name=DESKTOP-PA49OL0&app_version=3.14.3"

        val link = RemoteLink.parse(raw)
        assertEquals("d_QVhdHsDAk7nrURU3dThDmU", link.deviceSid)
        // %2B -> '+', %3D -> '=' ; the hash stays base64 (not base64url)
        assertEquals("lTfwvocMJkZhz7eVHu+wR2SNPGq3AG5g/UgHF/GO2js=", link.passHash)
        assertEquals(1790325109310L, link.timestamp)
        assertEquals("6765fd52-8970-4479-9050-4b2a6c45d89b", link.deviceMid)
        assertEquals("DESKTOP-PA49OL0", link.deviceName)
        assertEquals("3.14.3", link.appVersion)
    }

    @Test
    fun `rejects a link missing any required parameter`() {
        val base = "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=1"
        assertNotNull(RemoteLink.parse(base))

        for (broken in listOf(
            "https://zcode.z.ai/remote/v4?hash=h&t=1",          // no sid
            "https://zcode.z.ai/remote/v4?sid=s&t=1",           // no hash
            "https://zcode.z.ai/remote/v4?sid=s&hash=h",        // no t
            "https://zcode.z.ai/remote/v4?sid=s&hash=h&t=abc",  // t not numeric
        )) {
            assertTrue(
                runCatching { RemoteLink.parse(broken) }.isFailure,
                "should have rejected: $broken",
            )
        }
    }

    // ── auth proof ────────────────────────────────────────────────────────

    @Test
    fun `proof is base64url without padding`() {
        val proof = RelayTransport.calculateProof(
            passHash = "lTfwvocMJkZhz7eVHu+wR2SNPGq3AG5g/UgHF/GO2js=",
            nonce = "8jKGNpQ8o54ghmZaoNQ4Vsu",
            role = "terminal",
            deviceSid = "d_QVhdHsDAk7nrURU3dThDmU",
        )
        // 32-byte HMAC → 43 base64url chars with no '=' padding.
        assertEquals(43, proof.length)
        assertTrue(proof.none { it == '+' || it == '/' || it == '=' }, "got $proof")
        assertTrue(proof.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "got $proof")
    }

    @Test
    fun `proof is deterministic and sensitive to every input`() {
        val base = RelayTransport.calculateProof("key", "nonce", "terminal", "sid")

        assertEquals(base, RelayTransport.calculateProof("key", "nonce", "terminal", "sid"))

        // Each component must feed the HMAC; a swapped argument order would
        // still be self-consistent, so assert pairwise difference instead.
        for (variant in listOf(
            RelayTransport.calculateProof("key2", "nonce", "terminal", "sid"),
            RelayTransport.calculateProof("key", "nonce2", "terminal", "sid"),
            RelayTransport.calculateProof("key", "nonce", "web", "sid"),
            RelayTransport.calculateProof("key", "nonce", "terminal", "sid2"),
        )) {
            assertTrue(variant != base, "proof ignored one of its inputs")
        }
    }

    @Test
    fun `proof message template is role-separated by pipes`() {
        // The template is `${nonce}|${role}|${sid}`; a different separator
        // yields a different digest, so this pins the format.
        val a = RelayTransport.calculateProof("k", "n", "r", "s")
        val b = RelayTransport.calculateProof("k", "n|r", "", "s")
        assertTrue(a != b, "separator is not part of the signed message")
    }

    // ── reducer ───────────────────────────────────────────────────────────

    private fun rowJson(
        rowId: Long,
        kind: String,
        text: String? = null,
        state: String? = null,
    ) = linkedMapOf<String, Any?>(
        "rowId" to rowId,
        "kind" to kind,
        "text" to text,
        "state" to state,
        "createdAt" to 1790333549186L,
    )

    @Test
    fun `snapshot builds rows and reads control, meta and revision`() {
        val snapshot = linkedMapOf<String, Any?>(
            "sessionId" to "sess_1",
            "logEpoch" to "epoch_1",
            "revision" to 3167L,
            "meta" to linkedMapOf<String, Any?>("title" to "分析APP技术栈"),
            "control" to linkedMapOf<String, Any?>("phase" to "running", "canStop" to true),
            "rows" to linkedMapOf<String, Any?>(
                "window" to listOf(rowJson(1, "userInput", "hi")),
                "totalCount" to 294L,
            ),
        )
        val state = ConversationReducer.applySnapshot(
            linkedMapOf("toSeq" to 52741L), snapshot,
        )

        assertEquals("sess_1", state.sessionId)
        assertEquals("分析APP技术栈", state.title)
        assertEquals("running", state.phase)
        assertTrue(state.canStop)
        assertEquals(3167L, state.revision)
        assertEquals(52741L, state.seq)
        assertEquals(294, state.totalCount)
        assertEquals(1, state.rows.size)
        assertEquals("userInput", state.rows[0].kind)
    }

    @Test
    fun `row upserted replaces in place instead of duplicating`() {
        // Live capture: the host streams whole-row updates as `row.upserted`.
        // Treating it as an append silently doubles the transcript.
        var state = ConversationState(rows = listOf(ConversationReducer.toRow(rowJson(1, "reasoning", "a"))))

        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.upserted", "row" to rowJson(1, "reasoning", "b")))
        )

        assertEquals(1, state.rows.size, "row.upserted must not append a second row")
        assertEquals("b", state.rows[0].text)
    }

    @Test
    fun `row upserted for an unknown id appends`() {
        var state = ConversationState(
            rows = listOf(ConversationReducer.toRow(rowJson(1, "reasoning", "a"))),
            totalCount = 1,
        )
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.upserted", "row" to rowJson(2, "assistantText", "b"))),
        )
        assertEquals(2, state.rows.size)
        assertEquals(2, state.totalCount)
    }

    @Test
    fun `row appended is idempotent on a repeated rowId`() {
        // Regression: the host re-appended a rowId the projection already held
        // (rewind/replay edge); the duplicate crashed LazyColumn with
        // `Key "…" was already used`. A repeat must replace, not append.
        var state = ConversationState(
            rows = listOf(ConversationReducer.toRow(rowJson(1, "userInput", "a"))),
            totalCount = 1,
        )

        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.appended", "row" to rowJson(1, "userInput", "a2")))
        )

        assertEquals(1, state.rows.size, "repeated row.appended must not duplicate a row")
        assertEquals("a2", state.rows[0].text)
        assertEquals(1, state.totalCount, "a repeated append must not inflate totalCount")
    }

    @Test
    fun `row delta appends to the addressed field only`() {
        var state = ConversationState(rows = listOf(ConversationReducer.toRow(rowJson(7, "assistantText", "Hel"))))
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.delta", "rowId" to 7L, "path" to "text", "append" to "lo")),
        )
        assertEquals("Hello", state.rows[0].text)
    }

    @Test
    fun `row delta ignores non-streamable paths`() {
        var state = ConversationState(rows = listOf(ConversationReducer.toRow(rowJson(7, "toolCall", "x"))))
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.delta", "rowId" to 7L, "path" to "toolName", "append" to "!"))
        )
        assertEquals("x", state.rows[0].text)
    }

    @Test
    fun `row removed drops the row and everything after it`() {
        var state = ConversationState(
            rows = listOf(1L, 2L, 3L, 4L).map { ConversationReducer.toRow(rowJson(it, "assistantText", "$it")) },
            totalCount = 4,
        )
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "row.removed", "fromRowId" to 3L)),
        )
        assertEquals(listOf(1L, 2L), state.rows.map { it.rowId })
    }

    @Test
    fun `state updated applies a shallow patch`() {
        // Live capture: `{"op":"state.updated","patch":{"revision":3187}}`
        var state = ConversationState(revision = 3167, phase = "running", canStop = true)
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "state.updated", "patch" to linkedMapOf("revision" to 3187L))),
        )
        assertEquals(3187L, state.revision)
        // Fields absent from the patch must survive.
        assertEquals("running", state.phase)
        assertTrue(state.canStop)
    }

    @Test
    fun `state updated can replace control wholesale`() {
        var state = ConversationState(phase = "running", canStop = true, revision = 1)
        state = ConversationReducer.applyDeltas(
            state,
            listOf(
                linkedMapOf(
                    "op" to "state.updated",
                    "patch" to linkedMapOf(
                        "control" to linkedMapOf("phase" to "completed", "canStop" to false),
                        "meta" to linkedMapOf("title" to "新标题"),
                        "revision" to 42L,
                    ),
                )
            ),
        )
        assertEquals("completed", state.phase)
        assertEquals(false, state.canStop)
        assertEquals("新标题", state.title)
        assertEquals(42L, state.revision)
    }

    @Test
    fun `empty pendingInteractions in a patch clears the list`() {
        // Approving the last prompt arrives as an empty array; branching on
        // truthiness instead of key presence would leave the dialog stuck open.
        var state = ConversationState(
            pending = listOf(
                PendingInteraction(
                    interactionId = "i1", kind = "permission", title = "run?",
                    description = null, options = emptyList(), raw = emptyMap(),
                )
            )
        )
        state = ConversationReducer.applyDeltas(
            state,
            listOf(
                linkedMapOf(
                    "op" to "state.updated",
                    "patch" to linkedMapOf("pendingInteractions" to emptyList<Any?>()),
                )
            ),
        )
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `unknown ops are reported but do not abort the batch`() {
        val unknown = mutableListOf<String>()
        var state = ConversationState(rows = emptyList())
        state = ConversationReducer.applyDeltas(
            state,
            listOf(
                linkedMapOf("op" to "some.future.op"),
                linkedMapOf("op" to "row.appended", "row" to rowJson(1, "assistantText", "ok")),
            ),
            onUnknownOp = { unknown += it },
        )
        assertEquals(listOf("some.future.op"), unknown)
        assertEquals(1, state.rows.size, "a later delta in the same batch must still apply")
    }

    @Test
    fun `row removed with a missing fromRowId is a no-op`() {
        val state = ConversationState(rows = listOf(ConversationReducer.toRow(rowJson(1, "assistantText", "a"))))
        val after = ConversationReducer.applyDeltas(state, listOf(linkedMapOf("op" to "row.removed")))
        assertEquals(1, after.rows.size)
    }

    // ── command envelopes ─────────────────────────────────────────────────

    @Test
    fun `sendText envelope has the documented shape`() {
        val env = Commands.sendText(
            clientId = "c1",
            sessionId = "s1",
            text = "hello",
            baseRevision = 3167,
            baseLogEpoch = "epoch",
            requestedDelivery = Commands.Delivery.StartNow,
            mode = Commands.Mode.Plan,
        )

        assertEquals("sendText", env["type"])
        assertEquals("c1", env["clientId"])
        assertEquals("s1", env["sessionId"])
        assertEquals(3167L, env["baseRevision"])
        assertEquals("epoch", env["baseLogEpoch"])
        assertTrue((env["commandId"] as String).isNotBlank())
        assertTrue((env["issuedAt"] as Long) > 0)

        val payload = Json.asMap(env["payload"])
        assertEquals("hello", payload["text"])
        assertEquals("startNow", payload["requestedDelivery"])
        assertEquals("plan", payload["mode"])
    }

    @Test
    fun `envelope omits optional CAS fields when absent`() {
        val env = Commands.sendText("c", "s", "hi")
        assertTrue(!env.containsKey("baseRevision"))
        assertTrue(!env.containsKey("baseLogEpoch"))
    }

    @Test
    fun `commandId is stable when supplied so retries deduplicate`() {
        val id = "fixed-id"
        val a = Commands.sendText("c", "s", "x", commandId = id)
        val b = Commands.sendText("c", "s", "x", commandId = id)
        assertEquals(id, a["commandId"])
        assertEquals(id, b["commandId"])
    }

    @Test
    fun `resolveInteraction carries the option and optional reason`() {
        val env = Commands.resolveInteraction(
            clientId = "c",
            sessionId = "s",
            interactionId = "i1",
            optionId = "allow",
            freeText = "because",
        )
        assertEquals("resolveInteraction", env["type"])
        val payload = Json.asMap(env["payload"])
        assertEquals("i1", payload["interactionId"])
        val answer = Json.asMap(payload["answer"])
        assertEquals("allow", answer["optionId"])
        assertEquals("because", answer["freeText"])
    }

    @Test
    fun `command ack parses each status`() {
        val accepted = CommandAck.parse(
            linkedMapOf(
                "commandId" to "x", "status" to "accepted",
                "revisionAtDecision" to 3078L,
            )
        )
        assertTrue(accepted.accepted)
        assertEquals(3078L, accepted.revisionAtDecision)

        val rejected = CommandAck.parse(
            linkedMapOf(
                "commandId" to "y", "status" to "rejected",
                "reasonCode" to "fault.command.clientMismatch",
            )
        )
        assertTrue(!rejected.accepted)
        assertEquals("fault.command.clientMismatch", rejected.reasonCode)
    }

    @Test
    fun `parsePendingInteractions reads id, titles and options`() {
        val snap = linkedMapOf<String, Any?>(
            "pendingInteractions" to listOf(
                linkedMapOf(
                    "interactionId" to "i1",
                    "kind" to "permission",
                    "title" to "允许执行 Bash？",
                    "description" to "rm -rf build",
                    "options" to listOf(
                        linkedMapOf("optionId" to "allow", "name" to "允许"),
                        linkedMapOf("optionId" to "deny", "name" to "拒绝"),
                    ),
                )
            )
        )
        val pending = parsePendingInteractions(snap)
        assertEquals(1, pending.size)
        assertEquals("i1", pending[0].interactionId)
        assertEquals("允许执行 Bash？", pending[0].title)
        assertEquals(listOf("allow", "deny"), pending[0].options.map { it.optionId })
    }

    @Test
    fun `parsePendingInteractions skips entries without an id`() {
        val snap = linkedMapOf<String, Any?>(
            "pendingInteractions" to listOf(linkedMapOf("kind" to "permission"))
        )
        assertTrue(parsePendingInteractions(snap).isEmpty())
    }

    // ── JSON ──────────────────────────────────────────────────────────────

    @Test
    fun `json round-trips nested structures and escapes`() {
        val value = linkedMapOf<String, Any?>(
            "s" to "quote\" back\\slash newline\n tab\t 中文 🚀",
            "n" to 42L,
            "d" to 1.5,
            "b" to true,
            "nul" to null,
            "arr" to listOf(1L, "two", false),
            "nested" to linkedMapOf<String, Any?>("k" to listOf(linkedMapOf("deep" to "v"))),
        )
        val back = Json.decode(Json.encode(value))
        assertEquals("quote\" back\\slash newline\n tab\t 中文 🚀", Json.asString(Json.asMap(back)["s"]))
        assertEquals(42L, Json.asLong(Json.asMap(back)["n"]))
        assertEquals(true, Json.asBool(Json.asMap(back)["b"]))
        assertEquals(3, Json.asList(Json.asMap(back)["arr"]).size)
    }

    @Test
    fun `json parses unicode escapes`() {
        val v = Json.asMap(Json.decode("""{"t":"\u4e2d\u6587"}"""))
        assertEquals("中文", Json.asString(v["t"]))
    }

    @Test
    fun `json accessors are total on wrong types`() {
        assertEquals(emptyMap<String, Any?>(), Json.asMap("not a map"))
        assertEquals(emptyList<Any?>(), Json.asList(7))
        assertEquals(null, Json.asString(42))
        assertEquals(null, Json.asBool("true"))
        assertEquals(null, Json.asLong("42"))
    }

    @Test
    fun `json handles large row ids without precision loss`() {
        // rowId and createdAt are millisecond-scale; Long fidelity matters.
        val big = 1790333549186L
        val back = Json.decode(Json.encode(linkedMapOf("rowId" to big)))
        assertEquals(big, Json.asLong(Json.asMap(back)["rowId"]))
        assertTrue(abs(big) > Int.MAX_VALUE.toLong())
    }

    // ── task list ─────────────────────────────────────────────────────────

    private fun taskJson(
        taskId: String,
        title: String,
        status: String?,
        updatedAt: Long?,
        provider: String? = "glm",
        workspaceLabel: String? = "open_trae_m",
    ) = linkedMapOf<String, Any?>(
        "taskId" to taskId,
        "title" to title,
        "displayStatus" to status,
        "updatedAt" to updatedAt,
        "provider" to provider,
        "workspaceLabel" to workspaceLabel,
        "workspacePath" to "E:\\open_trae_m",
    )

    @Test
    fun `duplicate taskIds collapse to one entry preferring running`() {
        // Live desktop reported the same session as both `completed` (stale) and
        // `running`. Two entries with one key crashes LazyColumn.
        val tasks = parseTaskSummaries(
            listOf(
                taskJson("sess_1", "分析APP技术栈", "completed", 1000),
                taskJson("sess_1", "分析APP技术栈", "running", 900),
            )
        )
        assertEquals(1, tasks.size)
        assertEquals("running", tasks[0].displayStatus)
    }

    @Test
    fun `duplicate taskIds without a running entry keep the newest`() {
        val tasks = parseTaskSummaries(
            listOf(
                taskJson("sess_1", "old", "completed", 1000),
                taskJson("sess_1", "new", "completed", 5000),
            )
        )
        assertEquals(1, tasks.size)
        assertEquals("new", tasks[0].title)
    }

    @Test
    fun `task list is sorted newest first and drops entries without an id`() {
        val tasks = parseTaskSummaries(
            listOf(
                taskJson("sess_a", "a", "completed", 100),
                linkedMapOf<String, Any?>("title" to "no id"),
                taskJson("sess_b", "b", "completed", 900),
            )
        )
        assertEquals(listOf("sess_b", "sess_a"), tasks.map { it.taskId })
    }

    @Test
    fun `task fields survive parsing`() {
        val t = parseTaskSummaries(listOf(taskJson("s", "标题", "running", 42, "glm", "ws"))).single()
        assertEquals("标题", t.title)
        assertEquals("glm", t.provider)
        assertEquals("ws", t.workspaceLabel)
        assertEquals(42L, t.updatedAt)
    }

    @Test
    fun `unreadAt marks a task unread`() {
        val read = parseTaskSummaries(listOf(taskJson("s", "t", "completed", 1))).single()
        assertTrue(!read.unread)

        val unread = parseTaskSummaries(
            listOf(taskJson("s", "t", "completed", 1) + ("unreadAt" to 5L))
        ).single()
        assertTrue(unread.unread)
    }

    @Test
    fun `empty or malformed task payloads yield an empty list`() {
        assertTrue(parseTaskSummaries(null).isEmpty())
        assertTrue(parseTaskSummaries("nope").isEmpty())
        assertTrue(parseTaskSummaries(emptyList<Any?>()).isEmpty())
    }

    // ── composer state ────────────────────────────────────────────────────

    @Test
    fun `snapshot exposes model mode and context budget`() {
        val snapshot = linkedMapOf<String, Any?>(
            "sessionId" to "s",
            "config" to linkedMapOf<String, Any?>(
                "provider" to "new-provider-7",
                "model" to "cn:deepseek-v4.1-flash",
                "thought" to "max",
                "mode" to "yolo",
                "followupMode" to "queue",
                "planEnabled" to false,
            ),
            "usage" to linkedMapOf<String, Any?>(
                "contextWindow" to linkedMapOf<String, Any?>(
                    "usedTokens" to 454295L,
                    "maxTokens" to 1_000_000L,
                )
            ),
            "rows" to linkedMapOf<String, Any?>("window" to emptyList<Any?>(), "totalCount" to 0L),
        )
        val state = ConversationReducer.applySnapshot(emptyMap(), snapshot)

        assertEquals("cn:deepseek-v4.1-flash", state.model)
        assertEquals("new-provider-7", state.provider)
        assertEquals("yolo", state.mode)
        assertEquals("queue", state.followupMode)
        assertEquals("max", state.thoughtLevel)
        assertEquals(454295L, state.contextUsed)
        assertEquals(1_000_000L, state.contextMax)
        assertEquals(0.454295f, state.contextFraction!!, 1e-4f)
    }

    @Test
    fun `context fraction is null when the host reports no window`() {
        val state = ConversationState()
        assertEquals(null, state.contextFraction)

        // A zero max must not divide by zero.
        assertEquals(null, ConversationState(contextUsed = 10, contextMax = 0).contextFraction)
    }

    @Test
    fun `config and usage update through state patches`() {
        var state = ConversationState(model = "old", mode = "build", contextUsed = 1)
        state = ConversationReducer.applyDeltas(
            state,
            listOf(
                linkedMapOf(
                    "op" to "state.updated",
                    "patch" to linkedMapOf(
                        "config" to linkedMapOf<String, Any?>("model" to "new", "mode" to "yolo"),
                        "usage" to linkedMapOf<String, Any?>(
                            "contextWindow" to linkedMapOf<String, Any?>("usedTokens" to 99L)
                        ),
                    ),
                )
            ),
        )
        assertEquals("new", state.model)
        assertEquals("yolo", state.mode)
        assertEquals(99L, state.contextUsed)
    }

    @Test
    fun `absent config keys leave prior values intact`() {
        var state = ConversationState(model = "keep", mode = "yolo")
        state = ConversationReducer.applyDeltas(
            state,
            listOf(linkedMapOf("op" to "state.updated", "patch" to linkedMapOf("revision" to 7L))),
        )
        assertEquals("keep", state.model)
        assertEquals("yolo", state.mode)
    }

    // ── markdown-bearing rows ─────────────────────────────────────────────

    @Test
    fun `assistant rows keep raw markdown intact`() {
        // The UI renders this through a Markdown renderer; any escaping or
        // trimming here would corrupt fences and tables.
        val md = """
            ## 标题

            正文含 `inline code` 与 **粗体**。

            ```kotlin
            val x = 1
            ```

            | a | b |
            |---|---|
            | 1 | 2 |
        """.trimIndent()

        val row = ConversationReducer.toRow(
            linkedMapOf("rowId" to 1L, "kind" to "assistantText", "text" to md, "state" to "complete")
        )
        assertEquals(md, row.text)
    }

    // ── file changes ──────────────────────────────────────────────────────

    private fun editRow(
        tool: String = "Edit",
        file: String = "E:/p/app/Main.kt",
        old: String? = null,
        new: String? = null,
        content: String? = null,
        edits: List<Any?>? = null,
    ): Row = ConversationReducer.toRow(
        linkedMapOf(
            "rowId" to 1L,
            "kind" to "toolCall",
            "toolName" to tool,
            "status" to "success",
            "input" to buildMap<String, Any?> {
                put("file_path", file)
                old?.let { put("old_string", it) }
                new?.let { put("new_string", it) }
                content?.let { put("content", it) }
                edits?.let { put("edits", it) }
            },
        )
    )

    @Test
    fun `edit derives additions and deletions from the tool input`() {
        val change = fileChangeOf(
            editRow(old = "a\nb\nc", new = "a\nB\nc\nd")
        )!!
        assertEquals(4, change.additions)
        assertEquals(3, change.deletions)
        assertEquals("Main.kt", change.fileName)
        assertTrue(!change.isCreation)
    }

    @Test
    fun `write is a creation with only additions`() {
        val change = fileChangeOf(editRow(tool = "Write", content = "x\ny\nz"))!!
        assertEquals(3, change.additions)
        assertEquals(0, change.deletions)
        assertTrue(change.isCreation)
    }

    @Test
    fun `multiEdit sums every pair`() {
        val change = fileChangeOf(
            editRow(
                tool = "MultiEdit",
                edits = listOf(
                    linkedMapOf("old_string" to "a", "new_string" to "a\nb"),
                    linkedMapOf("old_string" to "c\nd", "new_string" to "c"),
                ),
            )
        )!!
        assertEquals(3, change.additions) // 2 + 1
        assertEquals(3, change.deletions) // 1 + 2
    }

    @Test
    fun `non-write tools produce no change card`() {
        for (tool in listOf("Bash", "Read", "Grep", "Glob", "WebFetch")) {
            val row = ConversationReducer.toRow(
                linkedMapOf(
                    "rowId" to 1L, "kind" to "toolCall", "toolName" to tool,
                    "input" to linkedMapOf("file_path" to "x"),
                )
            )
            assertEquals(null, fileChangeOf(row), "$tool should not yield a change card")
        }
    }

    @Test
    fun `an edit without its input yet yields no change card`() {
        val row = ConversationReducer.toRow(
            linkedMapOf("rowId" to 1L, "kind" to "toolCall", "toolName" to "Edit", "status" to "running")
        )
        assertEquals(null, fileChangeOf(row))
    }

    @Test
    fun `lineCount ignores a trailing newline`() {
        assertEquals(0, lineCount(""))
        assertEquals(0, lineCount("\n"))
        assertEquals(1, lineCount("a"))
        assertEquals(1, lineCount("a\n"))
        assertEquals(2, lineCount("a\nb"))
        assertEquals(2, lineCount("a\nb\n"))
    }

    @Test
    fun `windows paths are handled`() {
        val change = fileChangeOf(
            editRow(file = "E:\\open_trae_m\\zcode-mobile\\README.md", old = "a", new = "b")
        )!!
        assertEquals("README.md", change.fileName)
    }

    // ── model catalog ─────────────────────────────────────────────────────

    @Test
    fun `model catalog extracts only identifiers and never credentials`() {
        // The live getView payload embeds provider API keys under
        // config.access.apiKey. Nothing in the parsed catalog may carry them.
        val view = linkedMapOf<String, Any?>(
            "revision" to 33L,
            "providers" to listOf(
                linkedMapOf(
                    "providerId" to "new-provider-7",
                    "providerName" to "workbuddy",
                    "config" to linkedMapOf<String, Any?>(
                        "access" to linkedMapOf<String, Any?>(
                            "type" to "api-key",
                            "apiKey" to "sk-SECRET-MUST-NOT-LEAK",
                        ),
                    ),
                    "models" to listOf(
                        linkedMapOf<String, Any?>(
                            "modelId" to "cn:deepseek-v4.1-flash",
                            "config" to linkedMapOf<String, Any?>(
                                "enabled" to true,
                                "properties" to linkedMapOf<String, Any?>(
                                    "contextWindow" to 1_000_000L,
                                ),
                                // Levels live here, not at the model top level.
                                "optionSpecs" to linkedMapOf<String, Any?>(
                                    "reasoningLevel" to linkedMapOf<String, Any?>(
                                        "values" to listOf("disabled", "low", "high", "max"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            ),
        )

        val catalog = ModelCatalogService.parse(view)
        val models = catalog.models

        assertEquals(1, models.size)
        assertEquals("new-provider-7", models[0].providerId)
        assertEquals("deepseek-v4.1-flash", models[0].shortName)
        assertEquals(listOf("disabled", "low", "high", "max"), models[0].thoughtLevels)
        assertEquals(1_000_000L, models[0].contextWindow)

        val rendered = Json.encode(models.map { it.modelId to it.providerId })
        assertTrue(
            !rendered.contains("sk-"),
            "a credential reached the parsed catalog: $rendered",
        )
    }

    @Test
    fun `model catalog falls back to a flat thoughtLevels key`() {
        val view = linkedMapOf<String, Any?>(
            "providers" to listOf(
                linkedMapOf<String, Any?>(
                    "providerId" to "p",
                    "models" to listOf(
                        linkedMapOf<String, Any?>(
                            "modelId" to "m",
                            "thoughtLevels" to listOf("low", "high"),
                        ),
                    ),
                )
            ),
        )
        assertEquals(
            listOf("low", "high"),
            ModelCatalogService.parse(view).models.single().thoughtLevels,
        )
    }

    @Test
    fun `model catalog reads the vision flag from inputFormat`() {
        // The web shows a 视觉 badge on models with
        // config.properties.inputFormat.supportsImage (LIVE-WEB-VERIFICATION §4.1).
        fun view(supportsImage: Any?) = linkedMapOf<String, Any?>(
            "providers" to listOf(
                linkedMapOf<String, Any?>(
                    "providerId" to "p",
                    "models" to listOf(
                        linkedMapOf<String, Any?>(
                            "modelId" to "m",
                            "config" to linkedMapOf<String, Any?>(
                                "properties" to linkedMapOf<String, Any?>(
                                    "inputFormat" to linkedMapOf<String, Any?>(
                                        "supportsImage" to supportsImage,
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            ),
        )

        assertTrue(ModelCatalogService.parse(view(true)).models.single().supportsVision)
        assertFalse(ModelCatalogService.parse(view(false)).models.single().supportsVision)
        // Absent inputFormat (older payloads) must not crash or set the badge.
        assertFalse(ModelCatalogService.parse(view(null)).models.single().supportsVision)
    }

    @Test
    fun `shortName falls back to the id suffix`() {
        val m = ModelOption("p", null, "cn:glm-5.3", null, emptyList())
        assertEquals("glm-5.3", m.shortName)
    }

    // ── switch commands ───────────────────────────────────────────────────

    @Test
    fun `switchModelConfig carries provider model and thought`() {
        val env = Commands.switchModelConfig(
            clientId = "c", sessionId = "s",
            provider = "new-provider-7", model = "cn:glm-5.3", thought = "high",
            baseRevision = 6,
        )
        assertEquals("switchModelConfig", env["type"])
        val p = Json.asMap(env["payload"])
        assertEquals("new-provider-7", p["provider"])
        assertEquals("cn:glm-5.3", p["model"])
        assertEquals("high", p["thought"])
        assertEquals(6L, env["baseRevision"])
    }

    @Test
    fun `switchCollaborationMode rejects values outside the wire enum`() {
        val env = Commands.switchCollaborationMode("c", "s", Commands.Mode.Yolo, baseRevision = 6)
        assertEquals("switchCollaborationMode", env["type"])
        assertEquals("yolo", Json.asMap(env["payload"])["mode"])

        // `auto` is runtime state and deliberately absent from the UI enum.
        val offered = Commands.Mode.entries.map { it.wire }
        assertEquals(listOf("build", "edit", "plan", "yolo"), offered)
    }

    @Test
    fun `followup mode command uses the queue guide enum`() {
        val env = Commands.setFollowupMode("c", "s", Commands.Followup.Guide, baseRevision = 6)
        assertEquals("setFollowupMode", env["type"])
        assertEquals("guide", Json.asMap(env["payload"])["mode"])
    }
}
