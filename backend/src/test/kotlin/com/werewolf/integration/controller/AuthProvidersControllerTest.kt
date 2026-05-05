package com.werewolf.integration.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

private const val PROVIDERS_URL = "/api/auth/providers"

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = ["app.oauth.google.client-id=test-google-client-id"])
class AuthProvidersControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET providers returns google true wechat false guest true when only google is configured`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["google"]).isEqualTo(true)
        assertThat(response.body!!["wechat"]).isEqualTo(false)
        assertThat(response.body!!["guest"]).isEqualTo(true)
    }

    @Test
    fun `GET providers does not require authorization`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }
}

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "app.oauth.google.client-id=test-google-client-id",
    "app.oauth.wechat.app-id=test-wechat-app-id",
])
class AuthProvidersControllerWithWeChatTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET providers returns wechat true when wechat app-id is configured`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["google"]).isEqualTo(true)
        assertThat(response.body!!["wechat"]).isEqualTo(true)
        assertThat(response.body!!["guest"]).isEqualTo(true)
    }
}

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthProvidersControllerNoOAuthTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET providers returns google false wechat false guest true when no env vars are set`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["google"]).isEqualTo(false)
        assertThat(response.body!!["wechat"]).isEqualTo(false)
        assertThat(response.body!!["guest"]).isEqualTo(true)
    }
}
