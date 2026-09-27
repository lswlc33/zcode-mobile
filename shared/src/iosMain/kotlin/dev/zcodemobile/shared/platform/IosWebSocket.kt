package dev.zcodemobile.shared.platform

import dev.zcodemobile.protocol.WebSocketConnection
import dev.zcodemobile.protocol.WebSocketFactory
import dev.zcodemobile.protocol.WebSocketListener
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURLSessionWebSocketTask
import kotlin.concurrent.Volatile

/**
 * iOS WebSocket transport on NSURLSession, behind the same [WebSocketFactory]
 * the JVM (java.net.http) and Android (OkHttp) shells use.
 *
 * The protocol layer drives everything off listener callbacks, so this adapter
 * keeps a self-perpetuating `receiveMessage` loop: each completed read either
 * forwards a text frame or reports close/failure. `onOpen` fires after the
 * first successful ping — the same "connection truly up" signal the relay
 * expects before `auth_init`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosWebSocketFactory : WebSocketFactory {

    override fun open(url: String, listener: WebSocketListener): WebSocketConnection {
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration,
        )
        val task = session.webSocketTaskWithURL(NSURL.URLWithString(url)!!)
        val conn = IosWebSocketConnection(task, listener)
        task.resume()
        conn.start()
        return conn
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosWebSocketConnection(
    private val task: NSURLSessionWebSocketTask,
    private val listener: WebSocketListener,
) : WebSocketConnection {

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = !closed

    /** Kicks off open-detection and the receive loop. */
    fun start() {
        // A successful pong proves the handshake completed.
        task.sendPingWithPongReceiveHandler { error ->
            if (error != null) {
                fail("websocket ping failed: ${error.localizedDescription}")
            } else if (!closed) {
                listener.onOpen()
            }
        }
        receiveNext()
    }

    private fun receiveNext() {
        if (closed) return
        task.receiveMessageWithCompletionHandler { message, error ->
            when {
                error != null -> fail(error.localizedDescription ?: "socket error")
                message == null -> fail("socket closed by peer")
                else -> {
                    val text = message.string
                    if (text != null) listener.onTextMessage(text)
                    if (!closed) receiveNext()
                }
            }
        }
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        listener.onFailure(RuntimeException(message))
    }

    override fun send(text: String) {
        if (closed) return
        val msg = NSURLSessionWebSocketMessage(text)
        task.sendMessage(msg) { error ->
            if (error != null) fail(error.localizedDescription ?: "send failed")
        }
    }

    override fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        val reasonData = (NSString.create(string = reason) as Any) as? NSData
        task.cancelWithCloseCode(code.toLong(), reasonData)
        listener.onClosed(code, reason)
    }
}
