package com.macromonitor.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.macromonitor.app.di.AppContainer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MacroMonitorApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        // Report images are bridge-proxied, but some URLs point straight at the
        // Discord CDN. The bearer token is only ever attached to the bridge host
        // so it can never leak to third-party servers.
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val bridgeHost = container.secureStore.baseUrl.toHttpUrlOrNull()?.host
                val token = container.secureStore.sessionTokenOrNull()
                val isBridgeRequest =
                    bridgeHost != null && request.url.host.equals(bridgeHost, ignoreCase = true)
                val authorized = if (isBridgeRequest && !token.isNullOrBlank()) {
                    request.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    request
                }
                chain.proceed(authorized)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
