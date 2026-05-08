package com.werewolf.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AuthRequest(
    @field:NotBlank(message = "OAuth authorization code must not be blank")
    val code: String,
)

data class ProvidersResponse(
    val google: GoogleProvider?,
    val wechat: WeChatProvider?,
    val guest: Boolean,
)

data class GoogleProvider(val clientId: String)
data class WeChatProvider(val appId: String)

data class UserLoginRequest(
    @field:NotBlank(message = "Nickname must not be blank")
    @field:Size(max = 50, message = "Nickname must be at most 50 characters")
    val nickname: String,
)

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
