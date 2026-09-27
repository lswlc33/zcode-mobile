package dev.zcodemobile.protocol

/**
 * Provider/model management over the `provider-settings` channel — all 14
 * methods verified live against the real relay with a throwaway provider
 * (WS-API §7.11.1).
 *
 * ⚠️ `getView`'s raw payload embeds plaintext API keys under
 * `providers[].config.access.apiKey`. This projection extracts identifiers,
 * names and enablement only — never the credentials — so nothing sensitive can
 * reach the UI, a log, or a crash dump. Keys the user types in flow one way:
 * into `savePersonalProviderOverlay`, never back out.
 *
 * ⚠️ The overlay config schema is `.strict()` and takes ONLY the config keys
 * (`access`, `api`, …) — `providerName` lives in the rules layer and sending
 * it fails with `ZodError: Unrecognized key "providerName"`.
 */
@ZCodeExperimental
class ProviderSettingsService(private val channel: ChannelClient) {

    companion object {
        const val CHANNEL = "provider-settings"

        /** `testModelConnectivity` actually probes the provider; it can take >25s. */
        const val TEST_TIMEOUT_MS = 60_000L
    }

    suspend fun getView(): ProviderSettingsView =
        parseView(channel.call(CHANNEL, "getView"))

    suspend fun refresh(): ProviderSettingsView =
        parseView(channel.call(CHANNEL, "refresh", "settings-manual"))

    suspend fun createPersonalProvider(name: String?): ProviderSettingsView {
        val arg: Any = if (name.isNullOrBlank()) {
            // Verified: the empty call also works and mints `new-provider-N`.
            emptyList<Any>()
        } else {
            listOf(mapOf("providerName" to name))
        }
        return parseView(channel.call(CHANNEL, "createPersonalProvider", arg))
    }

    /** Strict config: access + api only, no rule-layer keys. */
    suspend fun savePersonalProviderOverlay(
        providerId: String,
        apiKey: String,
        baseUrl: String,
    ): ProviderSettingsView = parseView(
        channel.call(
            CHANNEL, "savePersonalProviderOverlay",
            listOf(
                providerId,
                mapOf(
                    "access" to mapOf("type" to "api-key", "apiKey" to apiKey),
                    "api" to mapOf("type" to "openai-chat-completions", "baseUrl" to baseUrl),
                ),
            ),
        )
    )

    suspend fun deletePersonalProvider(providerId: String): ProviderSettingsView =
        parseView(channel.call(CHANNEL, "deletePersonalProvider", listOf(providerId)))

    /** `useRecommendedConfig: true` lets the host fill the recommended schema. */
    suspend fun addPersonalModel(
        providerId: String,
        modelId: String,
    ): ProviderSettingsView = parseView(
        channel.call(
            CHANNEL, "addPersonalModel",
            listOf(providerId, modelId, mapOf("enabled" to true), true),
        )
    )

    suspend fun deletePersonalModel(providerId: String, modelId: String): ProviderSettingsView =
        parseView(channel.call(CHANNEL, "deletePersonalModel", listOf(providerId, modelId)))

    suspend fun setPersonalModelEnabled(
        providerId: String,
        modelId: String,
        enabled: Boolean,
    ): ProviderSettingsView = parseView(
        channel.call(CHANNEL, "setPersonalModelEnabled", listOf(providerId, modelId, enabled))
    )

    suspend fun testModelConnectivity(
        workspacePath: String,
        providerId: String,
        modelId: String,
    ): ProviderTestResult {
        val res = Json.asMap(
            channel.call(
                CHANNEL, "testModelConnectivity",
                mapOf(
                    "workspacePath" to workspacePath,
                    "providerId" to providerId,
                    "modelId" to modelId,
                ),
                timeoutMs = TEST_TIMEOUT_MS,
            )
        )
        val error = Json.asMap(res["error"])
        return ProviderTestResult(
            success = res["success"] == true,
            errorCode = Json.asString(error["code"]),
            errorMessage = Json.asString(error["message"]),
        )
    }
}

data class ProviderModel(
    val modelId: String,
    val enabled: Boolean,
    val supportsVision: Boolean,
    val contextWindow: Long?,
)

data class ProviderEntry(
    val providerId: String,
    val name: String,
    val models: List<ProviderModel>,
)

data class ProviderSettingsView(
    val revision: Long,
    /** Registry-wide revision: every mutation +1; use as the CAS base. */
    val providers: List<ProviderEntry>,
) {
    val isEmpty: Boolean get() = providers.isEmpty()

    fun byId(providerId: String): ProviderEntry? = providers.firstOrNull { it.providerId == providerId }
}

data class ProviderTestResult(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
) {
    /** Ready-made display line; the codes were observed live. */
    val message: String
        get() = when {
            success -> "连接成功"
            errorCode == "model-unavailable" -> "该模型当前不可测试（未注册或已禁用）"
            else -> errorMessage ?: errorCode ?: "连接失败"
        }
}

/** Whitelist projection of the raw view. Deliberately drops `config.access`. */
fun parseView(v: Any?): ProviderSettingsView {
    val m = Json.asMap(v)
    val providers = Json.asList(m["providers"]).mapNotNull { p ->
        val pm = Json.asMap(p)
        val id = Json.asString(pm["providerId"]) ?: return@mapNotNull null
        ProviderEntry(
            providerId = id,
            name = Json.asString(pm["providerName"]) ?: id,
            models = Json.asList(pm["models"]).mapNotNull { mm ->
                val model = Json.asMap(mm)
                val modelId = Json.asString(model["modelId"]) ?: return@mapNotNull null
                val config = Json.asMap(model["config"])
                val properties = Json.asMap(config["properties"])
                ProviderModel(
                    modelId = modelId,
                    enabled = Json.asBool(config["enabled"]) ?: true,
                    supportsVision = Json.asBool(
                        Json.asMap(properties["inputFormat"])["supportsImage"]
                    ) ?: false,
                    contextWindow = Json.asLong(properties["contextWindow"]),
                )
            },
        )
    }
    return ProviderSettingsView(
        revision = Json.asLong(m["revision"]) ?: 0,
        providers = providers,
    )
}
