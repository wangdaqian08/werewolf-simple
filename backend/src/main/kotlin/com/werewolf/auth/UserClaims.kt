package com.werewolf.auth

data class UserClaims(
    val userId: String,
    val nickname: String,
    val avatarUrl: String?,
)
