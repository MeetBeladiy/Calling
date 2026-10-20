package com.drivercall.data.remote

import com.drivercall.data.remote.dto.UpdateProfileRequest
import com.drivercall.data.remote.dto.UserDto
import com.drivercall.data.remote.dto.UserEnvelope
import com.drivercall.data.remote.dto.UsersEnvelope
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Query

interface UserApi {
    @GET("api/users/me")
    suspend fun getMe(): UserEnvelope

    @PATCH("api/users/me")
    suspend fun updateMe(@Body request: UpdateProfileRequest): UserEnvelope

    @GET("api/users/search")
    suspend fun search(@Query("q") query: String): UsersEnvelope
}
