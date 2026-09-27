package dev.zcodemobile.protocol

/**
 * One selectable value inside a workspace config option.
 *
 * `modelThoughtLevels` is present only for model options; an **empty array**
 * means the catalog is known to offer no reasoning levels, while an **absent**
 * key means the payload predates the capability. Those are different states and
 * are kept distinct here.
 */
data class ConfigSelectValue(
    val value: String,
    val name: String,
    val description: String?,
    val origin: String?,
    val modelProviderId: String?,
    val modelProviderName: String?,
    val modelThoughtLevels: List<String>?,
    val modelDefaultThoughtLevel: String?,
)

/**
 * A workspace-level setting (`id` is `model`, `mode`, `thought_level`, …).
 *
 * `currentValue` is the **workspace default**; the session's live value lives in
 * the conversation snapshot's `config`.
 */
data class ConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val type: String,
    val currentValue: Any?,
    val options: List<ConfigSelectValue>,
) {
    val isBoolean: Boolean get() = type == "boolean"

    val currentString: String? get() = currentValue as? String
    val currentBool: Boolean? get() = currentValue as? Boolean
}

/** A slash command the workspace exposes. */
data class SlashCommand(
    val name: String,
    val description: String,
    val inputHint: String?,
    val source: String?,
)

/**
 * Conflated latest state of the `workspace-config/<workspaceId>` topic.
 *
 * Whole-state replacement: the host deliberately ships no field-level deltas
 * for a payload this small.
 */
data class WorkspaceConfigState(
    val workspaceId: String? = null,
    val logEpoch: String? = null,
    val configOptions: List<ConfigOption> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList(),
) {
    fun option(id: String): ConfigOption? = configOptions.firstOrNull { it.id == id }

    /** Workspace default for a select option, if the catalog published it. */
    fun currentValue(id: String): String? = option(id)?.currentString
}

object WorkspaceConfig {

    const val TOPIC_PREFIX = "workspace-config/"

    fun topic(workspaceId: String): String = TOPIC_PREFIX + workspaceId

    fun parseTopic(topic: String): String? =
        topic.removePrefix(TOPIC_PREFIX)
            .takeIf { topic.startsWith(TOPIC_PREFIX) && it.isNotEmpty() }

    fun applySnapshot(snapshot: Map<String, Any?>): WorkspaceConfigState {
        val config = Json.asMap(snapshot["config"])
        return WorkspaceConfigState(
            workspaceId = Json.asString(snapshot["workspaceId"]),
            logEpoch = Json.asString(snapshot["logEpoch"]),
            configOptions = Json.asList(config["configOptions"]).map { toOption(Json.asMap(it)) },
            slashCommands = Json.asList(config["slashCommands"]).map { toCommand(Json.asMap(it)) },
        )
    }

    /** `config.updated` replaces the whole directory; unknown ops are reported. */
    fun applyDeltas(
        current: WorkspaceConfigState,
        deltas: List<Any?>,
        onUnknownOp: ((String) -> Unit)? = null,
    ): WorkspaceConfigState {
        var state = current
        for (d in deltas) {
            val op = Json.asMap(d)
            when (val name = Json.asString(op["op"])) {
                "config.updated" -> {
                    val config = Json.asMap(op["config"])
                    state = state.copy(
                        configOptions = Json.asList(config["configOptions"])
                            .map { toOption(Json.asMap(it)) },
                        slashCommands = Json.asList(config["slashCommands"])
                            .map { toCommand(Json.asMap(it)) },
                    )
                }
                else -> if (name != null) onUnknownOp?.invoke(name)
            }
        }
        return state
    }

    fun toOption(m: Map<String, Any?>): ConfigOption = ConfigOption(
        id = Json.asString(m["id"]) ?: "",
        name = Json.asString(m["name"]) ?: "",
        description = Json.asString(m["description"]),
        category = Json.asString(m["category"]),
        type = Json.asString(m["type"]) ?: "select",
        currentValue = m["currentValue"],
        options = Json.asList(m["options"]).map { o ->
            val om = Json.asMap(o)
            ConfigSelectValue(
                value = Json.asString(om["value"]) ?: "",
                name = Json.asString(om["name"]) ?: "",
                description = Json.asString(om["description"]),
                origin = Json.asString(om["origin"]),
                modelProviderId = Json.asString(om["modelProviderId"]),
                modelProviderName = Json.asString(om["modelProviderName"]),
                // Absent vs empty are different: absent = capability unknown.
                modelThoughtLevels = if (om.containsKey("modelThoughtLevels")) {
                    Json.asList(om["modelThoughtLevels"]).mapNotNull { Json.asString(it) }
                } else {
                    null
                },
                modelDefaultThoughtLevel = Json.asString(om["modelDefaultThoughtLevel"]),
            )
        },
    )

    fun toCommand(m: Map<String, Any?>): SlashCommand = SlashCommand(
        name = Json.asString(m["name"]) ?: "",
        description = Json.asString(m["description"]) ?: "",
        inputHint = Json.asString(m["inputHint"]),
        source = Json.asString(m["source"]),
    )
}
