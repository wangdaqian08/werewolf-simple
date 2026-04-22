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

/**
 * Guest login — no OAuth required.
 *
 * POST /api/user/login  { "nickname": "Alice" }
 *
 * The userId is derived deterministically from the (lowercased, trimmed) nickname
 * — `guest:alice` for any "Alice" / "alice" / "  ALICE  ". Logging in again with
 * the same nickname returns the SAME userId, so a player who lost their JWT (new
 * browser, cleared cache, dropped connection) can rejoin a room/game by simply
 * re-entering their nickname. Trade-off: anyone who knows your nickname can
 * become you. Acceptable for the small-group, host-vetted-lobby use case.
 *
 * Returns a JWT the client uses for all subsequent authenticated requests.
 */
@RestController
@RequestMapping("/api/user")
class UserController(private val authService: AuthService) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): ResponseEntity<AuthResponse> {
        val nickname = request.nickname.trim()
        val userId = "guest:${nickname.lowercase().replace(" ", "-")}"
        val response = authService.loginOrRegister(userId, nickname, null)
        return ResponseEntity.ok(response)
    }
}
