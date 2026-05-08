package com.werewolf.auth

import com.werewolf.dto.AuthRequest
import com.werewolf.dto.AuthResponse
import com.werewolf.dto.GoogleProvider
import com.werewolf.dto.ProvidersResponse
import com.werewolf.dto.WeChatProvider
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
    @Value("\${app.oauth.google.client-id:}") private val googleClientId: String,
    @Value("\${app.oauth.wechat.app-id:}") private val wechatAppId: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET /api/auth/providers
     * Returns which login providers are enabled in this deployment so the
     * frontend can hide buttons that have no corresponding env-var configured.
     * Guest is always available.
     */
    @GetMapping("/providers")
    fun providers(): ResponseEntity<ProvidersResponse> = ResponseEntity.ok(
        ProvidersResponse(
            google = if (googleClientId.isNotBlank()) GoogleProvider(googleClientId) else null,
            wechat = if (wechatAppId.isNotBlank()) WeChatProvider(wechatAppId) else null,
            guest = true,
        ),
    )

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
