package com.example.network

import com.example.BuildConfig
import com.example.domain.model.WinGoGameConfig
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    fun createOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            val sanitizedLogger = HttpLoggingInterceptor.Logger { message ->
                val scrubbed = message
                    .replace(Regex("([?&](?:signature|random)=)[^&\\s]+"), "$1<redacted>")
                HttpLoggingInterceptor.Logger.DEFAULT.log(scrubbed)
            }
            val logging = HttpLoggingInterceptor(sanitizedLogger).apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("Cookie")
            }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }

    fun createMoshi(): Moshi = GetPanelParser.buildMoshi()

    fun createRetrofit(okHttpClient: OkHttpClient = createOkHttpClient()): Retrofit {
        val moshi = createMoshi()

        return Retrofit.Builder()
            .baseUrl(WinGoGameConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    fun createHistoryApiService(retrofit: Retrofit = createRetrofit()): HistoryApiService =
        retrofit.create(HistoryApiService::class.java)

    fun createGetPanelApiService(retrofit: Retrofit = createRetrofit()): GetPanelApiService =
        retrofit.create(GetPanelApiService::class.java)

    fun createDrawHistoryJsonService(
        okHttpClient: OkHttpClient = createOkHttpClient()
    ): DrawHistoryJsonService = HistoryDataSource.createDefaultService(okHttpClient = okHttpClient)
}
