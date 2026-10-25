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
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.*

@AutoConfigureTestRestTemplate
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

    // ── Look-alike nicknames must map to one identity ─────────────────────────
    // A returning guest who retypes "the same" nickname on another keyboard
    // (Chinese IME full-width space, double space, paste with a zero-width char)
    // used to get a different userId and could not rejoin a running game.

    @Suppress("UNCHECKED_CAST")
    private fun loginUser(nickname: String): Map<String, Any?> {
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(resp.statusCode).`as`("login %s", nickname).isEqualTo(HttpStatus.OK)
        return resp.body!![FIELD_USER] as Map<String, Any?>
    }

    private fun userIdOf(nickname: String) = loginUser(nickname)[FIELD_USER_ID] as String

    @Test
    fun `any run of unicode whitespace between words maps to one hyphen`() {
        val variants = listOf(
            "Ray Two", "Ray  Two", "Ray\tTwo", "Ray　Two", "Ray Two", "Ray Two", "　Ray Two　",
        )
        assertThat(variants.map(::userIdOf).toSet()).containsExactly("guest:ray-two")
        assertThat(loginUser("Ray　　Two")[FIELD_NICKNAME]).isEqualTo("Ray Two")
    }

    @Test
    fun `full-width letters and punctuation match their ascii form`() {
        assertThat(userIdOf("Ｆ！")).isEqualTo(userIdOf("F!")).isEqualTo("guest:f!")
        assertThat(userIdOf("Ｒay")).isEqualTo("guest:ray")
    }

    @Test
    fun `composed and decomposed accents map to one identity`() {
        assertThat(userIdOf("José")).isEqualTo(userIdOf("José"))
    }

    @Test
    fun `zero-width and control characters are dropped from the id and the display name`() {
        val user = loginUser("Bob​‍﻿")
        assertThat(user[FIELD_USER_ID]).isEqualTo("guest:bob")
        assertThat(user[FIELD_NICKNAME]).isEqualTo("Bob")
    }

    @Test
    fun `joiners inside emoji and scripts stay in the display name, but not in the id`() {
        val family = "👨‍👩‍👧"
        val user = loginUser(family)
        assertThat(user[FIELD_NICKNAME]).isEqualTo(family)
        assertThat(user[FIELD_USER_ID]).isEqualTo("guest:👨👩👧")
    }

    @Test
    fun `emoji with and without a variation selector map to one identity`() {
        assertThat(userIdOf("❤️")).isEqualTo(userIdOf("❤")).isEqualTo("guest:❤")
    }

    @Test
    fun `chinese and emoji nicknames are kept as they are`() {
        assertThat(userIdOf("小花")).isEqualTo("guest:小花")
        assertThat(userIdOf("🐺 狼人")).isEqualTo("guest:🐺-狼人")
    }

    @Test
    fun `a nickname whose normalised id would overflow user_id is rejected, not a 500`() {
        // NFKC expands U+FDFA to 18 chars: 10 of them = 180 > VARCHAR(128).
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to "ﷺ".repeat(10)), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a nickname of only invisible characters is rejected`() {
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to "​​"), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(userRepository.findById("guest:​​")).isEmpty
    }
}
