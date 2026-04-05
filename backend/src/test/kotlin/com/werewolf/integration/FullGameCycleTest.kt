package com.werewolf.integration

import com.werewolf.game.night.NightOrchestrator
import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.GamePhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
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
class FullGameCycleTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightOrchestrator: NightOrchestrator

    companion object {
        const val START_URL = "/api/game/start"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val ACTION_URL = "/api/game/action"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Login and return (token, userId). */
    private fun login(nickname: String): Pair<String, String> {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body["user"] as Map<*, *>)["userId"] as String
        return token to userId
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun action(token: String, gameId: Int, actionType: String, targetUserId: String? = null) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                mapOf("gameId" to gameId, "actionType" to actionType, "targetUserId" to targetUserId),
                headers(token)
            ),
            Map::class.java
        )

    /**
     * Create a 4-player room, join all players, claim seats, go ready.
     * Returns (hostToken, roomId, userId→token map for all players).
     */
    private fun setupRoom(
        prefix: String,
        extraConfig: Map<String, Any?> = emptyMap(),
    ): Triple<String, Int, Map<String, String>> {
        val (hostToken, hostId) = login("${prefix}H")
        val guestPairs = (1..3).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to (mapOf(
                        FIELD_TOTAL_PLAYERS to 4,
                        "roles" to emptyList<String>(),
                        "hasSheriff" to false,
                    ) + extraConfig)
                ),
                headers(hostToken)
            ),
            Map::class.java
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        // Host claims seat 0
        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)), Map::class.java)

        // Guests join, claim seats, and go ready
        guestPairs.forEachIndexed { idx, (guestToken, _) ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(guestToken)), Map::class.java)
        }

        val tokenByUserId = buildMap<String, String> {
            put(hostId, hostToken)
            guestPairs.forEach { (t, id) -> put(id, t) }
        }

        return Triple(hostToken, roomId, tokenByUserId)
    }

    /** Find the token of the wolf among all players using game state API. */
    private fun findRoleToken(gameId: Int, tokenByUserId: Map<String, String>, role: PlayerRole): String {
        return tokenByUserId.entries.first { (_, token) ->
            @Suppress("UNCHECKED_CAST")
            val state = restTemplate.exchange(
                "/api/game/$gameId/state",
                org.springframework.http.HttpMethod.GET,
                HttpEntity<Nothing>(headers(token)),
                Map::class.java
            ).body
            state?.get("myRole") as? String == role.name
        }.value
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * CLASSIC mode: 4 players (2 wolves + 2 villagers), no sheriff, no special roles.
     * Night 1: wolf kills one villager → 2 wolves vs 1 villager → WEREWOLF wins.
     */
    @Test
    fun `CLASSIC - game reaches GAME_OVER with WEREWOLF winner after first night kill`() {
        val (hostToken, roomId, tokenByUserId) = setupRoom("C1")

        // Start game
        val startResp = restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        // All players confirm role
        tokenByUserId.values.forEach { token ->
            assertThat(action(token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        // Host starts night → WAITING, then advance past it
        assertThat(action(hostToken, gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.OK)
        nightOrchestrator.advanceFromWaiting(gameId)

        // Find a wolf's token and a villager's userId
        val wolfToken = findRoleToken(gameId, tokenByUserId, PlayerRole.WEREWOLF)
        val villagerTarget = gamePlayerRepository.findByGameId(gameId).first { it.role != PlayerRole.WEREWOLF }

        // Wolf kills the villager — triggers night resolution
        assertThat(action(wolfToken, gameId, "WOLF_KILL", villagerTarget.userId).statusCode).isEqualTo(HttpStatus.OK)

        // With 4 players: 2 wolves vs 1 remaining villager → CLASSIC wolves(2) >= others(1) → WEREWOLF wins
        val finalGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(finalGame.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isEqualTo(WinnerSide.WEREWOLF)
    }

    /**
     * HARD_MODE: wolves must eliminate ALL non-wolves.
     * 4 players (2 wolves + 2 villagers). Night 1: 1 villager killed → 1 villager still alive.
     * HARD_MODE: game should NOT end — proceeds to DAY.
     */
    @Test
    fun `HARD_MODE - game does NOT end when one villager still alive`() {
        val (hostToken, roomId, tokenByUserId) = setupRoom("HM1", extraConfig = mapOf("winCondition" to "HARD_MODE"))

        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)

        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        tokenByUserId.values.forEach { token -> action(token, gameId, "CONFIRM_ROLE") }
        action(hostToken, gameId, "START_NIGHT")
        nightOrchestrator.advanceFromWaiting(gameId)

        val wolfToken = findRoleToken(gameId, tokenByUserId, PlayerRole.WEREWOLF)
        val villagerTarget = gamePlayerRepository.findByGameId(gameId).first { it.role != PlayerRole.WEREWOLF }

        action(wolfToken, gameId, "WOLF_KILL", villagerTarget.userId)

        // In HARD_MODE: 2 wolves vs 1 villager — not all non-wolves eliminated → no win yet
        val finalGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(finalGame.phase).isEqualTo(GamePhase.DAY)
        assertThat(finalGame.winner).isNull()
    }
}
