package com.drivercall.domain.usecase

import com.drivercall.domain.model.AuthSession
import com.drivercall.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAuthStateUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    operator fun invoke(): Flow<AuthSession?> = authRepository.observeSession()
}
