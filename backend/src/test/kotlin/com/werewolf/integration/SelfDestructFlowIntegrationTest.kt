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
import com.werewolf.model.SheriffElection
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.SheriffElectionRepository
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
 * Reproduction-first integration tests for the wolf self-destruct (自爆) day flow.
 *
 * Standard 狼人杀 rule: 自爆 ends the day immediately with NO vote and goes
 * straight to night. These tests drive the real services (Spring + H2) to prove
 * the flow reaches NIGHT — and never a DAY_VOTING sub-phase — on Day 1 (after a
 * sheriff election) AND on Day 2+, which is the asymmetry reported in #4/#2.
 *
 * Two wolves are configured so one self-destruct does not trigger an instant
 * villager win, isolating the day→night transition under test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SelfDestructFlowIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
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
        return TestPlayer(body[FIELD_TOKEN] as String, (body["user"] as Map<*, *>)["userId"] as String)
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
                headers(token),
            ),
            Map::class.java,
        )

    /** 6-player room, 2 wolves + seer + witch + 2 villagers, sheriff enabled. */
    private fun startSixPlayerGame(prefix: String): Pair<List<TestPlayer>, Int> {
        val host = login("${prefix}H")
        val guests = (1..5).map { login("$prefix$it") }
        val all = listOf(host) + guests

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        FIELD_TOTAL_PLAYERS to 6,
                        "wolfCount" to 2,
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

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(host.token)), Map::class.java)
        guests.forEachIndexed { idx, p ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(p.token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(p.token)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(p.token)), Map::class.java)
        }

        assertThat(restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(host.token)), Map::class.java).statusCode)
            .isEqualTo(HttpStatus.OK)
        val gameId = gameRepository.findAll().first { it.roomId == roomId }.gameId!!
        all.forEach { action(it.token, gameId, "CONFIRM_ROLE") }
        return all to gameId
    }

    private fun tokenOf(all: List<TestPlayer>, userId: String) = all.first { it.userId == userId }.token

    @Test
    fun `Day 1 - wolf self-destruct during sheriff election reaches NIGHT, never DAY_VOTING`() {
        val (all, gameId) = startSixPlayerGame("SD1")

        val players = gamePlayerRepository.findByGameId(gameId)
        val wolves = players.filter { it.role == PlayerRole.WEREWOLF }
        assertThat(wolves).hasSize(2)
        val victim = players.first { it.role != PlayerRole.WEREWOLF }

        // Arrange Day-1 SHERIFF_ELECTION with a completed night that has a
        // DEFERRED wolf kill (国标: Day-1 deaths are revealed after the election).
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.SHERIFF_ELECTION
        game.subPhase = null
        game.dayNumber = 1
        gameRepository.save(game)
        nightPhaseRepository.save(
            NightPhase(gameId = gameId, dayNumber = 1).also {
                it.subPhase = NightSubPhase.COMPLETE
                it.wolfTargetUserId = victim.userId
            },
        )
        sheriffElectionRepository.save(SheriffElection(gameId = gameId))

        // Wolf self-destructs during the election.
        assertThat(action(tokenOf(all, wolves[0].userId), gameId, "WOLF_SELF_DESTRUCT").statusCode)
            .isEqualTo(HttpStatus.OK)

        val afterBoom = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBoom.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(afterBoom.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(afterBoom.daySkipVoting).isTrue()

        // Host reveals the deferred night death → RESULT_REVEALED, victim dies.
        assertThat(action(tokenOf(all, game.hostUserId), gameId, "REVEAL_NIGHT_RESULT").statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterReveal = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterReveal.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(afterReveal.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(gamePlayerRepository.findByGameIdAndUserId(gameId, victim.userId).orElseThrow().alive).isFalse()

        // Host advances → must reach NIGHT (day 2), NEVER DAY_VOTING.
        assertThat(action(tokenOf(all, game.hostUserId), gameId, "VOTING_CONTINUE").statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterContinue = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterContinue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(afterContinue.dayNumber).isEqualTo(2)
        // The self-destruct record is cleared at night-init so the banner does
        // not bleed into the next day.
        assertThat(afterContinue.selfDestructUserId).isNull()
    }

    @Test
    fun `Day 2 - wolf self-destruct during discussion reaches NIGHT, never DAY_VOTING`() {
        val (all, gameId) = startSixPlayerGame("SD2")
        val players = gamePlayerRepository.findByGameId(gameId)
        val wolf = players.first { it.role == PlayerRole.WEREWOLF }

        // Arrange a Day-2 discussion with results already revealed.
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        game.dayNumber = 2
        gameRepository.save(game)
        nightPhaseRepository.save(
            NightPhase(gameId = gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE },
        )

        assertThat(action(tokenOf(all, wolf.userId), gameId, "WOLF_SELF_DESTRUCT").statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterBoom = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBoom.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(afterBoom.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(afterBoom.daySkipVoting).isTrue()

        assertThat(action(tokenOf(all, game.hostUserId), gameId, "VOTING_CONTINUE").statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterContinue = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterContinue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(afterContinue.dayNumber).isEqualTo(3)
    }

    @Test
    fun `wolf self-destruct during DAY_VOTING ends the day to night, never a vote-result screen`() {
        val (all, gameId) = startSixPlayerGame("SD3")
        val players = gamePlayerRepository.findByGameId(gameId)
        val wolf = players.first { it.role == PlayerRole.WEREWOLF }

        // Arrange an open Day-2 vote (results already revealed before voting).
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.DAY_VOTING
        game.subPhase = com.werewolf.model.VotingSubPhase.VOTING.name
        game.dayNumber = 2
        gameRepository.save(game)
        nightPhaseRepository.save(
            NightPhase(gameId = gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE },
        )

        assertThat(action(tokenOf(all, wolf.userId), gameId, "WOLF_SELF_DESTRUCT").statusCode)
            .isEqualTo(HttpStatus.OK)

        // Standard rule: the day ends with NO vote → no DAY_VOTING vote-result screen.
        val afterBoom = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBoom.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(afterBoom.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(afterBoom.daySkipVoting).isTrue()

        assertThat(action(tokenOf(all, game.hostUserId), gameId, "VOTING_CONTINUE").statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterContinue = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterContinue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(afterContinue.dayNumber).isEqualTo(3)
    }
}
