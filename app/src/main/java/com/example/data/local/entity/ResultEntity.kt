package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "actual_results",
    indices = [Index(value = ["issueNumber"], unique = true)]
)
data class ResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val issueNumber: String,
    val number: Int,
    val color: String,
    val premium: String,
    val sum: Int,
    val blockId: String,
    val blockNumber: String,
    val blockTimestamp: String,
    val source: String = "",
    val receivedAt: Long
)
