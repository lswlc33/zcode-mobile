package dev.zcodemobile.verify

import dev.zcodemobile.protocol.ChannelClient
import dev.zcodemobile.protocol.CommandAck
import dev.zcodemobile.protocol.Commands
import dev.zcodemobile.protocol.ConversationApi
import dev.zcodemobile.protocol.Json
import dev.zcodemobile.protocol.RelayTransport
import dev.zcodemobile.protocol.RemoteLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Verifies the v4 **command** path (the write side) against the live relay.
 *
 * Deliberately submits a command that the host will reject on availability
 * grounds (`pauseGoal` on a session with no goal): this exercises envelope
 * construction, channel dispatch, host-side validation and typed ack handling
 * while leaving the user's session completely unmodified.
 *
 * `sendText` is built and printed but never submitted — pushing a real turn
 * into someone's live session is a side effect a probe has no business causing.
 */
fun main(): Unit = runBlocking {
    val raw = File("tools/relay-probe/link.txt").readText().trim()
    val link = RemoteLink.parse(raw)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = RelayTransport(link, JvmWebSocketFactory()) { msg, _ -> println("  $msg") }
    val channel = ChannelClient(transport, scope) { println("  $it") }

    try {
        println("── connect ─────────────────────────────────────")
        transport.connect(scope)
        val boot = transport.bootstrap()
        val workspace = boot.activeWorkspaceKey
            ?: boot.workspaces.firstOrNull()?.workspacePath
            ?: throw IllegalStateException("no workspace")
        val sessionId = boot.activeTaskId
            ?: boot.tasks.firstOrNull()?.taskId
            ?: throw IllegalStateException("no session")
        println("  workspace=$workspace")
        println("  session=$sessionId")

        transport.openBridge(workspace, sessionId)
        channel.start()
        channel.awaitInitialized()

        val api = ConversationApi(channel)
        println("\n── v4 handshake ────────────────────────────────")
        val hello = api.handshake(clientId = "zcode-mobile-probe-${java.util.UUID.randomUUID()}")
        println("  host clientMode=${hello["clientMode"]} profile=${hello["deliveryProfile"]}")

        println("\n── frame stream (registered before subscribe) ──")
        // The snapshot frame follows immediately after subscribe, so the
        // listener has to be attached first or the frame is missed.
        var snapshot: Map<String, Any?> = emptyMap()
        val listener = api.listenFrames(workspace) { candidate ->
            val frame = Json.asMap(candidate["frame"])
            val payload = Json.asMap(frame["payload"])
            if (Json.asString(payload["kind"]) == "snapshot") {
                snapshot = Json.asMap(payload["snapshot"])
            }
        }

        println("\n── subscribe ───────────────────────────────────")
        val sub = api.subscribe(workspace, sessionId)
        val ack = Json.asMap(sub["ack"])
        val subId = Json.asString(ack["subscriptionId"])
        println("  subscriptionId=$subId mode=${Json.asString(ack["mode"])}")

        for (i in 0 until 80) {
            if (snapshot.isNotEmpty()) break
            delay(100)
        }
        val revision = Json.asLong(snapshot["revision"])
        val logEpoch = Json.asString(snapshot["logEpoch"])
        val availability = Json.asMap(snapshot["availability"])
        val pauseGoal = Json.asMap(availability["pauseGoal"])
        println("  revision=$revision logEpoch=$logEpoch rows=${Json.asList(Json.asMap(snapshot["rows"])["window"]).size}")
        println("  availability.pauseGoal=${Json.encode(pauseGoal)}")

        println("\n── command envelope shape ──────────────────────")
        // clientId must equal the one registered at handshake, else the host
        // answers fault.command.clientMismatch.
        val envelopeClientId = api.clientId!!
        println("  handshake clientId = $envelopeClientId")

        val demoEnvelope = Commands.sendText(
            clientId = envelopeClientId,
            sessionId = sessionId,
            text = "…",
            baseRevision = revision,
            baseLogEpoch = logEpoch,
        )
        println("  sendText envelope (NOT submitted):")
        println("    ${Json.encode(demoEnvelope)}")

        val interactionDemo = Commands.resolveInteraction(
            clientId = envelopeClientId,
            sessionId = sessionId,
            interactionId = "…",
            optionId = "allow",
        )
        println("  resolveInteraction envelope (NOT submitted):")
        println("    ${Json.encode(interactionDemo)}")

        println("\n── submitting a no-op command (expect rejection) ─")
        // pauseGoal with no goal → typed reasonCode, zero state change.
        val envelope = Commands.envelopeFor(
            type = "pauseGoal",
            clientId = envelopeClientId,
            sessionId = sessionId,
            baseRevision = revision,
            baseLogEpoch = logEpoch,
            payload = emptyMap<String, Any?>(),
        )
        val commandId = envelope["commandId"] as String
        println("  commandId=$commandId type=pauseGoal")

        val result: CommandAck = api.sendCommand(workspace, envelope)
        println("  ← CommandAck:")
        println("      status          = ${result.status}")
        println("      reasonCode      = ${result.reasonCode}")
        println("      revisionAtDecision = ${result.revisionAtDecision}")
        println("      message         = ${result.message}")

        println("\n── idempotency probe (query the command back) ──")
        val q = api.queryCommands(workspace, listOf(sessionId to commandId))
        println("  ${Json.encode(Json.asList(q["results"]).firstOrNull())}")

        println("\n── result ──────────────────────────────────────")
        // A rejection still proves the whole submission path end to end.
        val ok = result.status.isNotEmpty() && result.commandId == commandId
        if (ok) {
            println("  ✓ command path verified (envelope accepted, host responded with a typed ack)")
        } else {
            println("  ✗ unexpected ack: ${Json.encode(result.raw)}")
        }

        api.disposeListener(listener)
        subId?.let { runCatching { api.unsubscribe(workspace, it) } }
    } finally {
        transport.close()
        scope.cancel()
    }
}
