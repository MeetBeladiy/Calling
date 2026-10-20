package com.drivercall.data.repository

import com.drivercall.data.local.dao.CallHistoryDao
import com.drivercall.data.local.entity.CallRecordEntity
import com.drivercall.domain.model.CallRecord
import com.drivercall.domain.model.CallType
import com.drivercall.domain.repository.CallHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallHistoryRepositoryImpl @Inject constructor(
    private val callHistoryDao: CallHistoryDao
) : CallHistoryRepository {

    override fun observeHistory(): Flow<List<CallRecord>> =
        callHistoryDao.observeHistory().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun addRecord(record: CallRecord) {
        callHistoryDao.insert(
            CallRecordEntity(
                id = record.id,
                peerName = record.peerName,
                peerId = record.peerId,
                startTimeMillis = record.startTimeMillis,
                durationSeconds = record.durationSeconds,
                type = record.type.name,
                reconnectionCount = record.reconnectionCount,
                qualitySummary = record.qualitySummary
            )
        )
    }

    override suspend fun clearHistory() = callHistoryDao.clear()

    private fun CallRecordEntity.toDomain(): CallRecord = CallRecord(
        id = id,
        peerName = peerName,
        peerId = peerId,
        startTimeMillis = startTimeMillis,
        durationSeconds = durationSeconds,
        type = runCatching { CallType.valueOf(type) }.getOrDefault(CallType.VOIP),
        reconnectionCount = reconnectionCount,
        qualitySummary = qualitySummary
    )
}
