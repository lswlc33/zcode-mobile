package dev.zcodemobile.shared.platform

import dev.zcodemobile.shared.data.UiPrefs
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSUserDefaults

/**
 * iOS [UiPrefs]: NSUserDefaults. Display preferences only — credentials
 * belong in the Keychain ([IosLinkStore]).
 */
class IosUiPrefs : UiPrefs {
    private val defaults = NSUserDefaults.standardUserDefaults

    private val _msgFontScale = kotlinx.coroutines.flow.MutableStateFlow(
        defaults.doubleForKey(KEY_MSG_SCALE).takeIf { it > 0 }?.toFloat() ?: 1f,
    )
    override val msgFontScale: StateFlow<Float> = _msgFontScale

    override fun setMsgFontScale(scale: Float) {
        defaults.setDouble(scale.toDouble(), KEY_MSG_SCALE)
        _msgFontScale.value = scale
    }

    private companion object {
        const val KEY_MSG_SCALE = "msg_font_scale"
    }
}
