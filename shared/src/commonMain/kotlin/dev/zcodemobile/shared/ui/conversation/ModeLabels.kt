package dev.zcodemobile.shared.ui.conversation

import dev.zcodemobile.protocol.Commands

/**
 * Display labels for wire enum values.
 *
 * Copy follows the official web composer menu (LIVE-WEB-VERIFICATION §4.1):
 * `build` = 变更前确认, `edit` = 自动编辑, `yolo` = 完全访问. `planEnabled` is
 * a separate axis from the three-mode radio group, mirroring the web's
 * checkbox + radio structure.
 */
object ModeLabels {

    fun label(mode: String?): String? = when (mode) {
        null -> null
        "build" -> "变更前确认"
        "edit" -> "自动编辑"
        "plan" -> "计划模式"
        "yolo" -> "完全访问"
        // A mode the desktop added and this build does not know about: show it
        // rather than hiding it, so the chip never silently disagrees with the
        // session's real state.
        else -> mode
    }

    /** One-line explanation, shown as supporting text in the picker. */
    fun describe(mode: Commands.Mode): String = when (mode) {
        Commands.Mode.Build -> "改文件前先问我。"
        Commands.Mode.Edit -> "自动编辑文件。"
        Commands.Mode.Plan -> "编辑前先出计划。"
        Commands.Mode.Yolo -> "减少确认次数。"
    }

    fun label(mode: Commands.Mode): String = label(mode.wire) ?: mode.wire
}

/** Display labels for the follow-up routing enum (`queue` / `guide`). */
object FollowupLabels {
    fun label(mode: String?): String? = when (mode) {
        null -> null
        "queue" -> "排队"
        "guide" -> "引导"
        else -> mode
    }
}

/** Thinking-budget levels (`config.thoughtLevels`). */
object ThoughtLabels {
    fun label(level: String?): String? = when (level) {
        null -> null
        "disabled" -> "关闭思考"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "max" -> "最高"
        else -> level
    }
}
