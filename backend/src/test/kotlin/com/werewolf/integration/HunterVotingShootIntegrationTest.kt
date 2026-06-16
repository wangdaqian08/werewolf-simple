package com.werewolf.integration

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
import com.werewolf.model.VotingSubPhase
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

/**
 * Integration coverage for the VOTING-path hunter shoot (DAY_VOTING +
 * VotingSubPhase.HUNTER_SHOOT) — a hunter voted out at day fires (or passes),
 * and the day must then PAUSE on VOTE_RESULT so the host controls the night
 * transition (giving the hunter's victim time for last words), exactly like
 * every other voting-elimination day-death.
 *
 * Counterpart to HunterNightDeathShootIntegrationTest (the night-death path).
 * The voting flow itself isn't under test, so we park the game directly at
 * "Day 1, hunter just voted out, awaiting the shot" with a fixed role layout
 * (1 werewolf, rest village-side) so no shot trips a win condition except the
 * dedicated game-ending case, then drive the real HTTP endpoints.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HunterVotingShootIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository

    companion object {
        const val START_URL = "/api/game/start"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val ACTION_URL = "/api/game/action"
    }

    private data class TestPlayer(val token: String, val userId: String)

    private fun login(nickname: String): TestPlayer {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body["user"] as Map<*, *>)["userId"] as String
        return TestPlayer(token, userId)
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

    private data class Room6(
        val host: TestPlayer,
        val g1: TestPlayer,
        val g2: TestPlayer,
        val g3: TestPlayer,
        val g4: TestPlayer,
        val g5: TestPlayer,
        val roomId: Int,
    ) {
        val all get() = listOf(host, g1, g2, g3, g4, g5)
    }

    private fun setupRoom(prefix: String): Room6 {
        val host = login("${prefix}H")
        val g1 = login("${prefix}G1")
        val g2 = login("${prefix}G2")
        val g3 = login("${prefix}G3")
        val g4 = login("${prefix}G4")
        val g5 = login("${prefix}G5")

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        FIELD_TOTAL_PLAYERS to 6,
                        "roles" to listOf("WEREWOLF", "SEER", "HUNTER", "VILLAGER", "VILLAGER"),
                        "hasSheriff" to false,
                    ),
                ),
                headers(host.token),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(host.token)),
            Map::class.java,
        )

        listOf(g1, g2, g3, g4, g5).forEachIndexed { idx, player ->
            restTemplate.postForEntity(
                JOIN_ROOM_URL,
                HttpEntity(mapOf("roomCode" to roomCode), headers(player.token)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                SEAT_URL,
                HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(player.token)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                READY_URL,
                HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(player.token)),
                Map::class.java,
            )
        }

        return Room6(host, g1, g2, g3, g4, g5, roomId)
    }

    /** Overwrite a player's (val) role in the DB — the night/voting flow isn't under test. */
    private fun forceRole(gameId: Int, userId: String, role: PlayerRole) {
        val gp = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow()
        val f = com.werewolf.model.GamePlayer::class.java.getDeclaredField("role")
        f.isAccessible = true
        f.set(gp, role)
        gamePlayerRepository.save(gp)
    }

    /**
     * Start the game, confirm roles, force a fixed layout (host=VILLAGER,
     * g1=HUNTER, g2=WEREWOLF, g3=SEER, g4/g5=VILLAGER), then park it at
     * "Day 1 — hunter g1 just voted out, awaiting the shot": DAY_VOTING /
     * HUNTER_SHOOT with g1 dead. Returns the gameId.
     */
    private fun openDay1HunterShoot(room: Room6): Int {
        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to room.roomId), headers(room.host.token)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == room.roomId }
        val gameId = game.gameId!!

        room.all.forEach { player ->
            assertThat(action(player.token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        forceRole(gameId, room.host.userId, PlayerRole.VILLAGER)
        forceRole(gameId, room.g1.userId, PlayerRole.HUNTER)
        forceRole(gameId, room.g2.userId, PlayerRole.WEREWOLF)
        forceRole(gameId, room.g3.userId, PlayerRole.SEER)
        forceRole(gameId, room.g4.userId, PlayerRole.VILLAGER)
        forceRole(gameId, room.g5.userId, PlayerRole.VILLAGER)

        // Hunter voted out → mirrors collectEliminationEvents: dead player,
        // sub-phase parked at HUNTER_SHOOT awaiting the shot.
        gamePlayerRepository.findByGameIdAndUserId(gameId, room.g1.userId).orElseThrow().also {
            it.alive = false
            gamePlayerRepository.save(it)
        }
        game.phase = GamePhase.DAY_VOTING
        game.subPhase = VotingSubPhase.HUNTER_SHOOT.name
        game.dayNumber = 1
        gameRepository.save(game)

        return gameId
    }

    private fun subPhase(gameId: Int) = gameRepository.findById(gameId).orElseThrow().subPhase
    private fun phase(gameId: Int) = gameRepository.findById(gameId).orElseThrow().phase
    private fun dayNumber(gameId: Int) = gameRepository.findById(gameId).orElseThrow().dayNumber
    private fun alive(gameId: Int, userId: String) =
        gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow().alive

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `voted-out hunter shoots a villager - day pauses on VOTE_RESULT, no auto-night`() {
        val room = setupRoom("HVS1")
        val gameId = openDay1HunterShoot(room)

        // Hunter (g1) shoots g4 (a villager — keeps the wolf alive, no win).
        val resp = action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        // The fix: pause for the victim's last words instead of jumping to night.
        assertThat(phase(gameId)).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(dayNumber(gameId)).isEqualTo(1) // still day 1 — night not started
    }

    @Test
    fun `voted-out hunter shoot then host VOTING_CONTINUE advances to night`() {
        val room = setupRoom("HVS2")
        val gameId = openDay1HunterShoot(room)

        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)

        // The host clicks "进入夜晚" → VOTING_CONTINUE drives the night transition.
        assertThat(action(room.host.token, gameId, "VOTING_CONTINUE").statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(phase(gameId)).isEqualTo(GamePhase.NIGHT)
        assertThat(dayNumber(gameId)).isEqualTo(2)
    }

    @Test
    fun `voted-out hunter passes - day still pauses on VOTE_RESULT, then host advances to night`() {
        val room = setupRoom("HVS3")
        val gameId = openDay1HunterShoot(room)

        // Hunter passes (no shot) — still a day-death, so still a host-controlled pause.
        assertThat(action(room.g1.token, gameId, "HUNTER_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(phase(gameId)).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(dayNumber(gameId)).isEqualTo(1)
        // g4 untouched (no shot).
        assertThat(alive(gameId, room.g4.userId)).isTrue()

        assertThat(action(room.host.token, gameId, "VOTING_CONTINUE").statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(phase(gameId)).isEqualTo(GamePhase.NIGHT)
    }
}
