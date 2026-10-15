package com.macromonitor.app.di

import android.content.Context
import com.macromonitor.app.data.prefs.SecureStore
import com.macromonitor.app.data.repository.MacroRepository
import com.macromonitor.app.net.BridgeDiscovery
import com.macromonitor.app.net.ConnectivityChecker

/** Manual DI — small app, no framework needed. */
class AppContainer(context: Context) {

    val secureStore: SecureStore = SecureStore(context)
    val connectivity: ConnectivityChecker = ConnectivityChecker(context)
    val discovery: BridgeDiscovery = BridgeDiscovery(context)

    @Volatile
    private var repository: MacroRepository? = null

    @Volatile
    private var cachedHost: String = ""

    @Volatile
    private var cachedToken: String? = null

    /** The authenticated repository, rebuilt when the address or token changes. */
    fun repository(): MacroRepository? {
        val token = secureStore.sessionTokenOrNull()
        if (token.isNullOrBlank() || secureStore.baseUrl.isBlank()) return null

        val existing = repository
        if (existing != null &&
            cachedHost.equals(secureStore.baseUrl, ignoreCase = true) &&
            cachedToken == token
        ) {
            return existing
        }
        return MacroRepository(secureStore.baseUrl, token).also {
            repository = it
            cachedHost = secureStore.baseUrl
            cachedToken = token
        }
    }

    /** A token-less repository for pre-login calls (info / login / pair). */
    fun anonymousRepository(baseUrl: String): MacroRepository =
        MacroRepository(baseUrl, null)

    fun resetRepository() {
        repository = null
        cachedHost = ""
        cachedToken = null
    }
}
