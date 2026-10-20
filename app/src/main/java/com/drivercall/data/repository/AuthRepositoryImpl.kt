package com.drivercall.data.repository

import com.drivercall.data.local.dao.UserDao
import com.drivercall.data.remote.AuthApi
import com.drivercall.data.remote.UserApi
import com.drivercall.data.remote.dto.LoginRequest
import com.drivercall.data.remote.dto.RegisterRequest
import com.drivercall.data.remote.dto.RefreshTokenRequest
import com.drivercall.data.remote.dto.PasswordResetRequest
import com.drivercall.data.remote.dto.toDomain
import com.drivercall.data.remote.dto.toEntity
import com.drivercall.data.local.entity.toDomain
import com.drivercall.data.security.TokenStorage
import com.drivercall.domain.model.AuthSession
import com.drivercall.domain.model.User
import com.drivercall.domain.repository.AuthRepository
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val userDao: UserDao,
    private val tokenStorage: TokenStorage
) : AuthRepository {

    private val sessionState = MutableStateFlow<AuthSession?>(null)

    override fun observeSession(): Flow<AuthSession?> = sessionState.asStateFlow()

    /**
     * Called once at startup (Splash). If a token was stored from a previous
     * sign-in, validates it against the server and rebuilds the session.
     * Returns true when the user is signed in afterwards.
     */
    override suspend fun tryRestoreSession(): Boolean {
        val token = tokenStorage.getToken() ?: return false
        return try {
            val userDto = userApi.getMe().user
            userDao.upsert(userDto.toEntity())
            sessionState.value = AuthSession(userDto.toDomain(), token)
            true
        } catch (e: HttpException) {
            if (e.code() == 401) logout()
            false
        } catch (e: IOException) {
            // Offline: keep the stored token; the UI shows a "no connection" state
            // and retries. We still report signed-in so cached data is visible.
            val cached = userDao.getUser()
            if (cached != null) {
                sessionState.value = AuthSession(cached.toDomain(), token)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun login(email: String, password: String): Result<AuthSession> {
        return try {
            val response = authApi.login(LoginRequest(email, password))
            persistSession(
                response.tokens.accessToken,
                response.tokens.refreshToken,
                response.user.toDomain(),
                response.user.toEntity()
            )
        } catch (e: IOException) {
            Result.Error(e.toUserMessage(), e)
        } catch (e: HttpException) {
            Result.Error(mapHttpError(e, "Login failed. Check your email and password."), e)
        } catch (e: Exception) {
            Result.Error("Something went wrong. Please try again.", e)
        }
    }

    override suspend fun register(
        name: String,
        email: String,
        password: String
    ): Result<AuthSession> {
        return try {
            val response = authApi.register(RegisterRequest(name, email, password))
            persistSession(
                response.tokens.accessToken,
                response.tokens.refreshToken,
                response.user.toDomain(),
                response.user.toEntity()
            )
        } catch (e: IOException) {
            Result.Error(e.toUserMessage(), e)
        } catch (e: HttpException) {
            Result.Error(mapHttpError(e, "Registration failed. Try a different email."), e)
        } catch (e: Exception) {
            Result.Error("Something went wrong. Please try again.", e)
        }
    }

    override suspend fun logout() {
        tokenStorage.getRefreshToken()?.let { refreshToken ->
            runCatching { authApi.logout(RefreshTokenRequest(refreshToken)) }
        }
        tokenStorage.clear()
        userDao.clear()
        sessionState.value = null
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> = try {
        authApi.requestPasswordReset(PasswordResetRequest(email))
        Result.Success(Unit)
    } catch (e: IOException) {
        Result.Error(e.toUserMessage(), e)
    } catch (e: HttpException) {
        Result.Error("Unable to request a password reset right now.", e)
    }

    private suspend fun persistSession(
        accessToken: String,
        refreshToken: String,
        user: User,
        entity: com.drivercall.data.local.entity.UserEntity
    ): Result<AuthSession> {
        tokenStorage.saveTokens(accessToken, refreshToken)
        userDao.upsert(entity)
        val session = AuthSession(user, accessToken)
        sessionState.value = session
        return Result.Success(session)
    }

    private fun mapHttpError(e: HttpException, fallback: String): String {
        return when (e.code()) {
            401 -> "Incorrect email or password."
            409 -> "An account with this email already exists."
            422 -> "Please check the details you entered."
            in 500..599 -> "Server is temporarily unavailable. Try again shortly."
            else -> fallback
        }
    }

    private fun IOException.toUserMessage(): String = when (this) {
        is UnknownHostException ->
            "The Calling App server address could not be found. Check the app's server configuration."
        is ConnectException ->
            "Cannot reach the Calling App server. Make sure it is running and your device is on the same network."
        is SocketTimeoutException ->
            "The Calling App server took too long to respond. Please try again."
        else ->
            "The network request failed. Check your connection and the Calling App server."
    }
}
