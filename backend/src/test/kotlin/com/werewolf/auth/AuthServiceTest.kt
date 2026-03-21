package com.werewolf.auth

import com.werewolf.model.User
import com.werewolf.repository.UserRepository
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AuthServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var jwtUtil: JwtUtil
    @InjectMocks lateinit var authService: AuthService

    @Test
    fun `loginOrRegister creates and saves new user when userId not found`() {
        val userId = "guest:abc-123"
        val nickname = "Alice"
        val savedUser = User(userId = userId, nickname = nickname)

        whenever(userRepository.findById(userId)).thenReturn(Optional.empty())
        whenever(userRepository.save(any<User>())).thenReturn(savedUser)
        whenever(jwtUtil.generateToken(userId, nickname, null)).thenReturn("mock.jwt.token")

        val result = authService.loginOrRegister(userId, nickname, null)

        assertThat(result.token).isEqualTo("mock.jwt.token")
        assertThat(result.user.userId).isEqualTo(userId)
        assertThat(result.user.nickname).isEqualTo(nickname)
        assertThat(result.user.avatarUrl).isNull()

        val captor = argumentCaptor<User>()
        verify(userRepository).save(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo(userId)
        assertThat(captor.firstValue.nickname).isEqualTo(nickname)
    }

    @Test
    fun `loginOrRegister updates nickname of existing user`() {
        val userId = "guest:abc-123"
        val existingUser = User(userId = userId, nickname = "OldNick")

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(existingUser))
        whenever(userRepository.save(any<User>())).thenReturn(existingUser)
        whenever(jwtUtil.generateToken(any(), any(), anyOrNull())).thenReturn("mock.jwt.token")

        authService.loginOrRegister(userId, "NewNick", null)

        assertThat(existingUser.nickname).isEqualTo("NewNick")
        verify(userRepository).save(existingUser)
    }

    @Test
    fun `loginOrRegister updates avatarUrl when provided`() {
        val userId = "google:xyz"
        val existingUser = User(userId = userId, nickname = "Bob", avatarUrl = "https://old.png")

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(existingUser))
        whenever(userRepository.save(any<User>())).thenReturn(existingUser)
        whenever(jwtUtil.generateToken(any(), any(), anyOrNull())).thenReturn("tok")

        authService.loginOrRegister(userId, "Bob", "https://new.png")

        assertThat(existingUser.avatarUrl).isEqualTo("https://new.png")
    }
}
