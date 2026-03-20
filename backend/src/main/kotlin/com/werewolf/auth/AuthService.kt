package com.werewolf.auth

import com.werewolf.dto.AuthResponse
import com.werewolf.model.User
import com.werewolf.repository.UserRepository
import com.werewolf.util.JwtUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtUtil: JwtUtil,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun loginOrRegister(userId: String, nickname: String, avatarUrl: String?): AuthResponse {
        log.debug("loginOrRegister userId={} nickname={}", userId, nickname)

        val user = userRepository.findById(userId).map { existing ->
            existing.nickname = nickname
            if (avatarUrl != null) existing.avatarUrl = avatarUrl
            userRepository.save(existing)
        }.orElseGet {
            userRepository.save(User(userId = userId, nickname = nickname, avatarUrl = avatarUrl))
        }

        val token = jwtUtil.generateToken(user.userId, user.nickname, user.avatarUrl)
        return AuthResponse(token, AuthResponse.UserDto(user.userId, user.nickname, user.avatarUrl))
    }
}
