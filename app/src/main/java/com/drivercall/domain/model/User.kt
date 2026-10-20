package com.drivercall.domain.model

data class User(
    val id: String,
    val name: String,
    val email: String,
    val phone: String?,
    val profilePhotoUrl: String?,
    val createdAt: Long
)
