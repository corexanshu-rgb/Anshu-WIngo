package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.PredictionEntity
import com.example.domain.model.PredictionStatus
import com.example.domain.model.VerificationStatus
import com.example.domain.model.WinGoGameConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface PredictionDao {

    @Query("SELECT * FROM predictions ORDER BY createdAt DESC LIMIT :limit")
    fun observeLatestPredictions(limit: Int = WinGoGameConfig.MAX_PREDICTION_RECORDS): Flow<List<PredictionEntity>>

    @Query("SELECT * FROM predictions ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getLatestPredictionsSnapshot(limit: Int = WinGoGameConfig.MAX_PREDICTION_RECORDS): List<PredictionEntity>

    @Query("SELECT * FROM predictions WHERE issueNumber = :issueNumber LIMIT 1")
    suspend fun getByIssueNumber(issueNumber: String): PredictionEntity?

    @Query("SELECT * FROM predictions WHERE issueNumber = :issueNumber LIMIT 1")
    fun observeByIssueNumber(issueNumber: String): Flow<PredictionEntity?>

    @Query("SELECT * FROM predictions WHERE verificationStatus = :status")
    suspend fun getByVerificationStatus(status: VerificationStatus = VerificationStatus.PENDING): List<PredictionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: PredictionEntity): Long

    @Query(
        """
        UPDATE predictions
        SET lockedAt = :lockedAt,
            predictionStatus = :lockedStatus
        WHERE issueNumber = :issueNumber
          AND lockedAt IS NULL
          AND predictionStatus = :draftStatus
        """
    )
    suspend fun lockPrediction(
        issueNumber: String,
        lockedAt: Long,
        lockedStatus: PredictionStatus = PredictionStatus.LOCKED,
        draftStatus: PredictionStatus = PredictionStatus.DRAFT
    ): Int

    @Query(
        """
        UPDATE predictions
        SET actualNumber = :actualNumber,
            actualColor = :actualColor,
            actualSize = :actualSize,
            numberCorrect = :numberCorrect,
            colorCorrect = :colorCorrect,
            sizeCorrect = :sizeCorrect,
            verificationStatus = :verificationStatus
        WHERE issueNumber = :issueNumber
          AND lockedAt IS NOT NULL
          AND lockedAt <= :resultReceivedAt
        """
    )
    suspend fun verifyLockedPrediction(
        issueNumber: String,
        actualNumber: Int,
        actualColor: String,
        actualSize: String,
        numberCorrect: Boolean,
        colorCorrect: Boolean,
        sizeCorrect: Boolean,
        verificationStatus: VerificationStatus,
        resultReceivedAt: Long
    ): Int

    @Query(
        """
        UPDATE predictions
        SET verificationStatus = :unverifiableStatus
        WHERE issueNumber = :issueNumber
          AND (lockedAt IS NULL OR lockedAt > :resultReceivedAt)
          AND verificationStatus = :pendingStatus
        """
    )
    suspend fun markUnverifiableIfNotLockedInTime(
        issueNumber: String,
        resultReceivedAt: Long,
        unverifiableStatus: VerificationStatus = VerificationStatus.UNVERIFIABLE,
        pendingStatus: VerificationStatus = VerificationStatus.PENDING
    ): Int

    @Query(
        """
        DELETE FROM predictions
        WHERE id NOT IN (
            SELECT id FROM predictions
            ORDER BY createdAt DESC
            LIMIT :keepLimit
        )
        """
    )
    suspend fun trimOldPredictions(keepLimit: Int = WinGoGameConfig.MAX_PREDICTION_RECORDS)

    @Transaction
    suspend fun insertPredictionAndTrim(entity: PredictionEntity): Long {
        val insertedId = insertIgnore(entity)
        trimOldPredictions(WinGoGameConfig.MAX_PREDICTION_RECORDS)
        return insertedId
    }
}
