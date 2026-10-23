package com.drivercall.domain.model

enum class FriendStatus {
    PENDING_INCOMING, // invitation received; this user can accept it
    PENDING_OUTGOING, // invitation sent; waiting for the other user
    ACCEPTED,   // both sides can call each other
    BLOCKED     // user blocked this contact
}

data class Friend(
    val id: String,
    /** The other user's account id. */
    val friendUserId: String,
    val name: String,
    val profilePhotoUrl: String?,
    val status: FriendStatus,
    val isOnline: Boolean,
    val lastActiveAt: Long?
)
