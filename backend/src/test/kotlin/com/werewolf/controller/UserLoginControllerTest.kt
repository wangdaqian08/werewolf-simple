package com.werewolf.controller

import com.werewolf.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserLoginControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var userRepository: UserRepository

    @Test
    fun `POST user-login with valid nickname returns 200 with token and user`() {
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to "Alice"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["token"]).isNotNull()

        @Suppress("UNCHECKED_CAST")
        val user = response.body!!["user"] as Map<String, Any?>
        assertThat(user["nickname"]).isEqualTo("Alice")
        assertThat(user["userId"] as String).startsWith("guest:")
    }

    @Test
    fun `POST user-login saves user to database`() {
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to "Bob"),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val user = response.body!!["user"] as Map<String, Any?>
        val userId = user["userId"] as String

        val saved = userRepository.findById(userId)
        assertThat(saved).isPresent
        assertThat(saved.get().nickname).isEqualTo("Bob")
    }

    @Test
    fun `POST user-login trims whitespace from nickname`() {
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to "  Carol  "),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val user = response.body!!["user"] as Map<String, Any?>
        assertThat(user["nickname"]).isEqualTo("Carol")
    }

    @Test
    fun `POST user-login with blank nickname returns 400`() {
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to ""),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST user-login with whitespace-only nickname returns 400`() {
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to "   "),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `POST user-login with nickname exceeding 50 chars returns 400`() {
        val longNickname = "A".repeat(51)
        val response = restTemplate.postForEntity(
            "/api/user/login",
            mapOf("nickname" to longNickname),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `each login call creates a distinct guest user`() {
        val r1 = restTemplate.postForEntity("/api/user/login", mapOf("nickname" to "Dave"), Map::class.java)
        val r2 = restTemplate.postForEntity("/api/user/login", mapOf("nickname" to "Dave"), Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val u1 = (r1.body!!["user"] as Map<String, Any?>)["userId"] as String
        @Suppress("UNCHECKED_CAST")
        val u2 = (r2.body!!["user"] as Map<String, Any?>)["userId"] as String

        assertThat(u1).isNotEqualTo(u2)
    }
}
