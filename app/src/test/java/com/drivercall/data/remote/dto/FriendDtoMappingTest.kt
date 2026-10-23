package com.drivercall.data.remote.dto

import com.drivercall.domain.model.FriendStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendDtoMappingTest {
    private val user = PublicUserDto(
        id = "user-2",
        name = "Receiver",
        profilePhotoUrl = null,
        createdAt = "2026-10-23T00:00:00.000Z",
        lastActiveAt = null,
        isOnline = false
    )

    @Test
    fun `incoming pending invitation can be accepted`() {
        val invitation = FriendDto(
            id = "invitation-1",
            status = "pending",
            direction = "incoming",
            user = user,
            createdAt = "2026-10-23T00:00:00.000Z"
        )

        assertEquals(FriendStatus.PENDING_INCOMING, invitation.toDomain().status)
        assertEquals(FriendStatus.PENDING_INCOMING.name, invitation.toEntity().status)
    }

    @Test
    fun `outgoing pending invitation waits for receiver`() {
        val invitation = FriendDto(
            id = "invitation-2",
            status = "pending",
            direction = "outgoing",
            user = user,
            createdAt = "2026-10-23T00:00:00.000Z"
        )

        assertEquals(FriendStatus.PENDING_OUTGOING, invitation.toDomain().status)
        assertEquals(FriendStatus.PENDING_OUTGOING.name, invitation.toEntity().status)
    }

    @Test
    fun `accepted relationship ignores invitation direction`() {
        val friendship = FriendDto(
            id = "friendship-1",
            status = "accepted",
            direction = "incoming",
            user = user,
            createdAt = "2026-10-23T00:00:00.000Z"
        )

        assertEquals(FriendStatus.ACCEPTED, friendship.toDomain().status)
    }
}
