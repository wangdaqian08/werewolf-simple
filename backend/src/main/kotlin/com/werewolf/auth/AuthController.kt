package com.werewolf.auth

import com.werewolf.dto.AuthRequest
import com.werewolf.dto.AuthResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val googleOAuthService: GoogleOAuthService,
    private val weChatOAuthService: WeChatOAuthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST /api/auth/google
     * Body: { "code": "<google-auth-code>" }
     */
    @PostMapping("/google")
    fun googleLogin(@Valid @RequestBody request: AuthRequest): ResponseEntity<AuthResponse> {
        log.info("Google login attempt")
        val profile = googleOAuthService.exchangeCode(request.code)
        val response = authService.loginOrRegister(profile.userId, profile.nickname, profile.avatarUrl)
        log.info("Google login success for userId={}", profile.userId)
        return ResponseEntity.ok(response)
    }

    /**
     * POST /api/auth/wechat
     * Body: { "code": "<wechat-auth-code>" }
     */
    @PostMapping("/wechat")
    fun wechatLogin(@Valid @RequestBody request: AuthRequest): ResponseEntity<AuthResponse> {
        log.info("WeChat login attempt")
        val profile = weChatOAuthService.exchangeCode(request.code)
        val response = authService.loginOrRegister(profile.userId, profile.nickname, profile.avatarUrl)
        log.info("WeChat login success for userId={}", profile.userId)
        return ResponseEntity.ok(response)
    }
}
