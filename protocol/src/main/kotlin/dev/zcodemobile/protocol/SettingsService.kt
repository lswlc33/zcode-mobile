package dev.zcodemobile.protocol

/** How a setting is edited in the UI. */
enum class SettingKind { Bool, Int, Text }

/**
 * One editable application setting.
 *
 * A descriptor list rather than a screen full of hand-written rows: the set of
 * toggles that make sense to flip from a phone is small and changes rarely, and
 * a generic renderer cannot drift out of sync with the payload the way a
 * bespoke row per key does.
 */
data class SettingSpec(
    val key: String,
    val label: String,
    val description: String? = null,
    val kind: SettingKind,
)

/**
 * The desktop application settings this client exposes.
 *
 * Deliberately curated. The full `setting.get` payload is 43 keys of mostly
 * desktop-local state (window size, tray behaviour, zoom, update channels) that
 * a remote client has no business writing, plus `httpProxy` — which can carry
 * `user:password@host` and is therefore never read into the UI at all.
 */
object AppSettingsCatalog {

    val editable: List<SettingSpec> = listOf(
        SettingSpec(
            "messageStreamShowReasoning", "显示思考过程",
            "在消息流里展开模型的推理内容", SettingKind.Bool,
        ),
        SettingSpec(
            "messageStreamShowTodos", "显示任务清单",
            "展示 agent 的待办进度", SettingKind.Bool,
        ),
        SettingSpec(
            "toolGroupingChangesEnabled", "折叠文件改动",
            "把同一轮的文件改动合并成一张卡片", SettingKind.Bool,
        ),
        SettingSpec(
            "toolGroupingExploreEnabled", "折叠探索类工具",
            "把搜索/读取类工具调用合并展示", SettingKind.Bool,
        ),
        SettingSpec(
            "toolGroupingTerminalEnabled", "折叠终端命令",
            "把同一轮的终端调用合并展示", SettingKind.Bool,
        ),
        SettingSpec(
            "memoryEnabled", "启用记忆",
            "允许 agent 跨会话记住偏好", SettingKind.Bool,
        ),
        SettingSpec(
            "askUserQuestionAutoResolutionEnabled", "提问自动应答",
            "允许在倒计时结束后自动选择默认项", SettingKind.Bool,
        ),
        SettingSpec(
            "proactiveSuggestionsEnabled", "主动建议",
            "由 agent 主动提出下一步建议", SettingKind.Bool,
        ),
        SettingSpec(
            "nativeSearchEnhancementsEnabled", "原生搜索增强",
            "使用供应商的原生联网搜索", SettingKind.Bool,
        ),
        SettingSpec(
            "keepAwakeWhileRunning", "运行时保持唤醒",
            "任务执行期间阻止系统休眠", SettingKind.Bool,
        ),
        SettingSpec(
            "taskAutoArchiveEnabled", "自动归档任务",
            "超过指定天数的任务自动归档", SettingKind.Bool,
        ),
        SettingSpec(
            "taskAutoArchiveOlderThanDays", "自动归档天数",
            "配合自动归档使用", SettingKind.Int,
        ),
        SettingSpec(
            "zcodeInteractionBehavior", "新输入行为",
            "运行中发送消息时的默认处理方式", SettingKind.Text,
        ),
        SettingSpec(
            "locale", "界面语言",
            null, SettingKind.Text,
        ),
    )

    val editableKeys: Set<String> = editable.mapTo(HashSet()) { it.key }

    /** Read-only values worth showing as context. */
    val readOnly: List<SettingSpec> = listOf(
        SettingSpec("recentProjects", "最近项目", null, SettingKind.Text),
        SettingSpec("providerFamilyDomain", "供应商域", null, SettingKind.Text),
    )
}

/**
 * A **projected** view of `setting.get`.
 *
 * Only whitelisted keys are retained. The raw payload is never stored, logged,
 * or rendered — `httpProxy` in particular can embed proxy credentials, and this
 * object is the kind of thing that ends up in a screenshot or a crash report.
 */
data class AppSettings(val values: Map<String, Any?>) {

    fun bool(key: String): Boolean? = values[key] as? Boolean

    fun string(key: String): String? = values[key] as? String

    fun int(key: String): Int? = (values[key] as? Number)?.toInt()

    /** Values for [AppSettingsCatalog.readOnly] keys, in catalog order. */
    fun recentProjects(): List<String> =
        Json.asList(values["recentProjects"]).mapNotNull { Json.asString(it) }

    companion object {
        val Empty = AppSettings(emptyMap())
    }
}

/**
 * Reads and writes the desktop's global application settings.
 *
 * Backed by the `setting` channel: `get()` / `update(patch, expectedAccountSettings?)`.
 */
@ZCodeExperimental
class SettingsService(private val channel: ChannelClient) {

    companion object {
        const val CHANNEL = "setting"
        private const val METHOD_GET = "get"
        private const val METHOD_UPDATE = "update"
    }

    /** Read the settings, keeping only keys this client understands. */
    suspend fun load(): AppSettings {
        val raw = Json.asMap(channel.call(CHANNEL, METHOD_GET))
        val keep = AppSettingsCatalog.editableKeys + AppSettingsCatalog.readOnly.map { it.key }
        return AppSettings(
            raw.filterKeys { it in keep },
        )
    }

    /**
     * Patch one or more settings.
     *
     * [expectedAccountSettings] is the host's optimistic-concurrency token; pass
     * `null` (omitted) to apply unconditionally.
     */
    suspend fun update(
        patch: Map<String, Any?>,
        expectedAccountSettings: Any? = null,
    ) {
        require(patch.isNotEmpty()) { "update needs at least one setting" }
        val args = if (expectedAccountSettings == null) {
            listOf(patch)
        } else {
            listOf(patch, expectedAccountSettings)
        }
        channel.call(CHANNEL, METHOD_UPDATE, *args.toTypedArray())
    }
}
