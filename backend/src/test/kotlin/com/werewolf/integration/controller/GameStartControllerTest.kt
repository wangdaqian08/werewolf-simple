package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.DEFAULT_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ERROR
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.RoomPlayerRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameStartControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository

    companion object {
        const val START_URL = "/api/game/start"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        val DEFAULT_ROLES = listOf(PlayerRole.SEER, PlayerRole.WITCH, PlayerRole.HUNTER)
    }

    private fun login(nickname: String): String {
        val response = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!![FIELD_TOKEN] as String
    }

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    /**
     * Creates a room and joins [guestCount] guests. All guests claim a seat and go ready.
     * Returns (hostToken, roomId, list of guestTokens).
     */
    @Suppress("UNCHECKED_CAST")
    private fun setupReadyRoom(prefix: String, guestCount: Int): Triple<String, Int, List<String>> {
        val hostToken = login("${prefix}Host")
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES))
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(hostToken)), Map::class.java
        ).body!! as Map<String, Any?>

        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        val guestTokens = (1..guestCount).map { i ->
            val token = login("${prefix}Guest$i")
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), authHeaders(token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to i, "roomId" to roomId), authHeaders(token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(token)), Map::class.java)
            token
        }

        return Triple(hostToken, roomId, guestTokens)
    }

    // ── startGame ─────────────────────────────────────────────────────────────

    @Test
    fun `host starts game when all guests ready - roles assigned`() {
        val (hostToken, roomId, _) = setupReadyRoom("Start1", guestCount = 3)

        val response = restTemplate.postForEntity(
            START_URL,
            HttpEntity(mapOf("roomId" to roomId), authHeaders(hostToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val gamePlayers = gamePlayerRepository.findAll()
            .filter { gp -> roomPlayerRepository.findByRoomId(roomId).any { rp -> rp.userId == gp.userId } }
        assertThat(gamePlayers).isNotEmpty
    }

    @Test
    fun `start game fails when a guest is NOT_READY`() {
        // Create a room but do NOT set any guest ready
        val hostToken = login("Start2Host")
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to DEFAULT_TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES))
        @Suppress("UNCHECKED_CAST")
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(hostToken)), Map::class.java
        ).body!! as Map<String, Any?>
        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        // Join guests but don't ready them
        repeat(3) { i ->
            val guestToken = login("Start2Guest$i")
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), authHeaders(guestToken)), Map::class.java)
        }

        val response = restTemplate.postForEntity(
            START_URL,
            HttpEntity(mapOf("roomId" to roomId), authHeaders(hostToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        assertThat(response.body!![FIELD_ERROR] as String).contains("ready")
    }

    @Test
    fun `non-host cannot start game`() {
        val (_, roomId, guestTokens) = setupReadyRoom("Start3", guestCount = 3)

        val response = restTemplate.postForEntity(
            START_URL,
            HttpEntity(mapOf("roomId" to roomId), authHeaders(guestTokens.first())),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        assertThat(response.body!![FIELD_ERROR] as String).contains("host")
    }

    @Test
    fun `start game without token is rejected`() {
        val response = restTemplate.postForEntity(
            START_URL,
            mapOf("roomId" to 1),
            Map::class.java,
        )

        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
