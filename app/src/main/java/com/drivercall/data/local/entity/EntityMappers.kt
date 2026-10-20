package com.drivercall.data.local.entity

import com.drivercall.domain.model.User

fun UserEntity.toDomain(): User = User(
    id = id,
    name = name,
    email = email,
    phone = phone,
    profilePhotoUrl = profilePhotoUrl,
    createdAt = createdAt
)
