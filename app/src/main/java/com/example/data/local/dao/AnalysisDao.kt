package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.AnalysisEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisDao {

    @Query("SELECT * FROM analysis_runs ORDER BY moduleId ASC")
    fun observeAll(): Flow<List<AnalysisEntity>>

    @Query("SELECT * FROM analysis_runs ORDER BY moduleId ASC")
    suspend fun getAllSnapshot(): List<AnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<AnalysisEntity>)
}
