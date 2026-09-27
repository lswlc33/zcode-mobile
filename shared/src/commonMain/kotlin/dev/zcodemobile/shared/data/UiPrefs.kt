package dev.zcodemobile.shared.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Display preferences. Plain per-platform storage is the right weight —
 * only credentials belong in the encrypted store.
 */
interface UiPrefs {
    val msgFontScale: StateFlow<Float>
    fun setMsgFontScale(scale: Float)
}
