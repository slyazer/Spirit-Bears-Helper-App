package com.macromonitor.app.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MacroApi {

    @GET("health")
    suspend fun health(): HealthDto

    @GET("api/v1/accounts")
    suspend fun accounts(): AccountsResponseDto

    @GET("api/v1/accounts/{id}/status")
    suspend fun status(@Path("id") accountId: String): StatusDto

    @GET("api/v1/accounts/{id}/planters")
    suspend fun planters(@Path("id") accountId: String): PlantersResponseDto

    @GET("api/v1/accounts/{id}/reports")
    suspend fun reports(
        @Path("id") accountId: String,
        @Query("limit") limit: Int = 12,
    ): ReportsResponseDto

    @GET("api/v1/accounts/{id}/logs")
    suspend fun logs(
        @Path("id") accountId: String,
        @Query("limit") limit: Int = 30,
    ): LogsResponseDto

    @POST("api/v1/accounts/{id}/commands")
    suspend fun command(
        @Path("id") accountId: String,
        @Body body: CommandRequestDto,
    ): CommandResponseDto
}
