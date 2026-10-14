package com.macromonitor.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Distinguishes local connectivity problems from bridge reachability problems,
 * so the app can show an accurate connection diagnosis.
 */
class ConnectivityChecker(private val context: Context) {

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** True if a known public endpoint responds; used to detect captive portals. */
    suspend fun canReachInternet(): Boolean = withContext(Dispatchers.IO) {
        if (!hasInternet()) return@withContext false
        try {
            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .build()
            probeClient.newCall(request).execute().use { it.isSuccessful || it.code == 204 }
        } catch (_: Exception) {
            false
        }
    }
}
