package com.werewolf.integration

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.DaySubPhase
import com.werewolf.model.GamePhase
import com.werewolf.model.NightPhase
import com.werewolf.model.NightSubPhase
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
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
 * Integration coverage for the new "sheriff killed at night gets one last
 * action" contract. Setup mirrors SheriffElectionIntegrationTest's shortcut
 * style — the night flow isn't under test, so we manipulate the NightPhase
 * row directly to set wolfTargetUserId, then hit REVEAL_NIGHT_RESULT through
 * the real HTTP endpoint and verify:
 *
 *   1. revealNightResult lands on DAY_DISCUSSION/BADGE_HANDOVER when the
 *      sheriff is the wolves' victim (and stays on RESULT_REVEALED when
 *      they're not).
 *   2. BADGE_PASS during the night-reveal handover transitions back to
 *      DAY_DISCUSSION/RESULT_REVEALED with the heir flagged.
 *   3. BADGE_DESTROY during the night-reveal handover clears sheriffUserId
 *      and lands on RESULT_REVEALED.
 *   4. After handover, the existing dayAdvance path still works (host can
 *      proceed to voting).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SheriffNightDeathHandoverIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository

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

    private data class Sextuple(
        val host: TestPlayer,
        val g1: TestPlayer,
        val g2: TestPlayer,
        val g3: TestPlayer,
        val g4: TestPlayer,
        val g5: TestPlayer,
        val roomId: Int,
    )

    private fun setupSheriffRoom(prefix: String): Sextuple {
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
                        "roles" to listOf("WEREWOLF", "SEER", "WITCH", "VILLAGER", "VILLAGER"),
                        "hasSheriff" to true,
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

        return Sextuple(host, g1, g2, g3, g4, g5, roomId)
    }

    /**
     * Drive the room from creation → DAY_DISCUSSION/RESULT_HIDDEN with the
     * given user already flagged as sheriff and a populated Day-2 NightPhase
     * where the wolves chose `wolfTargetUserId` as their victim.
     *
     * Bypasses the actual night flow — this test is about the day-reveal
     * branch, not about night orchestration.
     */
    private fun openDay2RevealWithSheriffAndWolfTarget(
        host: TestPlayer,
        players: List<TestPlayer>,
        roomId: Int,
        sheriffUserId: String,
        wolfTargetUserId: String,
    ): Int {
        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to roomId), headers(host.token)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        players.forEach { player ->
            assertThat(action(player.token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        // Park the game at "Day 2 morning, ready to reveal" with the sheriff
        // already in place. Real games reach this state via N1 → election →
        // D1 vote → N2 night actions; we skip ahead because that path is
        // already covered by SheriffElectionIntegrationTest + the unit suite.
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_HIDDEN.name
        game.dayNumber = 2
        game.sheriffUserId = sheriffUserId
        gameRepository.save(game)

        // Flag the sheriff player as sheriff (matches what handleBadge
        // checks alongside game.sheriffUserId).
        gamePlayerRepository.findByGameIdAndUserId(gameId, sheriffUserId).ifPresent {
            it.sheriff = true
            gamePlayerRepository.save(it)
        }

        // The night that just ended: wolves targeted the given player. No
        // antidote, no guard save → computePendingKills returns [target].
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 2).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = wolfTargetUserId
        })

        return gameId
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `revealNightResult lands on BADGE_HANDOVER when sheriff is the wolves victim`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SNDH1")
        val players = listOf(host, g1, g2, g3, g4, g5)
        val gameId = openDay2RevealWithSheriffAndWolfTarget(
            host, players, roomId,
            sheriffUserId = g1.userId,
            wolfTargetUserId = g1.userId,
        )

        val resp = action(host.token, gameId, "REVEAL_NIGHT_RESULT")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        // Sheriff identity is intact during handover; the dying player is
        // still authorized to dispatch BADGE_PASS / BADGE_DESTROY.
        assertThat(game.sheriffUserId).isEqualTo(g1.userId)
        // Sheriff is dead in DB after applyNightKills, but the handover doesn't
        // care about aliveness for the actor check.
        val g1Player = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(g1Player.alive).isFalse()
    }

    @Test
    fun `revealNightResult lands on RESULT_REVEALED when a non-sheriff is killed`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SNDH2")
        val players = listOf(host, g1, g2, g3, g4, g5)
        val gameId = openDay2RevealWithSheriffAndWolfTarget(
            host, players, roomId,
            sheriffUserId = g1.userId,
            wolfTargetUserId = g2.userId, // not the sheriff
        )

        val resp = action(host.token, gameId, "REVEAL_NIGHT_RESULT")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game.sheriffUserId).isEqualTo(g1.userId)
    }

    @Test
    fun `night-reveal BADGE_PASS hands the badge to the heir and lands back on RESULT_REVEALED`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SNDH3")
        val players = listOf(host, g1, g2, g3, g4, g5)
        val gameId = openDay2RevealWithSheriffAndWolfTarget(
            host, players, roomId,
            sheriffUserId = g1.userId,
            wolfTargetUserId = g1.userId,
        )
        // Land in BADGE_HANDOVER first.
        assertThat(action(host.token, gameId, "REVEAL_NIGHT_RESULT").statusCode).isEqualTo(HttpStatus.OK)

        // Sheriff hands the badge to g3 (alive).
        val passResp = action(g1.token, gameId, "BADGE_PASS", g3.userId)
        assertThat(passResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game.sheriffUserId).isEqualTo(g3.userId)

        val oldSheriff = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        val newSheriff = gamePlayerRepository.findByGameIdAndUserId(gameId, g3.userId).orElseThrow()
        assertThat(oldSheriff.sheriff).isFalse()
        assertThat(newSheriff.sheriff).isTrue()
    }

    @Test
    fun `night-reveal BADGE_DESTROY clears sheriffUserId and lands back on RESULT_REVEALED`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SNDH4")
        val players = listOf(host, g1, g2, g3, g4, g5)
        val gameId = openDay2RevealWithSheriffAndWolfTarget(
            host, players, roomId,
            sheriffUserId = g1.userId,
            wolfTargetUserId = g1.userId,
        )
        assertThat(action(host.token, gameId, "REVEAL_NIGHT_RESULT").statusCode).isEqualTo(HttpStatus.OK)

        val destroyResp = action(g1.token, gameId, "BADGE_DESTROY")
        assertThat(destroyResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game.sheriffUserId).isNull()

        val oldSheriff = gamePlayerRepository.findByGameIdAndUserId(gameId, g1.userId).orElseThrow()
        assertThat(oldSheriff.sheriff).isFalse()
    }

    @Test
    fun `after night-reveal handover, host can dayAdvance to voting`() {
        val (host, g1, g2, g3, g4, g5, roomId) = setupSheriffRoom("SNDH5")
        val players = listOf(host, g1, g2, g3, g4, g5)
        val gameId = openDay2RevealWithSheriffAndWolfTarget(
            host, players, roomId,
            sheriffUserId = g1.userId,
            wolfTargetUserId = g1.userId,
        )
        assertThat(action(host.token, gameId, "REVEAL_NIGHT_RESULT").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(g1.token, gameId, "BADGE_PASS", g3.userId).statusCode).isEqualTo(HttpStatus.OK)

        // Now the host can advance into voting via the existing DAY_ADVANCE.
        val advanceResp = action(host.token, gameId, "DAY_ADVANCE")
        assertThat(advanceResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_VOTING)
    }
}
