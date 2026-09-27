package dev.zcodemobile.shared.platform

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point: the Xcode app's root view controller. Wires the platform
 * services (NSURLSession sockets, Keychain store) into the shared app model
 * and renders the shared root screen.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val services = AppServices(
        webSockets = IosWebSocketFactory(),
        linkStore = IosLinkStore(),
        uiPrefs = IosUiPrefs(),
    )
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )
    val model = AppModel(services, scope)
    return ComposeUIViewController {
        IosRoot(model)
    }
}
