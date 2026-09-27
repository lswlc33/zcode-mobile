package dev.zcodemobile.shared.platform

import dev.zcodemobile.shared.data.LinkStore
import dev.zcodemobile.shared.data.UiPrefs
import dev.zcodemobile.protocol.WebSocketFactory

/**
 * The platform services an app shell must supply before any session work
 * starts. Android builds it in `ZCodeApp`, iOS in `IosApp` at startup.
 */
class AppServices(
    val webSockets: WebSocketFactory,
    val linkStore: LinkStore,
    val uiPrefs: UiPrefs,
)
