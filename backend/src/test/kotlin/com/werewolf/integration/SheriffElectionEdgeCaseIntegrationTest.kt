package com.werewolf.integration

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.ElectionSubPhase
import com.werewolf.model.GamePhase
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.service.SheriffService
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
class SheriffElectionEdgeCaseIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var sheriffService: SheriffService

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

    /**
     * Set up a 6-player room with hasSheriff=true.
     * Returns (players[0..5], roomId).
     */
    private fun setupSheriffRoom(prefix: String): Pair<List<TestPlayer>, Int> {
        val players = (0..5).map { login("${prefix}P$it") }
        val host = players[0]

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 6,
                    "roles" to listOf("WEREWOLF", "SEER", "WITCH", "VILLAGER", "VILLAGER"),
                    "hasSheriff" to true,
                )),
                headers(host.token)
            ),
            Map::class.java
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(host.token)), Map::class.java)

        players.drop(1).forEachIndexed { idx, player ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(player.token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(player.token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(player.token)), Map::class.java)
        }

        return players to roomId
    }

    private fun startGameAndConfirmRoles(players: List<TestPlayer>, roomId: Int): Int {
        val host = players[0]
        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to roomId), headers(host.token)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        players.forEach { player ->
            assertThat(action(player.token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }
        return gameId
    }

    /** Advance speech until VOTING sub-phase (handles random speaking order). */
    private fun advanceSpeechUntilVoting(hostToken: String, gameId: Int) {
        for (i in 0..9) { // safety limit
            val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
            if (election.subPhase == ElectionSubPhase.VOTING) return
            action(hostToken, gameId, "SHERIFF_ADVANCE_SPEECH")
        }
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.VOTING)
    }

    // ── Case 1: Speech quitter cannot vote ────────────────────────────────────

    @Test
    fun `sheriff election - speech quitter cannot vote`() {
        val (players, roomId) = setupSheriffRoom("EC1")
        val host = players[0]; val g1 = players[1]; val g2 = players[2]; val g3 = players[3]
        val g4 = players[4]; val g5 = players[5]
        val gameId = startGameAndConfirmRoles(players, roomId)

        // SIGNUP: g1, g2, g3 campaign; others pass
        assertThat(action(g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // SPEECH: host starts, g3 quits during speech
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_QUIT_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)

        // Advance through remaining speeches → VOTING (handles random order)
        advanceSpeechUntilVoting(host.token, gameId)

        // g3's vote should be REJECTED (quit during speech → forfeited vote)
        val g3VoteResp = action(g3.token, gameId, "SHERIFF_VOTE", g1.userId)
        assertThat(g3VoteResp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        val g3VoteBody = g3VoteResp.body as Map<String, Any?>
        assertThat(g3VoteBody["error"] as String).contains("quit")

        // g3's abstain should also be REJECTED
        val g3AbstainResp = action(g3.token, gameId, "SHERIFF_ABSTAIN")
        assertThat(g3AbstainResp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        @Suppress("UNCHECKED_CAST")
        val g3AbstainBody = g3AbstainResp.body as Map<String, Any?>
        assertThat(g3AbstainBody["error"] as String).contains("quit")

        // Other eligible players vote → g1 wins
        assertThat(action(host.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)

        // Host reveals → single winner g1
        assertThat(action(host.token, gameId, "SHERIFF_REVEAL_RESULT").statusCode).isEqualTo(HttpStatus.OK)
        sheriffService.cancelScheduledJob(gameId)

        val resultElection = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(resultElection.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(resultElection.electedSheriffUserId).isEqualTo(g1.userId)

        val savedGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(savedGame.sheriffUserId).isEqualTo(g1.userId)
        val g1Player = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(g1Player.sheriff).isTrue()
    }

    // ── Case 1.a: All candidates quit → RESULT with auto-advance ──────────────

    @Test
    fun `sheriff election - all candidates quit shows RESULT not direct NIGHT`() {
        val (players, roomId) = setupSheriffRoom("EC1a")
        val host = players[0]; val g1 = players[1]; val g2 = players[2]; val g3 = players[3]
        val g4 = players[4]; val g5 = players[5]
        val gameId = startGameAndConfirmRoles(players, roomId)

        // SIGNUP: g1, g2, g3 campaign; others pass
        assertThat(action(g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // SPEECH: host starts
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)

        // All three quit during speech
        assertThat(action(g1.token, gameId, "SHERIFF_QUIT_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_QUIT_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        // After g3 quits, no running candidates remain → RESULT (not direct NIGHT)
        assertThat(action(g3.token, gameId, "SHERIFF_QUIT_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)

        // Assert: election in RESULT sub-phase, game still in SHERIFF_ELECTION
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(election.electedSheriffUserId).isNull()

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
        assertThat(game.sheriffUserId).isNull()

        // Cancel auto-advance to prevent it from firing during test cleanup
        sheriffService.cancelScheduledJob(gameId)
    }

    // ── Case 1.b: All abstain → TIED → host appoints ─────────────────────────

    @Test
    fun `sheriff election - all abstain creates TIED state, host appoints sheriff`() {
        val (players, roomId) = setupSheriffRoom("EC1b")
        val host = players[0]; val g1 = players[1]; val g2 = players[2]; val g3 = players[3]
        val g4 = players[4]; val g5 = players[5]
        val gameId = startGameAndConfirmRoles(players, roomId)

        // SIGNUP: g1, g2, g3 campaign; others pass
        assertThat(action(g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // SPEECH: advance through all speeches → VOTING
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)
        advanceSpeechUntilVoting(host.token, gameId)

        // All eligible voters abstain
        assertThat(action(host.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)

        // Host reveals result → TIED (running candidates exist but no one voted for them)
        assertThat(action(host.token, gameId, "SHERIFF_REVEAL_RESULT").statusCode).isEqualTo(HttpStatus.OK)

        val tiedElection = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(tiedElection.subPhase).isEqualTo(ElectionSubPhase.TIED)

        // Host appoints g1 as sheriff
        assertThat(action(host.token, gameId, "SHERIFF_APPOINT", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        sheriffService.cancelScheduledJob(gameId)

        val resultElection = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(resultElection.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(resultElection.electedSheriffUserId).isEqualTo(g1.userId)

        // Verify sheriff flag is set on game and player
        val savedGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(savedGame.sheriffUserId).isEqualTo(g1.userId)
        val g1Player = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(g1Player.sheriff).isTrue()
    }
}
