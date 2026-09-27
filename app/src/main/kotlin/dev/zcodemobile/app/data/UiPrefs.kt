package dev.zcodemobile.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny UI-preference store. The relay link store stays Keystore-encrypted for
 * credentials; these are display preferences, so plain SharedPreferences is
 * the right weight.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _msgFontScale = MutableStateFlow(prefs.getFloat(KEY_MSG_SCALE, 1f))
    val msgFontScale: StateFlow<Float> = _msgFontScale.asStateFlow()

    fun setMsgFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_MSG_SCALE, scale).apply()
        _msgFontScale.value = scale
    }

    private companion object {
        const val KEY_MSG_SCALE = "msg_font_scale"
    }
}
