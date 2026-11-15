package com.example.network

import com.example.domain.model.WinGoGameConfig
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface GetPanelApiService {

    @GET("api/Energy/GetPanel")
    suspend fun getPanel(
        @Header("Authorization") authorization: String,
        @Header("Origin") origin: String = WinGoGameConfig.API_ORIGIN,
        @Header("Referer") referer: String = WinGoGameConfig.API_REFERER,
        @Query("gameCode") gameCode: String = WinGoGameConfig.GAME_CODE,
        @Query("language") language: String = WinGoGameConfig.LANGUAGE,
        @Query("random") random: String,
        @Query("signature") signature: String,
        @Query("timestamp") timestamp: Long
    ): Response<GetPanelResponse>
}
