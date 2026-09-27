package dev.zcodemobile.app.data

import android.content.Context
import dev.zcodemobile.shared.data.UiPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android [UiPrefs]: plain SharedPreferences for display preferences. */
class AndroidUiPrefs(context: Context) : UiPrefs {
    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _msgFontScale = MutableStateFlow(prefs.getFloat(KEY_MSG_SCALE, 1f))
    override val msgFontScale: StateFlow<Float> = _msgFontScale.asStateFlow()

    override fun setMsgFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_MSG_SCALE, scale).apply()
        _msgFontScale.value = scale
    }

    private companion object {
        const val KEY_MSG_SCALE = "msg_font_scale"
    }
}
