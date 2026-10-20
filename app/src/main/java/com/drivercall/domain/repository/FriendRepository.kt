package com.drivercall.domain.repository

import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.User
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow

interface FriendRepository {
    fun observeFriends(): Flow<List<Friend>>

    suspend fun refreshFriends(): Result<Unit>

    suspend fun searchUsers(query: String): Result<List<User>>

    suspend fun sendInvite(userId: String): Result<Unit>

    suspend fun acceptInvite(friendId: String): Result<Unit>

    suspend fun removeFriend(friendId: String): Result<Unit>

    suspend fun blockUser(friendId: String): Result<Unit>
}
