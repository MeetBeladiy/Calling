package com.drivercall.domain.repository

import com.drivercall.domain.model.AuthSession
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    /** Emits the current session, or null when signed out. */
    fun observeSession(): Flow<AuthSession?>

    /**
     * Validates a previously stored token at startup and rebuilds the session.
     * Returns true when the user ends up signed in.
     */
    suspend fun tryRestoreSession(): Boolean

    suspend fun login(email: String, password: String): Result<AuthSession>

    suspend fun register(name: String, email: String, password: String): Result<AuthSession>

    suspend fun requestPasswordReset(email: String): Result<Unit>

    /** Clears the local session (token + cached user). */
    suspend fun logout()
}
