package dev.zcodemobile.verify

import dev.zcodemobile.protocol.BridgeInfo
import dev.zcodemobile.protocol.ChannelClient
import dev.zcodemobile.protocol.ChannelProtocol
import dev.zcodemobile.protocol.Json
import dev.zcodemobile.protocol.RemoteLink
import dev.zcodemobile.protocol.RelayTransport
import dev.zcodemobile.protocol.WebSocketConnection
import dev.zcodemobile.protocol.WebSocketFactory
import dev.zcodemobile.protocol.WebSocketListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Live verification of the Kotlin protocol port against the hosted relay.
 *
 * Mirrors the Node reference implementation in `tools/relay-probe` so the two
 * can be diffed when the protocol drifts.
 *
 * Usage: `verify "<remote url>"`  (or put the URL in tools/relay-probe/link.txt)
 */

/**
 * Connection wrapper handed to the transport **before** the socket finishes
 * opening: `onOpen` fires while the HTTP client is still building it, and the
 * transport sends `auth_init` from that callback. Sends issued before the
 * underlying socket exists are buffered.
 */
internal class JvmWebSocketConnection : WebSocketConnection {
    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var closed = false

    private val pending = ArrayDeque<String>()

    override val isOpen: Boolean get() = ws != null && !closed

    override fun send(text: String) {
        val w = ws
        if (w == null) {
            synchronized(pending) { pending.addLast(text) }
        } else {
            w.sendText(text, true)
        }
    }

    override fun close(code: Int, reason: String) {
        closed = true
        runCatching { ws?.sendClose(code, reason) }
    }

    fun attach(socket: WebSocket) {
        val queued: List<String>
        synchronized(pending) {
            ws = socket
            queued = pending.toList()
            pending.clear()
        }
        queued.forEach { socket.sendText(it, true) }
    }

    fun markClosed() {
        closed = true
    }
}

/** java.net.http.WebSocket adapter — JVM-side implementation of the port. */
internal class JvmWebSocketFactory : WebSocketFactory {
    private val httpClient = HttpClient.newHttpClient()

    override fun open(url: String, listener: WebSocketListener): WebSocketConnection {
        val conn = JvmWebSocketConnection()

        httpClient.newWebSocketBuilder().buildAsync(URI.create(url), object : WebSocket.Listener {
            private val buf = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
                conn.attach(webSocket)
                listener.onOpen()
            }

            override fun onText(
                webSocket: WebSocket,
                data: CharSequence,
                last: Boolean,
            ): CompletionStage<*>? {
                buf.append(data)
                if (last) {
                    val text = buf.toString()
                    buf.setLength(0)
                    listener.onTextMessage(text)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(
                webSocket: WebSocket,
                statusCode: Int,
                reason: String,
            ): CompletionStage<*>? {
                conn.markClosed()
                listener.onClosed(statusCode, reason)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                conn.markClosed()
                listener.onFailure(error)
            }
        })

        return conn
    }
}

internal fun log(s: String) = println(s)

fun main(args: Array<String>): Unit = runBlocking {
    val raw = args.firstOrNull()
        ?: runCatching { java.io.File("tools/relay-probe/link.txt").readText().trim() }.getOrNull()
        ?: run {
            log("usage: verify \"<https://zcode.z.ai/remote/v4?sid=..&hash=..&t=..>\"")
            exitProcess(2)
        }

    val link = RemoteLink.parse(raw)
    log("── parsed link ─────────────────────────────────")
    log("  deviceSid : ${link.deviceSid}")
    log("  deviceMid : ${link.deviceMid ?: "(none)"}")
    log("  appVersion: ${link.appVersion ?: "(none)"}")
    log("  link age  : ${link.ageMillis / 60000.0} min")
    log("  proof(selftest) = ${RelayTransport.calculateProof(link.passHash, "nonce", "terminal", link.deviceSid).take(24)}…")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = RelayTransport(link, JvmWebSocketFactory()) { msg, _ -> log("  $msg") }
    val channel = ChannelClient(transport, scope) { msg -> log("  $msg") }

    try {
        log("")
        log("── handshake ───────────────────────────────────")
        transport.connect(scope)
        log("  pairStatus=${transport.pairStatus} terminalSid=${transport.terminalSid}")

        log("")
        log("── bootstrap ───────────────────────────────────")
        val boot = transport.bootstrap()
        log("  desktop=${boot.desktopAppVersion} activeWs=${boot.activeWorkspaceKey}")
        log("  ${boot.workspaces.size} workspace(s), ${boot.tasks.size} task(s)")
        boot.workspaces.take(6).forEach { log("    • ${it.workspacePath}  kind=${it.kind}") }

        val workspaceKey = boot.activeWorkspaceKey
            ?: boot.workspaces.firstOrNull()?.workspacePath
            ?: throw IllegalStateException("no workspace to bridge")

        log("")
        log("── bridge ──────────────────────────────────────")
        val bridge: BridgeInfo = transport.openBridge(workspaceKey, boot.activeTaskId)
        log("  bridgeSessionId=${bridge.bridgeSessionId} gen=${bridge.bridgeGeneration}")

        log("")
        log("── channel ─────────────────────────────────────")
        channel.start()
        channel.awaitInitialized()

        log("")
        log("── v4 handshake ────────────────────────────────")
        val hello = Json.asMap(
            channel.call(ChannelProtocol.CHANNEL_ZCODE_AGENT, "helloConversationV4")
        )
        log("  hello: clientMode=${hello["clientMode"]} profile=${hello["deliveryProfile"]} " +
            "v=${hello["protocolVersion"]}")
        val hostCaps = Json.asMap(hello["capabilities"])
        log("  host capabilities: ${Json.encode(hostCaps)}")

        channel.call(
            ChannelProtocol.CHANNEL_ZCODE_AGENT,
            "initializeConversationV4",
            linkedMapOf(
                "kind" to "clientHello",
                "protocolVersion" to 3,
                "clientId" to "zcode-mobile-${java.util.UUID.randomUUID()}",
                "clientKind" to "mobileApp",
                "appVersion" to "0.1.0",
                "capabilities" to linkedMapOf(
                    "workspaceHookReviewUi" to true,
                    // Only advertise what the host declared, and only when it
                    // did: the host's capabilities object is `.strict()`.
                    *(
                        if (hostCaps["workflowRunDeltas"] == true)
                            arrayOf("workflowRunDeltas" to true)
                        else emptyArray()
                        ),
                ),
            ),
        )
        log("  clientHello accepted")

        val sessionId = boot.activeTaskId
            ?: boot.tasks.firstOrNull()?.taskId
            ?: throw IllegalStateException("no session to subscribe")

        var snapshotRows = 0
        var wireFrames = 0
        val latch = CountDownLatch(1)

        channel.listen(
            ChannelProtocol.CHANNEL_ZCODE_AGENT,
            "onDynamicConversationFrame",
            linkedMapOf("workspacePath" to workspaceKey),
        ) { candidate ->
            val c = Json.asMap(candidate)
            wireFrames++
            val frame = Json.asMap(c["frame"])
            if (frame.isNotEmpty()) {
                val payload = Json.asMap(frame["payload"])
                when (Json.asString(payload["kind"])) {
                    "snapshot" -> {
                        val snap = Json.asMap(payload["snapshot"])
                        val rows = Json.asList(Json.asMap(snap["rows"])["window"])
                        snapshotRows = rows.size
                        log("  SNAPSHOT rows=${rows.size} " +
                            "total=${Json.asMap(snap["rows"])["totalCount"]} " +
                            "session=${Json.asString(snap["sessionId"])}")
                        log("  ${"rowId".padStart(5)}  ${"kind".padEnd(14)} preview")
                        rows.takeLast(12).forEach { r ->
                            val row = Json.asMap(r)
                            val kind = Json.asString(row["kind"]) ?: "?"
                            val preview = (
                                Json.asString(row["text"])
                                    ?: Json.asString(row["toolName"])
                                    ?: Json.encode(row)
                                ).replace(Regex("\\s+"), " ").take(64)
                            log("  ${(row["rowId"]?.toString() ?: "?").padStart(5)}  " +
                                "${kind.padEnd(14)} $preview")
                        }
                        latch.countDown()
                    }
                    "deltas" -> log("  DELTAS ${Json.asList(payload["deltas"]).size} op(s)")
                }
            }
        }

        log("")
        log("── subscribe ───────────────────────────────────")
        val sub = Json.asMap(
            channel.call(
                ChannelProtocol.CHANNEL_ZCODE_AGENT,
                "subscribeConversationV4",
                linkedMapOf("workspacePath" to workspaceKey, "sessionId" to sessionId),
            )
        )
        val ack = Json.asMap(sub["ack"])
        log("  ack: sub=${Json.asString(ack["subscriptionId"])} mode=${Json.asString(ack["mode"])} " +
            "logEpoch=${Json.asString(ack["logEpoch"])}")

        log("")
        log("── awaiting live frames ────────────────────────")
        repeat(100) { if (latch.count == 0L) return@repeat; delay(100) }

        log("")
        log("── result ──────────────────────────────────────")
        log("  snapshot delivered : ${snapshotRows > 0}  (${snapshotRows} rows)")
        log("  wire frames total  : $wireFrames")
        if (snapshotRows > 0) {
            log("  ✓ Kotlin port verified against the live relay")
        } else {
            log("  ✗ no snapshot received")
            exitProcess(1)
        }
    } finally {
        transport.close()
        scope.cancel()
    }
}
