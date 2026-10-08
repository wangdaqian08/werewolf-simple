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
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * Bug: the sheriff (A) is voted out and hands the badge to B during the
 * voting-elimination BADGE_HANDOVER. The VOTE_RESULT tally shown afterwards
 * was recomputed with B as sheriff — B's vote became 1.5 and A's dropped to
 * 1.0. A vote's weight is fixed by who held the badge when it was cast; B's
 * 1.5 only applies from the next vote onward.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SheriffVotedOutWeightIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository

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
    private fun votingPhase(token: String, gameId: Int): Map<String, Any?> =
        restTemplate.exchange(
            "/api/game/$gameId/state", HttpMethod.GET, HttpEntity<Void>(headers(token)), Map::class.java,
        ).body!!["votingPhase"] as Map<String, Any?>

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

    @Test
    fun `voted-out sheriff passes badge - VOTE_RESULT tally keeps the weights from when votes were cast`() {
        val (gameId, players) = startGame("SVOW")
        val host = players[0]
        val byUserId = players.associateBy { it.userId }
        val roles = gamePlayerRepository.findByGameId(gameId).associate { it.userId to it.role }

        // A = sheriff who gets voted out (a villager, so the exile cannot end
        // the game — the other villager survives). B = heir. X = A's target.
        val a = byUserId.getValue(roles.entries.first { it.value == PlayerRole.VILLAGER }.key)
        val b = byUserId.getValue(roles.entries.first { it.value == PlayerRole.SEER }.key)
        val x = byUserId.getValue(roles.entries.first { it.value == PlayerRole.WITCH }.key)
        val (o1, o2, o3) = players.filter { it !in setOf(a, b, x) }

        // Park on Day 2 voting with A holding the badge.
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.DAY_VOTING
        game.subPhase = VotingSubPhase.VOTING.name
        game.dayNumber = 2
        game.sheriffUserId = a.userId
        gameRepository.save(game)
        gamePlayerRepository.findByGameIdAndUserId(gameId, a.userId).ifPresent {
            it.sheriff = true; gamePlayerRepository.save(it)
        }

        // A (sheriff, 1.5) + o3 → X = 2.5 ; B + o1 + o2 → A = 3.0 ; X abstains.
        assertThat(action(a.token, gameId, "SUBMIT_VOTE", x.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(o3.token, gameId, "SUBMIT_VOTE", x.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(b.token, gameId, "SUBMIT_VOTE", a.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(o1.token, gameId, "SUBMIT_VOTE", a.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(o2.token, gameId, "SUBMIT_VOTE", a.userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(x.token, gameId, "SUBMIT_VOTE", null).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(action(host.token, gameId, "VOTING_REVEAL_TALLY").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(gameId).orElseThrow().subPhase)
            .isEqualTo(VotingSubPhase.BADGE_HANDOVER.name)

        // A hands the badge to B.
        assertThat(action(a.token, gameId, "BADGE_PASS", b.userId).statusCode).isEqualTo(HttpStatus.OK)
        val after = gameRepository.findById(gameId).orElseThrow()
        assertThat(after.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(after.sheriffUserId).isEqualTo(b.userId)

        @Suppress("UNCHECKED_CAST")
        val tally = (votingPhase(host.token, gameId)["tally"] as List<Map<String, Any?>>)
            .associate { it["playerId"] as String to (it["votes"] as Number).toDouble() }
        assertThat(tally).containsExactlyInAnyOrderEntriesOf(mapOf(a.userId to 3.0, x.userId to 2.5))
    }
}
