package com.macromonitor.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Builds a Retrofit client bound to a specific bridge base URL + token. */
object ApiClient {

    fun create(baseUrl: String, token: String): MacroApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MacroApi::class.java)
    }

    /** Absolute URL for an image proxy path returned by the bridge. */
    fun absoluteImageUrl(baseUrl: String, proxyPath: String?): String? {
        if (proxyPath.isNullOrBlank()) return null
        return if (proxyPath.startsWith("http")) proxyPath
        else baseUrl.trimEnd('/') + "/" + proxyPath.trimStart('/')
    }
}
