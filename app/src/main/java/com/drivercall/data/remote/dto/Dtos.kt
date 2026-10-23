package com.drivercall.data.remote.dto

import com.drivercall.data.local.entity.FriendEntity
import com.drivercall.data.local.entity.UserEntity
import com.drivercall.domain.model.Friend
import com.drivercall.domain.model.FriendStatus
import com.drivercall.domain.model.User
import com.google.gson.annotations.SerializedName

// ---------- Auth ----------

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class RegisterRequest(
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String
)

data class AuthResponse(
    @SerializedName("user") val user: UserDto,
    @SerializedName("tokens") val tokens: TokensDto
)

data class TokensDto(
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("expiresIn") val expiresIn: String
)

data class RefreshTokenRequest(@SerializedName("refreshToken") val refreshToken: String)
data class TokensResponse(@SerializedName("tokens") val tokens: TokensDto)
data class PasswordResetRequest(@SerializedName("email") val email: String)
data class MessageResponse(@SerializedName("message") val message: String)

// ---------- Users ----------

data class UserDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("phone") val phone: String?,
    @SerializedName("profilePhoto") val profilePhotoUrl: String?,
    @SerializedName("createdAt") val createdAt: String
)

data class UserEnvelope(@SerializedName("user") val user: UserDto)
data class UsersEnvelope(@SerializedName("users") val users: List<PublicUserDto>)

private fun String.toEpochMillis(): Long = runCatching {
    java.time.Instant.parse(this).toEpochMilli()
}.getOrDefault(0L)

fun UserDto.toDomain(): User = User(
    id = id,
    name = name,
    email = email,
    phone = phone,
    profilePhotoUrl = profilePhotoUrl,
    createdAt = createdAt.toEpochMillis()
)

fun UserDto.toEntity(): UserEntity = UserEntity(
    id = id,
    name = name,
    email = email,
    phone = phone,
    profilePhotoUrl = profilePhotoUrl,
    createdAt = createdAt.toEpochMillis()
)

data class UpdateProfileRequest(
    @SerializedName("name") val name: String,
    @SerializedName("phone") val phone: String?
)

// ---------- Friends ----------

data class FriendDto(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("direction") val direction: String,
    @SerializedName("user") val user: PublicUserDto,
    @SerializedName("createdAt") val createdAt: String
)

data class PublicUserDto(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("profilePhoto") val profilePhotoUrl: String?,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("lastActiveAt") val lastActiveAt: String?,
    @SerializedName("online") val isOnline: Boolean = false
)

fun PublicUserDto.toDomain(): User = User(
    id = id,
    name = name,
    email = "",
    phone = null,
    profilePhotoUrl = profilePhotoUrl,
    createdAt = createdAt.toEpochMillis()
)

data class FriendsEnvelope(@SerializedName("friends") val friends: List<FriendDto>)

fun FriendDto.toDomain(): Friend = Friend(
    id = id,
    friendUserId = user.id,
    name = user.name,
    profilePhotoUrl = user.profilePhotoUrl,
    status = toFriendStatus(),
    isOnline = user.isOnline,
    lastActiveAt = user.lastActiveAt?.toEpochMillis()
)

fun FriendDto.toEntity(): FriendEntity = FriendEntity(
    id = id,
    friendUserId = user.id,
    name = user.name,
    profilePhotoUrl = user.profilePhotoUrl,
    status = toFriendStatus().name,
    isOnline = user.isOnline,
    lastActiveAt = user.lastActiveAt?.toEpochMillis()
)

private fun FriendDto.toFriendStatus(): FriendStatus = when (status.lowercase()) {
    "accepted" -> FriendStatus.ACCEPTED
    else -> if (direction.equals("incoming", ignoreCase = true)) {
        FriendStatus.PENDING_INCOMING
    } else {
        FriendStatus.PENDING_OUTGOING
    }
}

data class FriendActionRequest(
    @SerializedName("userId") val userId: String
)
