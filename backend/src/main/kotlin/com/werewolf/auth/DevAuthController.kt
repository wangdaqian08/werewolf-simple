package com.werewolf.auth

import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DevLoginRequest(
    val nickname: String,
    val userId: String? = null,
)

/**
 * Dev-only auth bypass — not compiled into production builds.
 * Active only when spring.profiles.active=dev.
 *
 * POST /api/auth/dev  { "nickname": "Alice" }
 *                     { "nickname": "Bob", "userId": "dev:bob-fixed-id" }
 */
@Profile("dev")
@RestController
@RequestMapping("/api/auth")
class DevAuthController(private val authService: AuthService) {

    @PostMapping("/dev")
    fun devLogin(@RequestBody request: DevLoginRequest): ResponseEntity<*> {
        val resolvedUserId = request.userId
            ?: "dev:${request.nickname.lowercase().replace(" ", "-")}"
        val response = authService.loginOrRegister(resolvedUserId, request.nickname, null)
        return ResponseEntity.ok(response)
    }
}
