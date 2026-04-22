package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.*

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserLoginControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var userRepository: UserRepository

    @Test
    fun `POST user-login with valid nickname returns 200 with token and user`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "Alice"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!![FIELD_TOKEN]).isNotNull()

        @Suppress("UNCHECKED_CAST")
        val user = response.body!![FIELD_USER] as Map<String, Any?>
        assertThat(user[FIELD_NICKNAME]).isEqualTo("Alice")
        assertThat(user[FIELD_USER_ID] as String).startsWith("guest:")
    }

    @Test
    fun `POST user-login saves user to database`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "Bob"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val user = response.body!![FIELD_USER] as Map<String, Any?>
        val userId = user[FIELD_USER_ID] as String

        val saved = userRepository.findById(userId)
        assertThat(saved).isPresent
        assertThat(saved.get().nickname).isEqualTo("Bob")
    }

    @Test
    fun `POST user-login trims whitespace from nickname`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "  Carol  "),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val user = response.body!![FIELD_USER] as Map<String, Any?>
        assertThat(user[FIELD_NICKNAME]).isEqualTo("Carol")
    }

    @Test
    fun `POST user-login with blank nickname returns 400`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to ""),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST user-login with whitespace-only nickname returns 400`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "   "),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST user-login with nickname exceeding 50 chars returns 400`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "A".repeat(51)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `login returns a valid JWT containing the userId as subject`() {
        val response = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "Eve"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val token = response.body!![FIELD_TOKEN] as String

        val parts = token.split(".")
        assertThat(parts).hasSize(3)

        val raw = parts[1]
        val payload = String(Base64.getUrlDecoder().decode(raw + "=".repeat((4 - raw.length % 4) % 4)))
        assertThat(payload).contains("\"sub\":\"guest:")
    }

    @Test
    fun `repeat login with same nickname returns the same userId (idempotent rejoin)`() {
        val r1 = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to "Dave"), Map::class.java)
        val r2 = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to "Dave"), Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val u1 = (r1.body!![FIELD_USER] as Map<String, Any?>)[FIELD_USER_ID] as String
        @Suppress("UNCHECKED_CAST")
        val u2 = (r2.body!![FIELD_USER] as Map<String, Any?>)[FIELD_USER_ID] as String

        assertThat(u1).isEqualTo(u2)
        assertThat(u1).isEqualTo("guest:dave")
    }

    @Test
    fun `login normalises nickname case and whitespace into a stable userId`() {
        val cases = listOf("Frank", "frank", "FRANK", "  Frank  ", "fRaNk")
        val userIds = cases.map { input ->
            val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to input), Map::class.java)
            @Suppress("UNCHECKED_CAST")
            (resp.body!![FIELD_USER] as Map<String, Any?>)[FIELD_USER_ID] as String
        }

        assertThat(userIds.toSet()).hasSize(1)
        assertThat(userIds.first()).isEqualTo("guest:frank")
    }

    @Test
    fun `login with spaced nickname produces hyphenated userId`() {
        val resp = restTemplate.postForEntity(
            LOGIN_URL,
            mapOf(FIELD_NICKNAME to "Player One"),
            Map::class.java,
        )

        @Suppress("UNCHECKED_CAST")
        val user = resp.body!![FIELD_USER] as Map<String, Any?>
        assertThat(user[FIELD_USER_ID]).isEqualTo("guest:player-one")
        assertThat(user[FIELD_NICKNAME]).isEqualTo("Player One")
    }
}
