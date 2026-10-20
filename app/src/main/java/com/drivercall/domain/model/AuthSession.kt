package com.drivercall.domain.model

/** An authenticated session: the signed-in user plus their bearer token. */
data class AuthSession(
    val user: User,
    val token: String
)
