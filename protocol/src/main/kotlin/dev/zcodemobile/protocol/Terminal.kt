package dev.zcodemobile.protocol

/**
 * Remote PTY over the `terminal` channel (verified live 2026-09-26,
 * LIVE-WEB-VERIFICATION §8.3): the desktop's right-panel terminal tab is a
 * real shell — create hands back an id, `write` carries keystrokes verbatim
 * (including `\r`), and output/exit arrive on the two dynamic events.
 */
@ZCodeExperimental
class TerminalService(private val channel: ChannelClient) {

    companion object {
        const val CHANNEL = "terminal"
        const val EVENT_DATA = "onDynamicData"
        const val EVENT_EXIT = "onDynamicExit"
    }

    /**
     * Create a shell in [cwd]. Output/exit listeners must be attached before
     * the first write lands, so the usual call order is listen → create.
     */
    suspend fun create(cols: Int, rows: Int, cwd: String): String {
        val res = Json.asMap(
            channel.call(CHANNEL, "create", mapOf("cols" to cols, "rows" to rows, "cwd" to cwd))
        )
        return Json.asString(res["id"]) ?: error("terminal.create returned no id")
    }

    /** Send keystrokes; callers include the trailing `\r` for Enter. */
    suspend fun write(id: String, data: String) {
        channel.call(CHANNEL, "write", mapOf("id" to id, "data" to data))
    }

    suspend fun resize(id: String, cols: Int, rows: Int) {
        channel.call(CHANNEL, "resize", mapOf("id" to id, "cols" to cols, "rows" to rows))
    }

    /**
     * `onDynamicData(id)` is a dynamic event factory whose argument is the
     * **bare terminal id string** (`terminalService.ts:427`), not an object —
     * passing a map silently registers nothing and no data ever arrives.
     */
    fun listenData(id: String, onChunk: (String) -> Unit): Int =
        channel.listen(CHANNEL, EVENT_DATA, id) { chunk ->
            (chunk as? String)?.let(onChunk) ?: onChunk(Json.encode(chunk))
        }

    fun listenExit(id: String, onExit: (code: Int?) -> Unit): Int =
        channel.listen(CHANNEL, EVENT_EXIT, id) { payload ->
            onExit((payload as? Number)?.toInt())
        }

    fun disposeEvent(listenerId: Int) = channel.disposeEvent(listenerId)
}
