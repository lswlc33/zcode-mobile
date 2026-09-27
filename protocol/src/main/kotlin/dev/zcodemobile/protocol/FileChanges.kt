package dev.zcodemobile.protocol

/**
 * A file modification implied by a write-capable tool call.
 *
 * Derived locally from the tool's own input rather than from
 * `conversationFileChangesV4`: that query takes a CAS `baseRevision` which the
 * client cannot reconstruct for a row that arrived as a delta (every base we
 * tried answered `proto.staleTarget` / `proto.staleRevision`). The tool input
 * is already in hand and cannot go stale, so the card renders instantly and
 * offline.
 */
data class FileChange(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val toolName: String,
) {
    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')

    val isCreation: Boolean get() = deletions == 0 && additions > 0
}

/** Tools whose input describes a file write. */
private val WRITE_TOOLS = setOf(
    "Edit", "Write", "MultiEdit", "NotebookEdit", "StrReplace", "CreateFile",
)

/**
 * Extract the file change a tool call produced, or null when the tool is not a
 * write (Bash/Read/Grep, or an Edit that has not been given its input yet).
 */
fun fileChangeOf(row: Row): FileChange? {
    if (row.kind != "toolCall") return null
    val tool = row.toolName ?: return null
    if (tool !in WRITE_TOOLS) return null

    val input = Json.asMap(row.raw["input"])
    val path = Json.asString(input["file_path"])
        ?: Json.asString(input["path"])
        ?: Json.asString(input["notebook_path"])
        ?: return null

    var additions = 0
    var deletions = 0

    // Write / CreateFile: the whole content is new.
    Json.asString(input["content"])?.let { additions += lineCount(it) }
    Json.asString(input["new_string"])?.let { additions += lineCount(it) }
    Json.asString(input["old_string"])?.let { deletions += lineCount(it) }

    // MultiEdit: a list of the same pair.
    Json.asList(input["edits"]).forEach { e ->
        val m = Json.asMap(e)
        Json.asString(m["new_string"])?.let { additions += lineCount(it) }
        Json.asString(m["old_string"])?.let { deletions += lineCount(it) }
    }

    if (additions == 0 && deletions == 0) return null
    return FileChange(path, additions, deletions, tool)
}

/**
 * Line count the way diff tools report it: a trailing newline does not add a
 * line, and an empty replacement counts as zero.
 */
internal fun lineCount(text: String): Int {
    if (text.isEmpty()) return 0
    val normalized = text.trimEnd('\n')
    if (normalized.isEmpty()) return 0
    return normalized.count { it == '\n' } + 1
}
