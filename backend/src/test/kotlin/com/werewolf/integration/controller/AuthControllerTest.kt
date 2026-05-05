package com.werewolf.integration.controller

import com.werewolf.auth.GoogleOAuthService
import com.werewolf.auth.WeChatOAuthService
import com.werewolf.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.*

private const val GOOGLE_AUTH_URL = "/api/auth/google"
private const val WECHAT_AUTH_URL = "/api/auth/wechat"

/**
 * Integration tests for the OAuth login endpoints. Both OAuth services are
 * @MockBean'd so we never reach the real Google / WeChat APIs — we exercise
 * AuthController + AuthService.loginOrRegister + JWT issuance + DB persistence.
 *
 * NOTE: Spring Boot 3.4+ deprecates @MockBean in favor of @MockitoBean.
 * Project is on 3.2.5; revisit on the next major upgrade.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var userRepository: UserRepository

    @MockBean lateinit var googleOAuthService: GoogleOAuthService
    @MockBean lateinit var weChatOAuthService: WeChatOAuthService

    @Test
    fun `POST auth-google with valid code returns 200 with token and user fields`() {
        whenever(googleOAuthService.exchangeCode("good-google-code")).thenReturn(
            GoogleOAuthService.GoogleUserProfile(
                userId = "google:sub-abc",
                nickname = "Daniel Wang",
                avatarUrl = "https://lh3.googleusercontent.com/a/x",
            ),
        )

        val response = restTemplate.postForEntity(
            GOOGLE_AUTH_URL,
            mapOf("code" to "good-google-code"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["token"]).isNotNull()

        @Suppress("UNCHECKED_CAST")
        val user = response.body!!["user"] as Map<String, Any?>
        assertThat(user["userId"]).isEqualTo("google:sub-abc")
        assertThat(user["nickname"]).isEqualTo("Daniel Wang")
        assertThat(user["avatarUrl"]).isEqualTo("https://lh3.googleusercontent.com/a/x")
    }

    @Test
    fun `POST auth-google persists user with avatarUrl on first login`() {
        whenever(googleOAuthService.exchangeCode("first-login")).thenReturn(
            GoogleOAuthService.GoogleUserProfile(
                userId = "google:first-time-user",
                nickname = "First Timer",
                avatarUrl = "https://example.com/first.png",
            ),
        )

        restTemplate.postForEntity(GOOGLE_AUTH_URL, mapOf("code" to "first-login"), Map::class.java)

        val saved = userRepository.findById("google:first-time-user")
        assertThat(saved).isPresent
        assertThat(saved.get().nickname).isEqualTo("First Timer")
        assertThat(saved.get().avatarUrl).isEqualTo("https://example.com/first.png")
    }

    @Test
    fun `POST auth-google updates avatarUrl on second login when provider returns a new url`() {
        // First login.
        whenever(googleOAuthService.exchangeCode("login-1")).thenReturn(
            GoogleOAuthService.GoogleUserProfile(
                userId = "google:refresher",
                nickname = "Old Name",
                avatarUrl = "https://example.com/old.png",
            ),
        )
        restTemplate.postForEntity(GOOGLE_AUTH_URL, mapOf("code" to "login-1"), Map::class.java)

        // Second login — provider returns updated nickname + avatar.
        whenever(googleOAuthService.exchangeCode("login-2")).thenReturn(
            GoogleOAuthService.GoogleUserProfile(
                userId = "google:refresher",
                nickname = "New Name",
                avatarUrl = "https://example.com/new.png",
            ),
        )
        restTemplate.postForEntity(GOOGLE_AUTH_URL, mapOf("code" to "login-2"), Map::class.java)

        val saved = userRepository.findById("google:refresher").get()
        assertThat(saved.nickname).isEqualTo("New Name")
        assertThat(saved.avatarUrl).isEqualTo("https://example.com/new.png")
    }

    @Test
    fun `POST auth-google with empty code returns 400`() {
        val response = restTemplate.postForEntity(
            GOOGLE_AUTH_URL,
            mapOf("code" to ""),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST auth-google with missing code returns 400`() {
        val response = restTemplate.postForEntity(
            GOOGLE_AUTH_URL,
            emptyMap<String, String>(),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST auth-google returns a JWT containing the userId as subject`() {
        whenever(googleOAuthService.exchangeCode("jwt-check")).thenReturn(
            GoogleOAuthService.GoogleUserProfile(
                userId = "google:jwt-user",
                nickname = "JWT User",
                avatarUrl = null,
            ),
        )

        val response = restTemplate.postForEntity(
            GOOGLE_AUTH_URL,
            mapOf("code" to "jwt-check"),
            Map::class.java,
        )

        val token = response.body!!["token"] as String
        val parts = token.split(".")
        assertThat(parts).hasSize(3)
        val payload = String(Base64.getUrlDecoder().decode(parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)))
        assertThat(payload).contains("\"sub\":\"google:jwt-user\"")
    }

    @Test
    fun `POST auth-wechat with valid code returns 200 with user mapped to wechat openid`() {
        whenever(weChatOAuthService.exchangeCode("good-wechat-code")).thenReturn(
            WeChatOAuthService.WeChatUserProfile(
                userId = "wechat:openid-xyz",
                nickname = "微信用户",
                avatarUrl = "https://thirdwx.qlogo.cn/mmopen/x.jpg",
            ),
        )

        val response = restTemplate.postForEntity(
            WECHAT_AUTH_URL,
            mapOf("code" to "good-wechat-code"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val user = response.body!!["user"] as Map<String, Any?>
        assertThat(user["userId"]).isEqualTo("wechat:openid-xyz")
        assertThat(user["nickname"]).isEqualTo("微信用户")
        assertThat(user["avatarUrl"]).isEqualTo("https://thirdwx.qlogo.cn/mmopen/x.jpg")
    }

    @Test
    fun `POST auth-wechat with empty code returns 400`() {
        val response = restTemplate.postForEntity(
            WECHAT_AUTH_URL,
            mapOf("code" to ""),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
