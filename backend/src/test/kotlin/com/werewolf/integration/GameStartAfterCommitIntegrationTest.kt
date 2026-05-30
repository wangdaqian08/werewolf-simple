package com.werewolf.integration

import com.werewolf.game.action.GameActionResult
import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PlayerRole
import com.werewolf.service.GameService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Regression for the prod race that left the GameView blank for some clients
 * (game=10 on 2026-04-29): GAME_STARTED was broadcast inside startGame's
 * @Transactional, so a fast recipient could call getGameState in a separate
 * read tx before the games row had committed and got "Game not found".
 *
 * Fix: defer the three broadcasts (GAME_STARTED, per-player RoleAssigned,
 * PhaseChanged → ROLE_REVEAL) to afterCommit. This test runs startGame inside
 * a TransactionTemplate and asserts the spy sees nothing until the outer tx
 * commits, then sees all three.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameStartAfterCommitIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameService: GameService
    @Autowired lateinit var txManager: PlatformTransactionManager

    @SpyBean lateinit var stompPublisher: StompPublisher

    private val txTemplate by lazy { TransactionTemplate(txManager) }

    companion object {
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val TOTAL_PLAYERS = 4
        private val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private data class Login(val userId: String, val token: String)

    @Suppress("UNCHECKED_CAST")
    private fun login(nickname: String): Login {
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body!!
        val user = body[FIELD_USER] as Map<String, Any?>
        return Login(userId = user[FIELD_USER_ID] as String, token = body[FIELD_TOKEN] as String)
    }

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupReadyRoom(prefix: String): Pair<Login, Int> {
        val host = login("${prefix}Host")
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES))
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(host.token)), Map::class.java
        ).body!! as Map<String, Any?>
        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), authHeaders(host.token)), Map::class.java)

        repeat(TOTAL_PLAYERS - 1) { i ->
            val g = login("${prefix}Guest$i")
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), authHeaders(g.token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to i + 1, "roomId" to roomId), authHeaders(g.token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(g.token)), Map::class.java)
        }
        return host to roomId
    }

    @Test
    fun `startGame defers STOMP broadcasts until the transaction commits`() {
        val (host, roomId) = setupReadyRoom("AfterCommit")
        // Drop the ROOM_UPDATE noise from seat / ready so the assertions below only see
        // what startGame produces.
        Mockito.clearInvocations(stompPublisher)

        // Run startGame inside a TransactionTemplate so the inner @Transactional joins
        // the outer tx. Snapshot the spy's state right after startGame returns but
        // BEFORE the outer tx commits — none of the deferred broadcasts should be
        // visible yet.
        var roomCallsBeforeCommit = -1
        var gameCallsBeforeCommit = -1
        var privateCallsBeforeCommit = -1

        txTemplate.execute {
            val result = gameService.startGame(host.userId, roomId)
            assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

            val invocations = Mockito.mockingDetails(stompPublisher).invocations
            roomCallsBeforeCommit = invocations.count { it.method.name == "broadcastRoom" }
            gameCallsBeforeCommit = invocations.count { it.method.name == "broadcastGame" }
            privateCallsBeforeCommit = invocations.count { it.method.name == "sendPrivate" }
        }

        assertThat(roomCallsBeforeCommit).isZero
        assertThat(gameCallsBeforeCommit).isZero
        assertThat(privateCallsBeforeCommit).isZero

        // After commit: GAME_STARTED → room, PhaseChanged → game, RoleAssigned → each player.
        val roomMsg = argumentCaptor<Map<*, *>>()
        verify(stompPublisher).broadcastRoom(eq(roomId), roomMsg.capture())
        assertThat(roomMsg.firstValue["type"]).isEqualTo("GAME_STARTED")

        verify(stompPublisher, atLeastOnce()).broadcastGame(any(), any())
        verify(stompPublisher, times(TOTAL_PLAYERS)).sendPrivate(any(), any())
    }
}
