package com.drivercall.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val id: String,
    val friendUserId: String,
    val name: String,
    val profilePhotoUrl: String?,
    /** Stored as the FriendStatus enum name. */
    val status: String,
    val isOnline: Boolean,
    val lastActiveAt: Long?
)
