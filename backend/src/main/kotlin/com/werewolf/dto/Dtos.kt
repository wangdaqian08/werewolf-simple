package com.werewolf.dto

data class AuthRequest(val code: String)

data class AuthResponse(
    val token: String,
    val user: UserDto,
) {
    data class UserDto(
        val userId: String,
        val nickname: String,
        val avatarUrl: String?,
    )
}
