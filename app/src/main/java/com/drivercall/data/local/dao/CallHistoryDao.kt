package com.drivercall.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drivercall.data.local.entity.CallRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CallRecordEntity)

    @Query("SELECT * FROM call_history ORDER BY startTimeMillis DESC")
    fun observeHistory(): Flow<List<CallRecordEntity>>

    @Query("DELETE FROM call_history")
    suspend fun clear()
}
