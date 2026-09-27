package dev.zcodemobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.zcodemobile.shared.session.ZCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The cross-platform app model: one [ZCodeSession] plus the stores, wired to
 * the platform services. Android's MainViewModel wraps this; iOS composes it
 * directly inside [MainViewController].
 *
 * Kept deliberately free of androidx ViewModel so both shells can share it.
 */
class AppModel(
    val services: AppServices,
    val scope: CoroutineScope,
) {
    val session = ZCodeSession(
        scope = scope,
        webSocketFactory = services.webSockets,
        // The link hash is a live credential; never log it.
        logger = { },
    )

    val linkStore get() = services.linkStore
    val uiPrefs get() = services.uiPrefs

    fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}

/** Remembers an [AppModel] keyed to the composition, torn down on dispose. */
@Composable
fun rememberAppModel(services: AppServices): AppModel {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val model = remember { AppModel(services, scope) }
    DisposableEffect(Unit) {
        onDispose { }
    }
    return model
}
