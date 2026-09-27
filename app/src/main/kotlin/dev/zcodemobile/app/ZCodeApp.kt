package dev.zcodemobile.app

import android.app.Application
import dev.zcodemobile.app.data.AndroidLinkStore
import dev.zcodemobile.app.data.AndroidUiPrefs
import dev.zcodemobile.app.net.OkHttpWebSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ZCodeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val webSockets by lazy { OkHttpWebSocketFactory() }
    val linkStore by lazy { AndroidLinkStore(this) }
    val uiPrefs by lazy { AndroidUiPrefs(this) }
}
