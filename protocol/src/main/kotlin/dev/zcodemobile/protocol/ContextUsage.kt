package dev.zcodemobile.protocol

/** One category of context consumption. */
data class ContextSlice(
    val source: String,
    val chars: Long,
) {
    val label: String get() = labelOf(source)

    companion object {
        /**
         * `zcodeContextUsageBreakdownSourceSchema` has seven values; they are
         * collapsed to the six rows a reader thinks in, with the leftovers
         * folded into 其他.
         */
        fun labelOf(source: String): String = when (source) {
            "messages" -> "消息"
            "system_tool_schemas" -> "系统工具"
            "mcp_tool_schemas" -> "MCP 工具"
            "system_prompt" -> "系统提示词"
            "skills" -> "技能"
            "tool_prompt" -> "工具提示词"
            "meta_user_context" -> "其他"
            else -> source
        }
    }
}

/**
 * Context-window consumption, with the per-category split and prompt-cache
 * hit rate the desktop surfaces behind its context pill.
 *
 * `breakdown[].chars` is measured in **characters**, not tokens, so shares are
 * computed from chars while the headline figure comes from `usedTokens`.
 */
data class ContextUsage(
    val usedTokens: Long? = null,
    val maxTokens: Long? = null,
    val slices: List<ContextSlice> = emptyList(),
    val cacheHitRate: Float? = null,
    val cacheReadTokens: Long? = null,
) {
    /** 0f..1f, or null when the host reported no window. */
    val fraction: Float?
        get() {
            val used = usedTokens ?: return null
            val max = maxTokens ?: return null
            if (max <= 0) return null
            return (used.toFloat() / max).coerceIn(0f, 1f)
        }

    val hasDetail: Boolean
        get() = slices.isNotEmpty() || cacheHitRate != null

    /** Rows to show, largest first, with the tail folded into 其他. */
    fun detailRows(): List<Pair<String, Float>> {
        if (slices.isEmpty()) return emptyList()
        val total = slices.sumOf { it.chars }.takeIf { it > 0 } ?: return emptyList()

        val byLabel = LinkedHashMap<String, Long>()
        for (s in slices.sortedByDescending { it.chars }) {
            byLabel[s.label] = (byLabel[s.label] ?: 0L) + s.chars
        }

        val rows = byLabel.entries
            .filter { it.key != "其他" }
            .map { it.key to (it.value.toFloat() / total) }
            .toMutableList()

        val other = byLabel["其他"] ?: 0L
        if (other > 0) rows += "其他" to (other.toFloat() / total)

        return rows.sortedByDescending { it.second }
    }
}

/** Parse `snapshot.usage` (or a `state.updated.patch.usage`). */
fun parseContextUsage(usageOrSnapshot: Map<String, Any?>): ContextUsage {
    // Accept either the `usage` object itself or a snapshot/patch containing it,
    // so callers do not have to remember which shape they hold.
    val usage = Json.asMap(usageOrSnapshot["usage"]).ifEmpty { usageOrSnapshot }
    val window = Json.asMap(usage["contextWindow"])
    if (window.isEmpty() && usage["usedTokens"] == null) return ContextUsage()

    val cache = Json.asMap(window["cache"])
    val slices = Json.asList(window["breakdown"]).mapNotNull { item ->
        val m = Json.asMap(item)
        val source = Json.asString(m["source"]) ?: return@mapNotNull null
        ContextSlice(source, Json.asLong(m["chars"]) ?: 0L)
    }

    return ContextUsage(
        usedTokens = Json.asLong(window["usedTokens"]),
        maxTokens = Json.asLong(window["maxTokens"]),
        slices = slices,
        cacheHitRate = (cache["hitRate"] as? Number)?.toFloat(),
        cacheReadTokens = Json.asLong(cache["cacheReadTokens"]),
    )
}
