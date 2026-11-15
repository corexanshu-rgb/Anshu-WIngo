package com.example.data.local

import androidx.room.TypeConverter
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class RoomTypeConverters {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val intDoubleMapType = Types.newParameterizedType(
        Map::class.java,
        Integer::class.javaObjectType,
        java.lang.Double::class.javaObjectType
    )
    private val intDoubleAdapter = moshi.adapter<Map<Int, Double>>(intDoubleMapType)

    private val stringDoubleMapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        java.lang.Double::class.javaObjectType
    )
    private val stringDoubleAdapter = moshi.adapter<Map<String, Double>>(stringDoubleMapType)

    @TypeConverter
    fun fromIntDoubleMap(map: Map<Int, Double>?): String {
        return intDoubleAdapter.toJson(map ?: emptyMap())
    }

    @TypeConverter
    fun toIntDoubleMap(json: String?): Map<Int, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { intDoubleAdapter.fromJson(json) }.getOrNull().orEmpty()
    }

    @TypeConverter
    fun fromStringDoubleMap(map: Map<String, Double>?): String {
        return stringDoubleAdapter.toJson(map ?: emptyMap())
    }

    @TypeConverter
    fun toStringDoubleMap(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { stringDoubleAdapter.fromJson(json) }.getOrNull().orEmpty()
    }

    @TypeConverter
    fun fromPredictionStatus(status: PredictionStatus): String = status.name

    @TypeConverter
    fun toPredictionStatus(value: String): PredictionStatus =
        runCatching { PredictionStatus.valueOf(value) }.getOrDefault(PredictionStatus.INSUFFICIENT_SIGNAL)

    @TypeConverter
    fun fromVerificationStatus(status: VerificationStatus): String = status.name

    @TypeConverter
    fun toVerificationStatus(value: String): VerificationStatus =
        runCatching { VerificationStatus.valueOf(value) }.getOrDefault(VerificationStatus.PENDING)
}
