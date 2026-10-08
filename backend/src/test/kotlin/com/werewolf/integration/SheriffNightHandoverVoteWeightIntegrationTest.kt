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
import com.werewolf.model.PlayerRole
import com.werewolf.model.VotingSubPhase
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
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * The sheriff (A) is killed at night and the badge is settled at the day
 * reveal, before any voting. A vote's 1.5x weight belongs to whoever holds the
 * badge when the vote is cast, so:
 *  - an heir (B) chosen at the reveal votes with 1.5 that same day;
 *  - if the badge is destroyed instead, nobody votes with 1.5.
 * Companion to SheriffVotedOutWeightIntegrationTest (badge handed over AFTER
 * the day's votes were cast).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SheriffNightHandoverVoteWeightIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository

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
            "/api/game/action",
            HttpEntity(
                mapOf("gameId" to gameId, "actionType" to actionType, "targetUserId" to targetUserId),
                headers(token),
            ),
            Map::class.java,
        )

    @Suppress("UNCHECKED_CAST")
    private fun tally(token: String, gameId: Int): Map<String, Double> {
        val votingPhase = restTemplate.exchange(
            "/api/game/$gameId/state", HttpMethod.GET, HttpEntity<Void>(headers(token)), Map::class.java,
        ).body!!["votingPhase"] as Map<String, Any?>
        return (votingPhase["tally"] as List<Map<String, Any?>>)
            .associate { it["playerId"] as String to (it["votes"] as Number).toDouble() }
    }

    /** Room of 6 with sheriff enabled, started, all roles confirmed. Returns (gameId, players). */
    private fun startGame(prefix: String): Pair<Int, List<TestPlayer>> {
        val players = (0 until 6).map { login("$prefix$it") }
        val host = players[0]

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

        players.forEachIndexed { idx, p ->
            if (idx > 0) {
                restTemplate.postForEntity(
                    JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(p.token)), Map::class.java,
                )
            }
            restTemplate.postForEntity(
                "/api/room/seat",
                HttpEntity(mapOf("seatIndex" to idx, "roomId" to roomId), headers(p.token)),
                Map::class.java,
            )
            if (idx > 0) {
                restTemplate.postForEntity(
                    "/api/room/ready",
                    HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(p.token)),
                    Map::class.java,
                )
            }
        }

        assertThat(
            restTemplate.postForEntity(
                "/api/game/start", HttpEntity(mapOf("roomId" to roomId), headers(host.token)), Map::class.java,
            ).statusCode
        ).isEqualTo(HttpStatus.OK)
        val gameId = gameRepository.findAll().first { it.roomId == roomId }.gameId!!
        players.forEach { assertThat(action(it.token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK) }
        return gameId to players
    }

    /**
     * A = sheriff killed at night, B = heir, X = vote target, p1..p3 = the
     * other living players. X is chosen so that losing both A and X leaves
     * 0 < wolves < humans — the game must still be running for the tally to show.
     */
    private data class NightKilledSheriff(
        val gameId: Int,
        val host: TestPlayer,
        val a: TestPlayer,
        val b: TestPlayer,
        val x: TestPlayer,
        val others: List<TestPlayer>,
    )

    /**
     * Parks the game at Day 2 "ready to reveal" with A as sheriff and the
     * wolves' kill on A, then reveals — landing on the day-reveal
     * BADGE_HANDOVER. Night orchestration is bypassed (covered elsewhere).
     */
    private fun revealNightKillOfSheriff(prefix: String): NightKilledSheriff {
        val (gameId, players) = startGame(prefix)
        val host = players[0]
        val byUserId = players.associateBy { it.userId }
        val roles = gamePlayerRepository.findByGameId(gameId).associate { it.userId to it.role }

        // A must not be the host: the host keeps driving the day after A dies.
        val a = byUserId.getValue(roles.entries.first { it.value == PlayerRole.VILLAGER && it.key != host.userId }.key)
        val b = byUserId.getValue(roles.entries.first { it.value == PlayerRole.SEER }.key)
        val survivorsOfNight = players.filter { it != a }
        val x = survivorsOfNight.filter { it != b }.sortedBy { it == host }.first { candidate ->
            val remaining = survivorsOfNight.filter { it != candidate }
            val wolves = remaining.count { roles[it.userId] == PlayerRole.WEREWOLF }
            wolves in 1 until remaining.size - wolves
        }
        val others = players.filter { it !in setOf(a, b, x) }

        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_HIDDEN.name
        game.dayNumber = 2
        game.sheriffUserId = a.userId
        gameRepository.save(game)
        gamePlayerRepository.findByGameIdAndUserId(gameId, a.userId).ifPresent {
            it.sheriff = true; gamePlayerRepository.save(it)
        }
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 2).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = a.userId
        })

        assertThat(action(host.token, gameId, "REVEAL_NIGHT_RESULT").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(gameId).orElseThrow().subPhase)
            .isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        return NightKilledSheriff(gameId, host, a, b, x, others)
    }

    /** Host opens voting, then: B + p1 → X ; p2 + p3 → p1 ; X abstains. Reveals the tally. */
    private fun voteAndReveal(s: NightKilledSheriff) {
        val (p1, p2, p3) = s.others
        assertThat(action(s.host.token, s.gameId, "DAY_ADVANCE").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(s.gameId).orElseThrow().phase).isEqualTo(GamePhase.DAY_VOTING)

        assertThat(action(s.b.token, s.gameId, "SUBMIT_VOTE", s.x.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(p1.token, s.gameId, "SUBMIT_VOTE", s.x.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(p2.token, s.gameId, "SUBMIT_VOTE", p1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(p3.token, s.gameId, "SUBMIT_VOTE", p1.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(s.x.token, s.gameId, "SUBMIT_VOTE", null).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(action(s.host.token, s.gameId, "VOTING_REVEAL_TALLY").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `heir chosen at the night-death reveal votes with 1_5 the same day`() {
        val s = revealNightKillOfSheriff("SNHW")

        assertThat(action(s.a.token, s.gameId, "BADGE_PASS", s.b.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(s.gameId).orElseThrow().sheriffUserId).isEqualTo(s.b.userId)

        voteAndReveal(s)

        // B (sheriff, 1.5) + p1 = 2.5 beats 2.0, so X is eliminated outright.
        // Had B's badge only counted from the next day, this would be a 2.0 tie.
        val p1 = s.others[0]
        val game = gameRepository.findById(s.gameId).orElseThrow()
        assertThat(game.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(tally(s.host.token, s.gameId))
            .containsExactlyInAnyOrderEntriesOf(mapOf(s.x.userId to 2.5, p1.userId to 2.0))
        assertThat(gamePlayerRepository.findByGameIdAndUserId(s.gameId, s.x.userId).orElseThrow().alive).isFalse()
    }

    @Test
    fun `badge destroyed at the night-death reveal - nobody votes with 1_5, so a 2-2 vote goes to a re-vote`() {
        val s = revealNightKillOfSheriff("SNHD")

        assertThat(action(s.a.token, s.gameId, "BADGE_DESTROY").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(s.gameId).orElseThrow().sheriffUserId).isNull()

        voteAndReveal(s)

        // B + p1 → X = 2.0 ; p2 + p3 → p1 = 2.0 : a tie, nobody eliminated.
        val p1 = s.others[0]
        assertThat(gameRepository.findById(s.gameId).orElseThrow().subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)
        assertThat(gamePlayerRepository.findByGameIdAndUserId(s.gameId, s.x.userId).orElseThrow().alive).isTrue()
        assertThat(gamePlayerRepository.findByGameIdAndUserId(s.gameId, p1.userId).orElseThrow().alive).isTrue()
    }
}
