package com.drivercall.domain.repository

import com.drivercall.domain.model.User
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /** The signed-in user's profile, kept fresh from cache + network. */
    fun observeProfile(): Flow<User?>

    suspend fun refreshProfile(): Result<User>

    suspend fun updateProfile(name: String, phone: String?): Result<User>
}
