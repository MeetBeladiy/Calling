package com.drivercall.data.repository

import com.drivercall.data.local.dao.FriendDao
import com.drivercall.data.remote.FriendApi
import com.drivercall.data.remote.UserApi
import com.drivercall.data.remote.dto.FriendActionRequest
import com.drivercall.data.remote.dto.toDomain
import com.drivercall.data.remote.dto.toEntity
import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.FriendStatus
import com.drivercall.domain.model.User
import com.drivercall.domain.repository.FriendRepository
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepositoryImpl @Inject constructor(
    private val friendApi: FriendApi,
    private val userApi: UserApi,
    private val friendDao: FriendDao
) : FriendRepository {

    override fun observeFriends(): Flow<List<Friend>> =
        friendDao.observeFriends().map { entities ->
            entities.map { entity ->
                Friend(
                    id = entity.id,
                    friendUserId = entity.friendUserId,
                    name = entity.name,
                    profilePhotoUrl = entity.profilePhotoUrl,
                    status = runCatching { FriendStatus.valueOf(entity.status) }
                        .getOrDefault(FriendStatus.PENDING_OUTGOING),
                    isOnline = entity.isOnline,
                    lastActiveAt = entity.lastActiveAt
                )
            }
        }

    override suspend fun refreshFriends(): Result<Unit> {
        return try {
            val dtos = friendApi.getFriends().friends
            friendDao.replaceAll(dtos.map { it.toEntity() })
            Result.Success(Unit)
        } catch (e: IOException) {
            Result.Error("No internet connection.", e)
        } catch (e: HttpException) {
            Result.Error("Could not load friends.", e)
        } catch (e: Exception) {
            Result.Error("Something went wrong.", e)
        }
    }

    override suspend fun searchUsers(query: String): Result<List<User>> {
        return try {
            Result.Success(userApi.search(query).users.map { it.toDomain() })
        } catch (e: IOException) {
            Result.Error("No internet connection.", e)
        } catch (e: Exception) {
            Result.Error("Search failed.", e)
        }
    }

    override suspend fun sendInvite(userId: String): Result<Unit> =
        friendAction { friendApi.sendInvite(FriendActionRequest(userId)) }

    override suspend fun acceptInvite(friendId: String): Result<Unit> =
        friendAction { friendApi.acceptInvite(friendId) }

    override suspend fun removeFriend(friendId: String): Result<Unit> = friendAction {
        val userId = friendDao.getFriend(friendId)?.friendUserId
            ?: throw IllegalStateException("Friend not found")
        friendApi.removeFriend(userId)
    }

    override suspend fun blockUser(friendId: String): Result<Unit> = friendAction {
        val userId = friendDao.getFriend(friendId)?.friendUserId
            ?: throw IllegalStateException("Friend not found")
        friendApi.blockUser(FriendActionRequest(userId))
    }

    private suspend fun friendAction(action: suspend () -> Unit): Result<Unit> {
        return try {
            action()
            // Re-sync so the local list reflects the change immediately.
            refreshFriends()
        } catch (e: IOException) {
            Result.Error("No internet connection.", e)
        } catch (e: HttpException) {
            Result.Error("Action failed. Please try again.", e)
        } catch (e: Exception) {
            Result.Error("Something went wrong.", e)
        }
    }
}
