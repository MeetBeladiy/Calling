package com.drivercall.domain.repository

import com.drivercall.domain.model.CallRecord
import kotlinx.coroutines.flow.Flow

interface CallHistoryRepository {
    fun observeHistory(): Flow<List<CallRecord>>

    suspend fun addRecord(record: CallRecord)

    suspend fun clearHistory()
}
