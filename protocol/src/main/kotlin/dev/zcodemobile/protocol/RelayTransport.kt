package dev.zcodemobile.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A parsed `https://zcode.z.ai/remote/v4?sid=..&hash=..&t=..` link. */
data class RemoteLink(
    val deviceSid: String,
    val passHash: String,
    val timestamp: Long,
    val deviceMid: String? = null,
    val deviceName: String? = null,
    val appVersion: String? = null,
) {
    val ageMillis: Long get() = System.currentTimeMillis() - timestamp

    companion object {
        private fun q(raw: String, key: String): String? {
            val marker = "$key="
            var idx = raw.indexOf("?$marker")
            if (idx < 0) idx = raw.indexOf("&$marker")
            if (idx < 0) return null
            val start = idx + marker.length + 1
            var end = raw.indexOf('&', start)
            if (end < 0) end = raw.length
            return URLDecoder.decode(raw.substring(start, end), "UTF-8")
        }

        fun parse(raw: String): RemoteLink {
            val sid = q(raw, "sid") ?: throw IllegalArgumentException(
                "Missing or invalid Web remote control relay parameters."
            )
            val hash = q(raw, "hash") ?: throw IllegalArgumentException(
                "Missing or invalid Web remote control relay parameters."
            )
            val t = q(raw, "t")?.toLongOrNull() ?: throw IllegalArgumentException(
                "Missing or invalid Web remote control relay parameters."
            )
            return RemoteLink(
                deviceSid = sid,
                passHash = hash,
                timestamp = t,
                deviceMid = q(raw, "mid"),
                deviceName = q(raw, "name"),
                appVersion = q(raw, "app_version"),
            )
        }
    }
}

/** A desktop workspace as reported by `bootstrap-response`. */
data class Workspace(
    val workspacePath: String,
    val workspaceKey: String,
    val kind: String?,
    val raw: Map<String, Any?>,
)

/** A task/session summary as reported by `bootstrap-response`. */
data class TaskSummary(
    val taskId: String,
    val title: String,
    val displayStatus: String?,
    val workspacePath: String?,
    val workspaceLabel: String?,
    val model: String?,
    val provider: String?,
    val updatedAt: Long?,
    val unreadAt: Long?,
    val raw: Map<String, Any?>,
) {
    val unread: Boolean get() = unreadAt != null
}

data class BootstrapResult(
    val desktopAppVersion: String?,
    val activeWorkspaceKey: String?,
    val activeTaskId: String?,
    val workspaces: List<Workspace>,
    val tasks: List<TaskSummary>,
)

data class BridgeInfo(
    val bridgeSessionId: String,
    val bridgeGeneration: Int,
    val workspaceKey: String?,
    val workspacePath: String?,
    val initialTaskId: String?,
)

annotation class ZCodeExperimental

/**
 * ZCode hosted-relay transport.
 *
 * Wire facts established empirically against the live relay:
 *  - endpoint is `wss://zcode.z.ai/ws?mid=<deviceMid>`
 *  - auth is JSON: `auth_init` → `auth_challenge` → `auth_response` → `auth_ack`
 *  - `proof = base64url_nopad(HMAC_SHA256(key = hash, msg = "<nonce>|terminal|<sid>"))`
 *  - binary channel payloads travel base64-encoded inside `data`/`rpc-frame`
 *    JSON envelopes, **without** the 13-byte SocketProtocol header that the
 *    direct-socket path uses
 */
@ZCodeExperimental
class RelayTransport(
    private val link: RemoteLink,
    private val webSockets: WebSocketFactory,
    private val logger: (String) -> Unit = {},
    /**
     * Called when an established socket goes away without the client asking.
     *
     * The relay allows exactly one remote controller, so being dropped is a
     * normal event — the user opened the web page, or the desktop clicked
     * 停止. The transport reports it (with the close code, so the caller can
     * tell transient drops from terminal ones like 4004) and stops; whether
     * to retry is the caller's decision.
     */
    private val onDisconnected: (reason: String, closeCode: Int?) -> Unit = { _, _ -> },
) {
    companion object {
        const val RELAY_WS = "wss://zcode.z.ai/ws"
        private const val ROLE = "terminal"
        private const val HEARTBEAT_MS = 10_000L

        /**
         * `proof = base64url_nopad(HMAC_SHA256(key = passHash, msg = "<nonce>|<role>|<sid>"))`.
         *
         * Verified byte-for-byte against the web client's implementation and
         * accepted by the live relay.
         */
        fun calculateProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sig = mac.doFinal("$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
        }

        /**
         * Human-readable reason for a relay close code.
         *
         * The relay's own reason string is usually empty, so the code is the
         * only signal the user gets.
         */
        fun closeReason(code: Int, reason: String): String = when {
            reason.isNotBlank() -> reason
            code == 4004 -> "链接已失效，请在桌面端重新生成"
            code == 4009 -> "会话已过期"
            code == 4010 -> "会话不存在"
            code == 4011 -> "工作区已关闭"
            code == 4012 || code == 4013 -> "已被桌面端停止远程控制"
            code == 1000 -> "连接已关闭"
            else -> "连接已断开（code=$code）"
        }
    }

    private var socket: WebSocketConnection? = null
    private var closing = false
    private val control = HashMap<String, CompletableDeferred<Map<String, Any?>>>()
    private val controlMatchers = HashMap<String, (Map<String, Any?>) -> Boolean>()

    /** Raw channel payloads, in arrival order. */
    val channelPayloads = Channel<ByteArray>(Channel.UNLIMITED)

    var pairStatus: String? = null
        private set
    var terminalSid: String? = null
        private set
    var bridge: BridgeInfo? = null
        private set
    var lastError: String? = null
        private set

    private var outSeq = 0
    private var outMessageSeq = 0

    val webSocketUrl: String
        get() = buildString {
            append(RELAY_WS)
            link.deviceMid?.takeIf { it.isNotBlank() }?.let { append("?mid=").append(it) }
        }

    suspend fun connect(scope: kotlinx.coroutines.CoroutineScope) {
        val ready = CompletableDeferred<Unit>()
        val listener = object : WebSocketListener {
            override fun onOpen() {
                sendJson(
                    linkedMapOf(
                        "type" to "auth_init",
                        "role" to ROLE,
                        "device_sid" to link.deviceSid,
                        "meta" to linkedMapOf(
                            "platform" to "web",
                            "version" to (link.appVersion ?: "web"),
                            "name" to "mobile-browser",
                        ),
                        "client_ts" to System.currentTimeMillis(),
                    )
                )
            }

            override fun onTextMessage(text: String) {
                val msg = Json.asMap(Json.decode(text))
                scope.launch { handle(msg, ready) }
            }

            override fun onClosed(code: Int, reason: String) {
                logger("socket closed code=$code reason=$reason")
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException("relay closed: $code $reason"))
                } else if (!closing) {
                    onDisconnected(closeReason(code, reason), code)
                }
            }

            override fun onFailure(error: Throwable) {
                lastError = error.message
                if (!ready.isCompleted) {
                    ready.completeExceptionally(error)
                } else if (!closing) {
                    onDisconnected(error.message ?: "连接中断", null)
                }
            }
        }
        socket = webSockets.open(webSocketUrl, listener)
        withTimeout(20_000) { ready.await() }
        scope.launch { heartbeatLoop() }
    }

    private suspend fun handle(msg: Map<String, Any?>, ready: CompletableDeferred<Unit>) {
        when (Json.asString(msg["type"])) {
            "auth_challenge" -> {
                val nonce = Json.asString(msg["nonce"]) ?: return
                sendJson(
                    linkedMapOf(
                        "type" to "auth_response",
                        "device_sid" to link.deviceSid,
                        "proof" to calculateProof(link.passHash, nonce, ROLE, link.deviceSid),
                        "client_ts" to System.currentTimeMillis(),
                    )
                )
            }
            "auth_ack" -> {
                terminalSid = Json.asString(msg["terminal_sid"])
                pairStatus = Json.asString(msg["pair_status"])
                logger("authenticated terminal_sid=$terminalSid pair_status=$pairStatus")
                if (!ready.isCompleted) ready.complete(Unit)
            }
            "pair_status_ack" -> pairStatus = Json.asString(msg["pair_status"])
            "error" -> {
                val code = Json.asString(msg["code"])
                val m = Json.asString(msg["message"])
                lastError = "relay error $code ${m ?: ""}".trim()
                logger(lastError!!)
                if (!ready.isCompleted) {
                    ready.completeExceptionally(IllegalStateException(lastError))
                }
            }
            "data" -> handleData(Json.asMap(msg["payload"]))
        }
    }

    private suspend fun handleData(payload: Map<String, Any?>) {
        when (Json.asString(payload["zcode_type"])) {
            "rpc-frame" -> {
                ackFrame(payload)
                Json.asString(payload["dataBase64"])?.let { b64 ->
                    channelPayloads.send(Base64.getDecoder().decode(b64))
                }
            }
            "rpc-frame-ack" -> Unit
            else -> {
                val requestId = Json.asString(payload["requestId"])
                if (requestId != null) {
                    val match = controlMatchers[requestId]
                    if (match == null || match(payload)) {
                        controlMatchers.remove(requestId)
                        control.remove(requestId)?.complete(payload)
                    }
                }
            }
        }
    }

    /** Release the peer's buffer for an inbound frame. */
    private fun ackFrame(payload: Map<String, Any?>) {
        val messageSeq = Json.asLong(payload["messageSeq"]) ?: return
        sendJson(
            linkedMapOf(
                "type" to "data",
                "payload" to linkedMapOf(
                    "zcode_type" to "rpc-frame-ack",
                    "bridgeSessionId" to payload["bridgeSessionId"],
                    "bridgeGeneration" to payload["bridgeGeneration"],
                    "ackMessageSeq" to messageSeq,
                ),
                "client_ts" to System.currentTimeMillis(),
            )
        )
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            kotlinx.coroutines.delay(HEARTBEAT_MS)
            if (socket?.isOpen != true) break
            sendJson(
                linkedMapOf(
                    "type" to "pair_status_query",
                    "device_sid" to link.deviceSid,
                    "client_ts" to System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun controlRequest(
        payload: Map<String, Any?>,
        timeoutMs: Long = 20_000,
        match: (Map<String, Any?>) -> Boolean,
    ): Map<String, Any?> {
        val requestId = Json.asString(payload["requestId"])!!
        val deferred = CompletableDeferred<Map<String, Any?>>()
        control[requestId] = deferred
        controlMatchers[requestId] = match
        sendData(payload)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            control.remove(requestId); controlMatchers.remove(requestId)
            throw IllegalStateException("timeout waiting for response to ${payload["zcode_type"]}")
        }
    }

    suspend fun bootstrap(): BootstrapResult {
        val requestId = "bootstrap-${UUID.randomUUID()}"
        val res = controlRequest(
            linkedMapOf("zcode_type" to "bootstrap-request", "requestId" to requestId),
        ) { it["zcode_type"] == "bootstrap-response" && it["requestId"] == requestId }

        val result = Json.asMap(res["result"])
        val view = Json.asMap(result["initialViewState"])
        val workspaces = Json.asList(result["workspaces"]).map { w ->
            val m = Json.asMap(w)
            Workspace(
                workspacePath = Json.asString(m["workspacePath"]) ?: "",
                workspaceKey = Json.asString(m["workspaceKey"]) ?: "",
                kind = Json.asString(m["kind"]),
                raw = m,
            )
        }
        // The desktop can report the same taskId more than once (observed:
        // a stale completed entry alongside the live running one). Collapse by
        // id so clients get a unique-key-safe list without each of them
        // re-implementing the rule.
        val tasks = parseTaskSummaries(result["tasks"])
        return BootstrapResult(
            desktopAppVersion = Json.asString(result["desktopAppVersion"]),
            activeWorkspaceKey = Json.asString(view["activeWorkspaceKey"]),
            activeTaskId = Json.asString(view["activeTaskId"]),
            workspaces = workspaces,
            tasks = tasks,
        )
    }

    /** Open the RPC bridge for one workspace; required before channel traffic. */
    suspend fun openBridge(workspaceKey: String, taskId: String? = null): BridgeInfo {
        val requestId = "workspace-bridge-${UUID.randomUUID()}"
        val bridgeSessionId = "bridge-${UUID.randomUUID()}"
        val ready = controlRequest(
            linkedMapOf(
                "zcode_type" to "workspace-bridge-open",
                "requestId" to requestId,
                "bridgeSessionId" to bridgeSessionId,
                "bridgeGeneration" to 1,
                "workspaceKey" to workspaceKey,
                *(taskId?.let { arrayOf("taskId" to it) } ?: emptyArray()),
            ),
        ) { it["zcode_type"] == "workspace-bridge-ready" && it["bridgeSessionId"] == bridgeSessionId }

        val b = Json.asMap(ready["bridge"])
        val info = BridgeInfo(
            bridgeSessionId = Json.asString(b["bridgeSessionId"]) ?: bridgeSessionId,
            bridgeGeneration = (Json.asLong(b["bridgeGeneration"]) ?: 1L).toInt(),
            workspaceKey = Json.asString(b["workspaceKey"]),
            workspacePath = Json.asString(b["workspacePath"]),
            initialTaskId = Json.asString(b["initialTaskId"]),
        )
        bridge = info
        return info
    }

    /** Wrap a bare channel payload into a relay `rpc-frame` envelope. */
    fun sendChannelPayload(bytes: ByteArray) {
        val b = bridge ?: throw IllegalStateException("no bridge open")
        outSeq++; outMessageSeq++
        sendData(
            linkedMapOf(
                "zcode_type" to "rpc-frame",
                "bridgeSessionId" to b.bridgeSessionId,
                "bridgeGeneration" to b.bridgeGeneration,
                "seq" to outSeq,
                "messageSeq" to outMessageSeq,
                "messageBytes" to bytes.size,
                "checksum" to linkedMapOf("algorithm" to "crc32", "value" to Crc32.hex(bytes)),
                "fragmentIndex" to 0,
                "fragmentCount" to 1,
                "dataBase64" to Base64.getEncoder().encodeToString(bytes),
            )
        )
    }

    private fun sendData(payload: Map<String, Any?>) {
        sendJson(
            linkedMapOf(
                "type" to "data",
                "payload" to payload,
                "client_ts" to System.currentTimeMillis(),
            )
        )
    }

    private fun sendJson(obj: Any?) {
        socket?.send(Json.encode(obj))
    }

    fun close() {
        closing = true
        runCatching { socket?.close(1000, "client closing") }
        socket = null
    }
}
