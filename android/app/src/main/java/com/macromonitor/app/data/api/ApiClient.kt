package com.macromonitor.app.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** Builds a Retrofit client bound to a specific bridge base URL + session token. */
object ApiClient {

    /**
     * @param token session token attached as `Authorization: Bearer …`. Pass null
     *   (or blank) for public endpoints such as `/health`, `/api/v1/info` and the
     *   `auth/login` / `auth/pair` calls that mint a session.
     */
    fun create(baseUrl: String, token: String?): MacroApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val bearer = token?.trim().orEmpty()
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("Accept", "application/json")
                if (bearer.isNotEmpty()) {
                    builder.header("Authorization", "Bearer $bearer")
                }
                chain.proceed(builder.build())
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
