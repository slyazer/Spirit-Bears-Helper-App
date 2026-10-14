package com.macromonitor.app.di

import android.content.Context
import com.macromonitor.app.data.prefs.SecureStore
import com.macromonitor.app.data.repository.MacroRepository
import com.macromonitor.app.net.ConnectivityChecker

/** Manual DI — small app, no framework needed. */
class AppContainer(context: Context) {

    val secureStore: SecureStore = SecureStore(context)
    val connectivity: ConnectivityChecker = ConnectivityChecker(context)

    @Volatile
    private var repository: MacroRepository? = null

    fun repository(): MacroRepository? {
        val existing = repository
        if (existing != null && existing.host.equals(secureStore.baseUrl, ignoreCase = true)) {
            return existing
        }
        val token = secureStore.tokenOrNull()
        if (token.isNullOrBlank()) return null
        return MacroRepository(secureStore.baseUrl, token).also { repository = it }
    }

    fun resetRepository() {
        repository = null
    }
}