package com.drivercall.domain.usecase

import com.drivercall.domain.model.AuthSession
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.domain.util.Result
import javax.inject.Inject

class RegisterUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(name: String, email: String, password: String): Result<AuthSession> {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        if (cleanName.length < 2) {
            return Result.Error("Please enter your name.")
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            return Result.Error("Please enter a valid email address.")
        }
        if (password.length < 10) {
            return Result.Error("Password must be at least 10 characters.")
        }
        return authRepository.register(cleanName, cleanEmail, password)
    }
}
