package dev.zcodemobile.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Channel RPC client over a [RelayTransport].
 *
 * Framing established empirically: the relay carries the *bare* channel payload
 * in `rpc-frame.dataBase64` — `serialize(header) + serialize(body)` with no
 * 13-byte SocketProtocol prefix (the relay frames messages itself via
 * `messageSeq` / `messageBytes` / `fragmentIndex`).
 *
 * Argument convention: the host dispatches with `target.apply(handler, args)`
 * (see `ProxyChannel.fromService`), so every call travels as a positional
 * **array** — a one-parameter method is still a one-element list.
 */
@ZCodeExperimental
class ChannelClient(
    private val transport: RelayTransport,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private var lastRequestId = 0
    private val pending = HashMap<Int, CompletableDeferred<ChannelMessage>>()
    private val eventHandlers = HashMap<Int, (Any?) -> Unit>()
    private val initialized = CompletableDeferred<Unit>()
    private var pump: Job? = null

    fun start() {
        pump = scope.launch {
            for (payload in transport.channelPayloads) dispatch(payload)
        }
    }

    /**
     * Stop consuming the shared transport payload stream.
     *
     * [transport.channelPayloads] is a plain channel: while two consumers are
     * attached, the runtime hands each payload to **one** of them at random.
     * Re-opening a workspace therefore must close the previous client first,
     * or both clients lose half of their messages to each other.
     */
    fun close() {
        pump?.cancel()
        pump = null
        eventHandlers.clear()
        pending.clear()
    }

    suspend fun awaitInitialized(timeoutMs: Long = 15_000) {
        try {
            withTimeout(timeoutMs) { initialized.await() }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("channel did not initialize within ${timeoutMs}ms")
        }
    }

    private fun dispatch(bytes: ByteArray) {
        val header: Any?
        val body: Any?
        try {
            val pair = Vql.decodeMessage(bytes)
            header = pair.first
            body = pair.second
        } catch (e: Exception) {
            logger("channel decode failed: ${e.message} (${bytes.size}B)")
            return
        }
        val h = header as? List<*> ?: run {
            logger("channel header not a list: ${Json.encode(header)}")
            return
        }
        val type = (h.getOrNull(0) as? Number)?.toInt() ?: return

        when (type) {
            ChannelProtocol.RESPONSE_INITIALIZE -> {
                logger("channel initialized")
                initialized.complete(Unit)
            }
            ChannelProtocol.RESPONSE_EVENT_FIRE -> {
                val id = (h.getOrNull(1) as? Number)?.toInt() ?: return
                eventHandlers[id]?.invoke(body)
            }
            ChannelProtocol.RESPONSE_PROMISE_SUCCESS,
            ChannelProtocol.RESPONSE_PROMISE_ERROR,
            ChannelProtocol.RESPONSE_PROMISE_ERROR_OBJ -> {
                val id = (h.getOrNull(1) as? Number)?.toInt() ?: return
                pending.remove(id)?.complete(ChannelMessage(type, id, body))
            }
        }
    }

    /** Invoke `channel.method(*args)`. Always array-wrapped on the wire. */
    suspend fun call(
        channel: String,
        method: String,
        vararg args: Any?,
        timeoutMs: Long = 30_000,
    ): Any? {
        val id = lastRequestId++
        val deferred = CompletableDeferred<ChannelMessage>()
        pending[id] = deferred
        logger("→ ${channel}.${method} id=$id argc=${args.size}")
        transport.sendChannelPayload(
            Vql.encodeMessage(
                listOf(ChannelProtocol.REQUEST_PROMISE, id, channel, method),
                args.toList(),
            )
        )
        val msg = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw IllegalStateException("timeout: $channel.$method")
        }
        return when (msg.type) {
            ChannelProtocol.RESPONSE_PROMISE_SUCCESS -> msg.body
            ChannelProtocol.RESPONSE_PROMISE_ERROR -> {
                val err = Json.asMap(msg.body)
                throw IllegalStateException(
                    "${Json.asString(err["name"]) ?: "Error"}: " +
                        "${Json.asString(err["message"]) ?: "rpc error"}"
                )
            }
            else -> throw IllegalStateException("RPC error object: ${Json.encode(msg.body)}")
        }
    }

    /**
     * Subscribe to a service event, or invoke an `onDynamicXxx` factory that
     * returns an event. Unlike [call], this is **not** array-wrapped: the host
     * routes it to `target.call(handler, arg)` with a single argument.
     */
    fun listen(channel: String, event: String, arg: Any?, onEvent: (Any?) -> Unit): Int {
        val id = lastRequestId++
        eventHandlers[id] = onEvent
        transport.sendChannelPayload(
            Vql.encodeMessage(
                listOf(ChannelProtocol.REQUEST_EVENT_LISTEN, id, channel, event),
                arg,
            )
        )
        return id
    }

    fun disposeEvent(id: Int) {
        eventHandlers.remove(id)
        transport.sendChannelPayload(
            Vql.encodeMessage(listOf(ChannelProtocol.REQUEST_EVENT_DISPOSE, id), null)
        )
    }
}

data class ChannelMessage(val type: Int, val id: Int, val body: Any?)
