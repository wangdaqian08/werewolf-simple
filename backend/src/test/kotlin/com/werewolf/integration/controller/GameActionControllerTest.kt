package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.FIELD_ERROR
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.LOGIN_URL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameActionControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    companion object {
        const val ACTION_URL = "/api/game/action"
    }

    private fun postAction(nickname: String, body: Map<String, Any?>) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(body, HttpHeaders().also {
                it.setBearerAuth(login(nickname))
                it.contentType = MediaType.APPLICATION_JSON
            }),
            Map::class.java,
        )

    private fun login(nickname: String): String {
        val response = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!![FIELD_TOKEN] as String
    }

    @Test
    fun `action without a gameId is rejected with 400, not a server error`() {
        val response = postAction("NoGameIdPlayer", mapOf("actionType" to "CONFIRM_ROLE"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).isEqualTo(mapOf("success" to false, FIELD_ERROR to "Game 0 not found"))
    }

    @Test
    fun `action on a game that does not exist is rejected with 400`() {
        val response = postAction("UnknownGamePlayer", mapOf("gameId" to 99_999, "actionType" to "CONFIRM_ROLE"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).isEqualTo(mapOf("success" to false, FIELD_ERROR to "Game 99999 not found"))
    }
}
