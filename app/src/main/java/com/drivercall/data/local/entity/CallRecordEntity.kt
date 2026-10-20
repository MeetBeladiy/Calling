package com.drivercall.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "call_history")
data class CallRecordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val peerName: String,
    val peerId: String?,
    val startTimeMillis: Long,
    val durationSeconds: Long,
    /** Stored as the CallType enum name (VOIP / CELLULAR). */
    val type: String,
    val reconnectionCount: Int,
    val qualitySummary: String?
)
