package com.werewolf.integration

import com.werewolf.game.DomainEvent
import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.GamePhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.ReadyStatus
import com.werewolf.model.RoomStatus
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.service.OrphanedGameRecovery
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
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

/**
 * Regression for the prod incident on 2026-04-29 (game=10): a backend
 * redeploy mid-game wiped the in-memory NightOrchestrator state, so any
 * subsequent WOLF_KILL hit "No pending action — queuing signal for next
 * await", queued forever, and the game stuck.
 *
 * The recovery contract: on startup, every in-flight game is force-ended
 * (forfeit), the room reopens to WAITING, every non-host player resets to
 * NOT_READY, and a GameOver event is broadcast so any client still on
 * GameView auto-routes to the Result screen.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrphanedGameRecoveryIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var orphanedGameRecovery: OrphanedGameRecovery

    @SpyBean lateinit var stompPublisher: StompPublisher

    companion object {
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val START_URL = "/api/game/start"
        private const val TOTAL_PLAYERS = 4
        private val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private fun login(nickname: String): String {
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return resp.body!![FIELD_TOKEN] as String
    }

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupRoomAndStartGame(prefix: String): Triple<String, Int, Int> {
        val hostToken = login("${prefix}Host")
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES))
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(hostToken)), Map::class.java
        ).body!! as Map<String, Any?>
        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), authHeaders(hostToken)), Map::class.java)
        repeat(TOTAL_PLAYERS - 1) { i ->
            val token = login("${prefix}Guest$i")
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), authHeaders(token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to i + 1, "roomId" to roomId), authHeaders(token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(token)), Map::class.java)
        }

        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to roomId), authHeaders(hostToken)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId && it.endedAt == null }
        return Triple(hostToken, roomId, game.gameId!!)
    }

    @Test
    fun `cancelInFlightGames force-ends in-flight games and reopens their rooms`() {
        val (_, roomId, gameId) = setupRoomAndStartGame("Orphan1")

        // Sanity: game is currently in-flight (just created via /api/game/start).
        assertThat(gameRepository.findById(gameId).orElseThrow().endedAt).isNull()
        assertThat(roomRepository.findById(roomId).orElseThrow().status).isEqualTo(RoomStatus.IN_GAME)

        // Drop the broadcasts that came from setup so we can assert exactly the
        // one this method should produce.
        org.mockito.Mockito.clearInvocations(stompPublisher)

        orphanedGameRecovery.cancelInFlightGames()

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.endedAt).isNotNull
        assertThat(game.winner).isNull() // forfeit — no winner

        val room = roomRepository.findById(roomId).orElseThrow()
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)

        val players = roomPlayerRepository.findByRoomId(roomId)
        assertThat(players).hasSize(TOTAL_PLAYERS)
        // Every non-host seat is reset to NOT_READY.
        assertThat(players.filter { !it.host }).allMatch { it.status == ReadyStatus.NOT_READY }

        // Exactly one GameOver event went out on this game's topic.
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val event = captor.firstValue as DomainEvent.GameOver
        assertThat(event.gameId).isEqualTo(gameId)
        assertThat(event.winner).isNull()
    }

    @Test
    fun `cancelInFlightGames is idempotent — already-ended games are not re-cancelled`() {
        val (_, _, gameId) = setupRoomAndStartGame("Orphan2")

        // First pass: cancels.
        orphanedGameRecovery.cancelInFlightGames()
        val firstEndedAt = gameRepository.findById(gameId).orElseThrow().endedAt
        assertThat(firstEndedAt).isNotNull

        org.mockito.Mockito.clearInvocations(stompPublisher)

        // Second pass: nothing to do — endedAt query excludes this row.
        orphanedGameRecovery.cancelInFlightGames()

        val secondEndedAt = gameRepository.findById(gameId).orElseThrow().endedAt
        assertThat(secondEndedAt).isEqualTo(firstEndedAt)
        verify(stompPublisher, org.mockito.kotlin.never()).broadcastGame(eq(gameId), org.mockito.kotlin.any())
    }
}
