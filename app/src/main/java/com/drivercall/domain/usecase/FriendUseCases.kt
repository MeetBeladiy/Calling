package com.drivercall.domain.usecase

import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.User
import com.drivercall.domain.repository.FriendRepository
import com.drivercall.domain.util.Result
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetFriendsUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    fun observe(): Flow<List<Friend>> = friendRepository.observeFriends()

    suspend fun refresh(): Result<Unit> = friendRepository.refreshFriends()
}

class SearchUsersUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    suspend operator fun invoke(query: String): Result<List<User>> {
        if (query.trim().length < 2) return Result.Success(emptyList())
        return friendRepository.searchUsers(query.trim())
    }
}

class SendFriendInviteUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    suspend operator fun invoke(userId: String): Result<Unit> =
        friendRepository.sendInvite(userId)
}

class AcceptFriendInviteUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    suspend operator fun invoke(friendId: String): Result<Unit> =
        friendRepository.acceptInvite(friendId)
}

class RemoveFriendUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    suspend operator fun invoke(friendId: String): Result<Unit> =
        friendRepository.removeFriend(friendId)
}

class BlockUserUseCase @Inject constructor(
    private val friendRepository: FriendRepository
) {
    suspend operator fun invoke(friendId: String): Result<Unit> =
        friendRepository.blockUser(friendId)
}
