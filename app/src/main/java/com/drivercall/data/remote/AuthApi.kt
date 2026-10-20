package com.drivercall.data.remote

import com.drivercall.data.remote.dto.AuthResponse
import com.drivercall.data.remote.dto.LoginRequest
import com.drivercall.data.remote.dto.RegisterRequest
import retrofit2.http.Body
import retrofit2.http.POST
import com.drivercall.data.remote.dto.RefreshTokenRequest
import com.drivercall.data.remote.dto.TokensResponse
import com.drivercall.data.remote.dto.PasswordResetRequest
import com.drivercall.data.remote.dto.MessageResponse

interface AuthApi {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body request: RefreshTokenRequest): TokensResponse

    @POST("api/auth/logout")
    suspend fun logout(@Body request: RefreshTokenRequest)

    @POST("api/auth/password-reset/request")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequest): MessageResponse
}
