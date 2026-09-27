package dev.zcodemobile.shared.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.zcodemobile.shared.session.ZCodeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
}

/** Remembers an [AppModel] keyed to the composition. */
@Composable
fun rememberAppModel(services: AppServices): AppModel {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val model = remember { AppModel(services, scope) }
    DisposableEffect(Unit) {
        onDispose { }
    }
    return model
}
