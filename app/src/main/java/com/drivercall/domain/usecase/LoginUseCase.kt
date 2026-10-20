package com.drivercall.domain.usecase

import com.drivercall.domain.model.AuthSession
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.domain.util.Result
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(email: String, password: String): Result<AuthSession> {
        val cleanEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return Result.Error("Please enter a valid email address.")
        }
        if (password.length < 10) {
            return Result.Error("Password must be at least 10 characters.")
        }
        return authRepository.login(cleanEmail, password)
    }
}
