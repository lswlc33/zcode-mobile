package dev.zcodemobile.app.net

import dev.zcodemobile.protocol.WebSocketConnection
import dev.zcodemobile.protocol.WebSocketFactory
import dev.zcodemobile.protocol.WebSocketListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener as OkListener
import java.util.concurrent.TimeUnit

/**
 * Android-side WebSocket transport.
 *
 * Unlike the JVM adapter, OkHttp hands back the [WebSocket] synchronously from
 * `newWebSocket`, so the connection wrapper always has a live socket by the
 * time `onOpen` fires.
 */
class OkHttpWebSocketFactory(
    private val client: OkHttpClient = defaultClient(),
) : WebSocketFactory {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived; relay heartbeats keep it warm
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun open(url: String, listener: WebSocketListener): WebSocketConnection {
        val request = Request.Builder().url(url).build()
        val conn = OkHttpWebSocketConnection()
        val socket = client.newWebSocket(request, object : OkListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                conn.attach(webSocket)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onTextMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                conn.markClosed()
                listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conn.markClosed()
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conn.markClosed()
                listener.onFailure(t)
            }
        })
        // Attach immediately; onOpen may already have fired for a fast connect.
        conn.attach(socket)
        return conn
    }
}

private class OkHttpWebSocketConnection : WebSocketConnection {
    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    override val isOpen: Boolean get() = socket != null && !closed

    fun attach(webSocket: WebSocket) {
        socket = webSocket
    }

    fun markClosed() {
        closed = true
    }

    override fun send(text: String) {
        if (closed) return
        socket?.send(text)
    }

    override fun close(code: Int, reason: String) {
        closed = true
        runCatching { socket?.close(code, reason) }
    }
}
