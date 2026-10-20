package com.drivercall.domain.usecase

import com.drivercall.domain.repository.AuthRepository
import com.drivercall.domain.util.Result
import javax.inject.Inject

class RequestPasswordResetUseCase @Inject constructor(
    private val repository: AuthRepository
) {
    suspend operator fun invoke(email: String): Result<Unit> {
        val cleanEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return Result.Error("Enter your email address first.")
        }
        return repository.requestPasswordReset(cleanEmail)
    }
}
