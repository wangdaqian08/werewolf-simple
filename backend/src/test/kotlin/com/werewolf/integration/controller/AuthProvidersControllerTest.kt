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
    fun `GET providers returns google config wechat null guest true when only google is configured`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val google = response.body!!["google"] as Map<String, Any?>?
        assertThat(google).isNotNull
        assertThat(google!!["clientId"]).isEqualTo("test-google-client-id")
        assertThat(response.body!!["wechat"]).isNull()
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
    fun `GET providers returns wechat config when wechat app-id is configured`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val wechat = response.body!!["wechat"] as Map<String, Any?>?
        assertThat(wechat).isNotNull
        assertThat(wechat!!["appId"]).isEqualTo("test-wechat-app-id")
        assertThat(response.body!!["guest"]).isEqualTo(true)
    }
}

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthProvidersControllerNoOAuthTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET providers returns google null wechat null guest true when no env vars are set`() {
        val response = restTemplate.getForEntity(PROVIDERS_URL, Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["google"]).isNull()
        assertThat(response.body!!["wechat"]).isNull()
        assertThat(response.body!!["guest"]).isEqualTo(true)
    }
}
