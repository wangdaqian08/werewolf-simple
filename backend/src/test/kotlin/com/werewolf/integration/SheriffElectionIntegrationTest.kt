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
import com.werewolf.model.ElectionSubPhase
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
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
class SheriffElectionIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var nightOrchestrator: NightOrchestrator
    @Autowired lateinit var sheriffService: SheriffService

    companion object {
        const val START_URL = "/api/game/start"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val ACTION_URL = "/api/game/action"
    }

    private data class TestPlayer(val token: String, val userId: String)
    private data class Sextuple(val host: TestPlayer, val g1: TestPlayer, val g2: TestPlayer, val g3: TestPlayer, val g4: TestPlayer, val g5: TestPlayer, val roomId: Int)

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
     * Set up a 4-player room with hasSheriff=true.
     * Returns (host, g1, g2, g3, roomId).
     */
    private fun setupSheriffRoom(prefix: String): Sextuple {
        val host = login("${prefix}H")
        val g1   = login("${prefix}G1")
        val g2   = login("${prefix}G2")
        val g3   = login("${prefix}G3")
        val g4   = login("${prefix}G4")
        val g5   = login("${prefix}G5")

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to 6, "roles" to listOf("WEREWOLF", "SEER", "WITCH", "VILLAGER", "VILLAGER"), "hasSheriff" to true)),
                headers(host.token)
            ),
            Map::class.java
        ).body!! as Map<String, Any?>

        val roomId   = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        // Host claims seat 0
        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(host.token)), Map::class.java)

        // Guests join, claim seats, go ready
        listOf(g1, g2, g3, g4, g5).forEachIndexed { idx, player ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(player.token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(player.token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(player.token)), Map::class.java)
        }

        return Sextuple(host, g1, g2, g3, g4, g5, roomId)
    }

    /** Advance speech until VOTING sub-phase (handles random speaking order). */
    private fun advanceSpeechUntilVoting(hostToken: String, gameId: Int) {
        for (i in 0..9) {
            val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
            if (election.subPhase == ElectionSubPhase.VOTING) return
            action(hostToken, gameId, "SHERIFF_ADVANCE_SPEECH")
        }
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.VOTING)
    }

    /** Start game, confirm all roles. Returns gameId. Game is now in SHERIFF_ELECTION/SIGNUP. */
    private fun startGameAndConfirmRoles(host: TestPlayer, players: List<TestPlayer>, roomId: Int): Int {
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

    // ── Test 1: Single winner path ────────────────────────────────────────────

    @Test
    fun `sheriff election - single candidate wins, sheriffUserId and sheriff flag are set, game advances to NIGHT`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SE1")
        val allPlayers = listOf(host, g1, g2, g3, g4, g5)
        val gameId = startGameAndConfirmRoles(host, allPlayers, roomId)

        // SIGNUP: g1 and g2 campaign; others pass
        assertThat(action(g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // SPEECH: g2 quits, advance until VOTING (handles random speaking order)
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_QUIT_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        advanceSpeechUntilVoting(host.token, gameId)

        // VOTING: host, g3, g4, g5 vote for g1; g1 abstains (can't self-vote); g2 cannot vote (quit during speech)
        assertThat(action(host.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "SHERIFF_ABSTAIN").statusCode).isEqualTo(HttpStatus.OK)

        // RESULT: host reveals → single winner → g1 elected
        assertThat(action(host.token, gameId, "SHERIFF_REVEAL_RESULT").statusCode).isEqualTo(HttpStatus.OK)

        // Cancel auto-advance immediately after result reveal
        sheriffService.cancelScheduledJob(gameId)

        // Assert election is in RESULT sub-phase
        val election = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(election.electedSheriffUserId).isEqualTo(g1.userId)

        // Assert game.sheriffUserId is set and sheriff flag on the player
        val savedGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(savedGame.sheriffUserId).isEqualTo(g1.userId)
        val g1Player = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(g1Player.sheriff).isTrue()
    }

    // ── Test 2: Tie then appoint ──────────────────────────────────────────────

    @Test
    fun `sheriff election - tied vote, host appoints, sheriffUserId is set`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SE2")
        val allPlayers = listOf(host, g1, g2, g3, g4, g5)
        val gameId = startGameAndConfirmRoles(host, allPlayers, roomId)

        // SIGNUP: g1 and g2 both campaign
        assertThat(action(g1.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_CAMPAIGN").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // SPEECH: host starts → 2 candidates → 2 advances to reach VOTING
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_ADVANCE_SPEECH").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(host.token, gameId, "SHERIFF_ADVANCE_SPEECH").statusCode).isEqualTo(HttpStatus.OK)

        // VOTING: split votes → g1 gets 3, g2 gets 3 → TIE
        // host→g1, g3→g2, g1→g2, g2→g1, g4→g1, g5→g2
        assertThat(action(host.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_VOTE", g2.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "SHERIFF_VOTE", g2.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_VOTE", g1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_VOTE", g2.userId).statusCode).isEqualTo(HttpStatus.OK)

        // TIED: host reveals result
        assertThat(action(host.token, gameId, "SHERIFF_REVEAL_RESULT").statusCode).isEqualTo(HttpStatus.OK)
        val tiedElection = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(tiedElection.subPhase).isEqualTo(ElectionSubPhase.TIED)

        // APPOINT: host appoints g1
        assertThat(action(host.token, gameId, "SHERIFF_APPOINT", g1.userId).statusCode).isEqualTo(HttpStatus.OK)

        // Cancel auto-advance immediately after appointment
        sheriffService.cancelScheduledJob(gameId)

        // Assert election moves to RESULT with g1 elected
        val resultElection = sheriffElectionRepository.findByGameId(gameId).orElseThrow()
        assertThat(resultElection.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(resultElection.electedSheriffUserId).isEqualTo(g1.userId)

        // Assert game.sheriffUserId is set and sheriff flag on the player
        val savedGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(savedGame.sheriffUserId).isEqualTo(g1.userId)
        val g1Player = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(g1Player.sheriff).isTrue()
    }

    // ── Test 3: No-candidate shortcut ─────────────────────────────────────────

    @Test
    fun `sheriff election - no candidates, SHERIFF_START_SPEECH transitions directly to NIGHT`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SE3")
        val allPlayers = listOf(host, g1, g2, g3, g4, g5)
        val gameId = startGameAndConfirmRoles(host, allPlayers, roomId)

        // SIGNUP: all players pass — no candidates
        assertThat(action(host.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g2.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g3.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g4.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g5.token, gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)

        // No-candidate shortcut: SHERIFF_START_SPEECH calls initNight(withWaiting=true).
        // Night starts immediately (WAITING sub-phase) without blocking — roles advance via player actions.
        assertThat(action(host.token, gameId, "SHERIFF_START_SPEECH").statusCode).isEqualTo(HttpStatus.OK)

        // Game has transitioned from SHERIFF_ELECTION to NIGHT
        val savedGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(savedGame.phase).isEqualTo(GamePhase.NIGHT)

        // Night phase record was created in WAITING sub-phase (ready for first role to act)
        val nightPhase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, savedGame.dayNumber).orElseThrow()
        assertThat(nightPhase.subPhase).isEqualTo(NightSubPhase.WAITING)

        // No sheriff was elected
        assertThat(savedGame.sheriffUserId).isNull()
    }
}
