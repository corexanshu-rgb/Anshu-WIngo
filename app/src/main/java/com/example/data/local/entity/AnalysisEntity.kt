package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "analysis_runs",
    indices = [Index(value = ["moduleId"], unique = true)]
)
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val moduleId: String,
    val moduleName: String,
    val moduleVersion: String,
    val status: String,
    val sampleSize: Int,
    val validationSampleSize: Int,
    val lastExecutedAt: Long,
    val logLoss: Double?,
    val brierScore: Double?,
    val accuracy: Double?,
    val baselineBrierScore: Double?,
    val assignedWeight: Double,
    val summaryNote: String
)
