package com.werewolf.controller

import com.werewolf.auth.AuthService
import com.werewolf.dto.AuthResponse
import com.werewolf.dto.UserLoginRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Guest login — no OAuth required.
 *
 * POST /api/user/login  { "nickname": "Alice" }
 *
 * Creates a new guest user in the database on every call (userId = "guest:{uuid}").
 * Returns a JWT the client uses for all subsequent authenticated requests.
 *
 * Authorization note: in production this endpoint can be gated behind an invitation
 * check or OAuth. For the current MVP it is open to any player with a valid room code.
 */
@RestController
@RequestMapping("/api/user")
class UserController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): ResponseEntity<AuthResponse> {
        val userId = "guest:${UUID.randomUUID()}"
        val response = authService.loginOrRegister(userId, request.nickname.trim(), null)
        return ResponseEntity.ok(response)
    }
}
