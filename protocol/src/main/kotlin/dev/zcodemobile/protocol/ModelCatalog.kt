package dev.zcodemobile.protocol

/** One selectable model on the paired desktop. */
data class ModelOption(
    val providerId: String,
    val providerName: String?,
    val modelId: String,
    val modelName: String?,
    val thoughtLevels: List<String>,
    /** From `config.properties.contextWindow`, when the provider declares one. */
    val contextWindow: Long? = null,
    /** From `config.properties.inputFormat.supportsImage` — the 视觉 badge. */
    val supportsVision: Boolean = false,
    val enabled: Boolean = true,
) {
    /** Short label for a chip: `deepseek-v4.1-flash`. */
    val shortName: String get() = modelName ?: modelId.substringAfterLast(':')
}

data class ModelCatalog(
    val models: List<ModelOption>,
    val currentProviderId: String?,
    val currentModelId: String?,
    val currentThought: String?,
) {
    val isEmpty: Boolean get() = models.isEmpty()
}

/**
 * Reads the desktop's model catalog from the `model-selection` service.
 *
 * ⚠️ The raw `getView` payload embeds provider **API keys** in plaintext under
 * `providers[].config.access.apiKey`. Only identifiers, display names and
 * thought levels are extracted here — the response is never stored, logged, or
 * echoed into the UI, so a credential cannot leak through a screenshot, a
 * crash report, or a debug dump.
 */
@ZCodeExperimental
class ModelCatalogService(private val channel: ChannelClient) {

    suspend fun load(currentProviderId: String?, currentModelId: String?): ModelCatalog {
        val view = Json.asMap(channel.call(CHANNEL, METHOD))
        return parse(view, currentProviderId, currentModelId)
    }

    companion object {
        const val CHANNEL = "model-selection"
        private const val METHOD = "getView"

        /**
         * Project the raw `getView` payload into a [ModelCatalog].
         *
         * Pure, and therefore testable without a live channel: the projection is
         * the only thing standing between a provider credential and the UI, so
         * it is worth testing directly rather than through a fixture copy.
         */
        fun parse(
            view: Map<String, Any?>,
            currentProviderId: String? = null,
            currentModelId: String? = null,
        ): ModelCatalog {
            val providers = Json.asList(view["providers"])
            val models = ArrayList<ModelOption>()

            for (p in providers) {
                val pm = Json.asMap(p)
                val providerId = Json.asString(pm["providerId"]) ?: continue
                val providerName = Json.asString(pm["providerName"])
                for (m in Json.asList(pm["models"])) {
                    val mm = Json.asMap(m)
                    val modelId = Json.asString(mm["modelId"]) ?: continue
                    val config = Json.asMap(mm["config"])
                    val properties = Json.asMap(config["properties"])
                    models += ModelOption(
                        providerId = providerId,
                        providerName = providerName,
                        modelId = modelId,
                        modelName = Json.asString(mm["modelName"])
                            ?: Json.asString(mm["name"]),
                        thoughtLevels = readThoughtLevels(mm, config),
                        contextWindow = Json.asLong(properties["contextWindow"]),
                        supportsVision = Json.asBool(
                            Json.asMap(properties["inputFormat"])["supportsImage"]
                        ) ?: false,
                        enabled = Json.asBool(config["enabled"]) ?: true,
                    )
                }
            }

            // Grouped by provider, matching how the desktop presents models:
            // provider is the primary axis, model the secondary one.
            models.sortBy { it.providerName ?: it.providerId }

            return ModelCatalog(
                models = models,
                currentProviderId = currentProviderId,
                currentModelId = currentModelId,
                currentThought = null,
            )
        }

        /**
         * Reasoning levels for one model.
         *
         * They live at `models[].config.optionSpecs.reasoningLevel.values`, not
         * at the model's top level — reading the wrong path silently yields an
         * empty list, which surfaces as a thought-level picker that always says
         * the model supports none. The flat `thoughtLevels` key is kept as a
         * fallback because it costs nothing and older payloads did carry it.
         */
        private fun readThoughtLevels(
            model: Map<String, Any?>,
            config: Map<String, Any?>,
        ): List<String> {
            val fromSpecs = Json.asList(
                Json.asMap(Json.asMap(config["optionSpecs"])["reasoningLevel"])["values"]
            ).mapNotNull { Json.asString(it) }
            if (fromSpecs.isNotEmpty()) return fromSpecs

            return Json.asList(model["thoughtLevels"]).mapNotNull { Json.asString(it) }
        }
    }
}
