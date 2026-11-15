package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus

@Entity(
    tableName = "predictions",
    indices = [Index(value = ["issueNumber"], unique = true)]
)
data class PredictionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val issueNumber: String,
    val createdAt: Long,
    val lockedAt: Long?,
    val numberProbabilities: Map<Int, Double>,
    val colorProbabilities: Map<String, Double>,
    val sizeProbabilities: Map<String, Double>,
    val predictedNumber: Int?,
    val predictedColor: String?,
    val predictedSize: String?,
    val confidence: Double?,
    val contributingModels: String = "",
    val modelVersions: String,
    val actualNumber: Int? = null,
    val actualColor: String? = null,
    val actualSize: String? = null,
    val numberCorrect: Boolean? = null,
    val colorCorrect: Boolean? = null,
    val sizeCorrect: Boolean? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,
    val predictionStatus: PredictionStatus = PredictionStatus.INSUFFICIENT_SIGNAL
)
