package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.ResultEntity
import com.example.domain.model.WinGoGameConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface ResultDao {

    @Query("SELECT * FROM actual_results ORDER BY LENGTH(issueNumber) DESC, issueNumber DESC LIMIT :limit")
    fun observeLatestResults(limit: Int = WinGoGameConfig.MAX_HISTORY_RESULTS): Flow<List<ResultEntity>>

    @Query("SELECT * FROM actual_results ORDER BY LENGTH(issueNumber) DESC, issueNumber DESC LIMIT :limit")
    suspend fun getLatestResultsSnapshot(limit: Int = WinGoGameConfig.MAX_HISTORY_RESULTS): List<ResultEntity>

    @Query("SELECT issueNumber FROM actual_results ORDER BY LENGTH(issueNumber) DESC, issueNumber DESC LIMIT :limit")
    suspend fun getLatestIssueNumbers(limit: Int = WinGoGameConfig.MAX_HISTORY_RESULTS): List<String>

    @Query("SELECT * FROM actual_results WHERE issueNumber = :issueNumber LIMIT 1")
    suspend fun getByIssueNumber(issueNumber: String): ResultEntity?

    @Query("SELECT COUNT(*) FROM actual_results")
    fun observeResultCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM actual_results")
    suspend fun getResultCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<ResultEntity>): List<Long>

    @Query(
        """
        DELETE FROM actual_results
        WHERE id NOT IN (
            SELECT id FROM actual_results
            ORDER BY LENGTH(issueNumber) DESC, issueNumber DESC
            LIMIT :keepLimit
        )
        """
    )
    suspend fun trimOldResults(keepLimit: Int = WinGoGameConfig.MAX_HISTORY_RESULTS)

    @Transaction
    suspend fun upsertAndTrimToLatest500(items: List<ResultEntity>) {
        if (items.isNotEmpty()) {
            insertIgnore(items)
        }
        trimOldResults(WinGoGameConfig.MAX_HISTORY_RESULTS)
    }
}
