package com.werewolf.util

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.*

@Component
class JwtUtil(
    @Value("\${app.jwt.secret}") secret: String,
    @Value("\${app.jwt.expiration-hours:2}") private val expirationHours: Long,
) {
    private val signingKey = run {
        val keyBytes = secret.toByteArray(StandardCharsets.UTF_8)
        require(keyBytes.size >= 32) {
            "JWT secret must be at least 32 characters. Current length: ${keyBytes.size}"
        }
        Keys.hmacShaKeyFor(keyBytes)
    }

    private val expirationMillis = expirationHours * 60 * 60 * 1000L

    fun generateToken(userId: String, nickname: String, avatarUrl: String?): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId)
            .claim("nickname", nickname)
            .claim("avatarUrl", avatarUrl)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMillis))
            .signWith(signingKey)
            .compact()
    }

    fun parseToken(token: String): Claims =
        Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

    fun extractUserId(token: String): String? = runCatching { parseToken(token).subject }.getOrNull()

    fun extractNickname(token: String): String? =
        runCatching { parseToken(token).get("nickname", String::class.java) }.getOrNull()

    fun extractAvatarUrl(token: String): String? =
        runCatching { parseToken(token).get("avatarUrl", String::class.java) }.getOrNull()
}
