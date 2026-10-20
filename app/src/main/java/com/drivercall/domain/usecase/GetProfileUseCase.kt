package com.drivercall.domain.usecase

import com.drivercall.domain.model.User
import com.drivercall.domain.repository.UserRepository
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetProfileUseCase @Inject constructor(
    private val userRepository: UserRepository
) {
    fun observe(): Flow<User?> = userRepository.observeProfile()

    suspend fun refresh(): Result<User> = userRepository.refreshProfile()
}
