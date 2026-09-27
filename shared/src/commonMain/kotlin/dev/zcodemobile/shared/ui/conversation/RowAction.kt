package dev.zcodemobile.shared.ui.conversation

/**
 * Actions offered on a transcript row.
 *
 * The set is filtered per row: editing applies to a user message, retry and
 * forking to an assistant one, and the file rewind only to a row that actually
 * changed files. Offering all of them everywhere would produce commands the
 * host rejects with `guard.latestQueryEditOnly` and friends.
 */
enum class RowAction {
    /** Copy the row's text to the clipboard. Client-side only. */
    Copy,

    /** Rewrite a user message, keeping the current file state. */
    Edit,

    /** Rewrite a user message and restore that turn's files first. */
    EditAndRewind,

    /** Re-run an assistant turn. */
    Retry,

    /** Branch the conversation at an assistant turn into a new session. */
    Fork,

    Like,
    Dislike,

    /** Remove an existing rating. */
    ClearFeedback,

    /** Undo a turn's file changes without touching the conversation. */
    RewindFiles,
}

/** Localised labels; these are identifiers on the wire, never shown raw. */
object RowActionLabels {
    fun label(action: RowAction): String = when (action) {
        RowAction.Copy -> "复制"
        RowAction.Edit -> "编辑并重新发送"
        RowAction.EditAndRewind -> "编辑并回退文件"
        RowAction.Retry -> "重新生成"
        RowAction.Fork -> "从这里分叉"
        RowAction.Like -> "有帮助"
        RowAction.Dislike -> "没帮助"
        RowAction.ClearFeedback -> "取消评价"
        RowAction.RewindFiles -> "回退本轮的代码改动"
    }

    /** One-line explanation for the destructive variants. */
    fun describe(action: RowAction): String? = when (action) {
        RowAction.EditAndRewind ->
            "先把这个回合改动的文件还原，再按新内容重发"
        RowAction.RewindFiles ->
            "只还原文件，聊天记录保持不变"
        RowAction.Fork ->
            "以这条回复为起点，另开一个会话"
        else -> null
    }
}
