package com.drivercall.data.remote

import com.drivercall.data.remote.dto.FriendActionRequest
import com.drivercall.data.remote.dto.FriendDto
import com.drivercall.data.remote.dto.FriendsEnvelope
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.DELETE
import retrofit2.http.Path

interface FriendApi {
    @GET("api/friends")
    suspend fun getFriends(): FriendsEnvelope

    @POST("api/friends/invitations")
    suspend fun sendInvite(@Body request: FriendActionRequest)

    @POST("api/friends/invitations/{id}/accept")
    suspend fun acceptInvite(@Path("id") friendId: String)

    @DELETE("api/friends/{userId}")
    suspend fun removeFriend(@Path("userId") userId: String)

    @POST("api/friends/blocks")
    suspend fun blockUser(@Body request: FriendActionRequest)
}
