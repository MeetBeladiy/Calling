package com.drivercall.domain.usecase

import com.drivercall.domain.model.CallRecord
import com.drivercall.domain.repository.CallHistoryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveCallHistoryUseCase @Inject constructor(
    private val callHistoryRepository: CallHistoryRepository
) {
    operator fun invoke(): Flow<List<CallRecord>> = callHistoryRepository.observeHistory()
}
