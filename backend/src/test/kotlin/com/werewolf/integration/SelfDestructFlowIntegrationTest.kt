package com.werewolf.integration

import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.repository.SheriffElectionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
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
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SelfDestructFlowIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var werewolfHandler: com.werewolf.game.role.WerewolfHandler

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

    /** 6-player room, 2 wolves + seer + witch + 2 villagers (by default), sheriff enabled. */
    private fun startSixPlayerGame(
        prefix: String,
        roles: List<String> = listOf("WEREWOLF", "SEER", "WITCH", "VILLAGER", "VILLAGER"),
    ): Pair<List<TestPlayer>, Int> {
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
                        "roles" to roles,
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
        game.subPhase = VotingSubPhase.VOTING.name
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

    // ── White Wolf King (白狼王) ─────────────────────────────────────────────

    /** Day-2 discussion, night result already shown. */
    private fun arrangeDay2Discussion(gameId: Int, sheriffUserId: String? = null) {
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        game.dayNumber = 2
        game.sheriffUserId = sheriffUserId
        gameRepository.save(game)
        if (sheriffUserId != null) {
            gamePlayerRepository.findByGameIdAndUserId(gameId, sheriffUserId).orElseThrow()
                .also { it.sheriff = true }.let { gamePlayerRepository.save(it) }
        }
        nightPhaseRepository.save(
            NightPhase(gameId = gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE },
        )
    }

    @Test
    fun `White Wolf King takes the sheriff - sheriff hands the badge over, then the day ends to night`() {
        val (all, gameId) = startSixPlayerGame("WK1", listOf("WEREWOLF", "WHITE_WOLF_KING", "SEER", "WITCH", "VILLAGER"))
        val hostId = gameRepository.findById(gameId).orElseThrow().hostUserId
        val players = gamePlayerRepository.findByGameId(gameId)
        val king = players.single { it.role == PlayerRole.WHITE_WOLF_KING }
        val (sheriff, heir) = players.filter { !it.role.isWolf && it.userId != hostId }.take(2)
        arrangeDay2Discussion(gameId, sheriffUserId = sheriff.userId)

        assertThat(action(tokenOf(all, king.userId), gameId, "WOLF_SELF_DESTRUCT", sheriff.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterBoom = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBoom.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        assertThat(afterBoom.selfDestructTakenUserId).isEqualTo(sheriff.userId)
        assertThat(gamePlayerRepository.findByGameIdAndUserId(gameId, king.userId).orElseThrow().alive).isFalse()
        assertThat(gamePlayerRepository.findByGameIdAndUserId(gameId, sheriff.userId).orElseThrow().alive).isFalse()

        assertThat(action(tokenOf(all, sheriff.userId), gameId, "BADGE_PASS", heir.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterBadge = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBadge.sheriffUserId).isEqualTo(heir.userId)
        assertThat(afterBadge.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(afterBadge.daySkipVoting).isTrue()

        assertThat(action(tokenOf(all, hostId), gameId, "VOTING_CONTINUE").statusCode).isEqualTo(HttpStatus.OK)
        val afterContinue = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterContinue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(afterContinue.dayNumber).isEqualTo(3)
        assertThat(afterContinue.selfDestructTakenUserId).isNull()
    }

    @Test
    fun `a hunter taken by the White Wolf King gets no shot - the day ends to night`() {
        val (all, gameId) = startSixPlayerGame("WK2", listOf("WEREWOLF", "WHITE_WOLF_KING", "SEER", "HUNTER", "VILLAGER"))
        val hostId = gameRepository.findById(gameId).orElseThrow().hostUserId
        val players = gamePlayerRepository.findByGameId(gameId)
        val king = players.single { it.role == PlayerRole.WHITE_WOLF_KING }
        val hunter = players.single { it.role == PlayerRole.HUNTER }
        arrangeDay2Discussion(gameId)

        assertThat(action(tokenOf(all, king.userId), gameId, "WOLF_SELF_DESTRUCT", hunter.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        val afterBoom = gameRepository.findById(gameId).orElseThrow()
        assertThat(afterBoom.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(gamePlayerRepository.findByGameIdAndUserId(gameId, hunter.userId).orElseThrow().alive).isFalse()

        assertThat(action(tokenOf(all, hostId), gameId, "VOTING_CONTINUE").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(gameId).orElseThrow().phase).isEqualTo(GamePhase.NIGHT)
    }

    // ── 白狼王 rule verification ─────────────────────────────────────────────
    // Each test asserts the written rule; a failure means the game does not
    // follow that rule yet.

    private val kingRoles = listOf("WEREWOLF", "WHITE_WOLF_KING", "SEER", "WITCH", "VILLAGER")

    private fun arrange(
        gameId: Int,
        phase: GamePhase,
        subPhase: String?,
        day: Int = 2,
        night: NightPhase.() -> Unit = {},
    ) {
        val game = gameRepository.findById(gameId).orElseThrow()
        game.phase = phase
        game.subPhase = subPhase
        game.dayNumber = day
        gameRepository.save(game)
        nightPhaseRepository.save(
            NightPhase(gameId = gameId, dayNumber = day).also { it.subPhase = NightSubPhase.COMPLETE; it.night() },
        )
    }

    private fun alive(gameId: Int, userId: String) =
        gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow().alive

    private data class KingGame(val all: List<TestPlayer>, val gameId: Int, val hostId: String, val king: GamePlayer, val wolf: GamePlayer, val target: GamePlayer)

    private fun kingGame(prefix: String, roles: List<String> = kingRoles): KingGame {
        val (all, gameId) = startSixPlayerGame(prefix, roles)
        val hostId = gameRepository.findById(gameId).orElseThrow().hostUserId
        val players = gamePlayerRepository.findByGameId(gameId)
        val king = players.single { it.role == PlayerRole.WHITE_WOLF_KING }
        val wolf = players.single { it.role == PlayerRole.WEREWOLF }
        val target = players.first { !it.role.isWolf && it.userId != hostId }
        return KingGame(all, gameId, hostId, king, wolf, target)
    }

    @Test
    fun `rule - a plain werewolf cannot take a player (server returns 400, nothing changes)`() {
        val g = kingGame("WR0")
        arrange(g.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)

        val resp = action(tokenOf(g.all, g.wolf.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(resp.body?.get("error")).isEqualTo("Only the White Wolf King can take a player")
        assertThat(alive(g.gameId, g.wolf.userId)).isTrue()
        assertThat(alive(g.gameId, g.target.userId)).isTrue()
    }

    @Test
    fun `rule - king may self-destruct and take a player during the sheriff election (上警阶段)`() {
        val g = kingGame("WR1")
        arrange(g.gameId, GamePhase.SHERIFF_ELECTION, null, day = 1)
        sheriffElectionRepository.save(SheriffElection(gameId = g.gameId))

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.king.userId)).isFalse()
        assertThat(alive(g.gameId, g.target.userId)).isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION) // election interrupted
        assertThat(game.daySkipVoting).isTrue()
    }

    // House rule (kept on purpose): unlike some rule sets, wolves — the king
    // included — may self-destruct during the vote and the vote-result last words.
    @Test
    fun `house rule - king may self-destruct and take a player during the vote`() {
        val g = kingGame("WR2")
        arrange(g.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.target.userId)).isFalse()
    }

    @Test
    fun `house rule - king may self-destruct and take a player on the vote-result screen (last words)`() {
        val g = kingGame("WR3")
        arrange(g.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTE_RESULT.name)

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.target.userId)).isFalse()
    }

    @Test
    fun `rule - king may take any player, even a wolf teammate, both leave at once (一换一)`() {
        val g = kingGame("WR4")
        arrange(g.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.wolf.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.king.userId)).isFalse()
        assertThat(alive(g.gameId, g.wolf.userId)).isFalse()
    }

    @Test
    fun `rule - after the king self-destructs the day is over, the host cannot start a vote (直接入夜)`() {
        val g = kingGame("WR5")
        arrange(g.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)
        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(action(tokenOf(g.all, g.hostId), g.gameId, "DAY_ADVANCE").statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(action(tokenOf(g.all, g.hostId), g.gameId, "VOTING_CONTINUE").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(gameRepository.findById(g.gameId).orElseThrow().phase).isEqualTo(GamePhase.NIGHT)
    }

    @Test
    fun `rule - at night the king kills with the wolves and may target itself (可自刀)`() {
        val g = kingGame("WR6")
        val game = gameRepository.findById(g.gameId).orElseThrow()
        game.phase = GamePhase.NIGHT
        game.subPhase = null
        game.dayNumber = 2
        gameRepository.save(game)
        nightPhaseRepository.save(NightPhase(gameId = g.gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.WEREWOLF_PICK })
        // A real night starts via initNight, which clears the per-night kill lock. This
        // test arranges the night directly, so clear it too (game ids repeat across contexts).
        werewolfHandler.resetKillLock(g.gameId)

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_KILL", g.king.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 2).orElseThrow().wolfTargetUserId)
            .isEqualTo(g.king.userId)
    }

    @Test
    fun `rule - a king voted out cannot take anyone (被放逐无法发动)`() {
        val g = kingGame("WR7")
        arrange(g.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)
        g.all.filter { it.userId != g.king.userId }.forEach {
            assertThat(action(it.token, g.gameId, "SUBMIT_VOTE", g.king.userId).statusCode).isEqualTo(HttpStatus.OK)
        }
        assertThat(action(tokenOf(g.all, g.hostId), g.gameId, "VOTING_REVEAL_TALLY").statusCode).isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.king.userId)).isFalse()
        assertThat(gamePlayerRepository.findByGameId(g.gameId).count { !it.alive }).isEqualTo(1) // only the king
        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(alive(g.gameId, g.target.userId)).isTrue()
    }

    @Test
    fun `rule - a king shot by the hunter cannot take anyone (被猎人带走无法发动)`() {
        val g = kingGame("WR8", listOf("WEREWOLF", "WHITE_WOLF_KING", "SEER", "HUNTER", "VILLAGER"))
        val hunter = gamePlayerRepository.findByGameId(g.gameId).single { it.role == PlayerRole.HUNTER }
        arrange(g.gameId, GamePhase.DAY_VOTING, VotingSubPhase.HUNTER_SHOOT.name)
        hunter.alive = false // voted out, now shooting
        gamePlayerRepository.save(hunter)

        assertThat(action(tokenOf(g.all, hunter.userId), g.gameId, "HUNTER_SHOOT", g.king.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.king.userId)).isFalse()
        val other = gamePlayerRepository.findByGameId(g.gameId).first { it.alive && !it.role.isWolf }
        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", other.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(alive(g.gameId, other.userId)).isTrue()
    }

    @Test
    fun `rule - the last wolf (king) may self-destruct but cannot take anyone`() {
        val g = kingGame("WR10")
        arrange(g.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)
        g.wolf.alive = false
        gamePlayerRepository.save(g.wolf)

        assertThat(action(tokenOf(g.all, g.king.userId), g.gameId, "WOLF_SELF_DESTRUCT", g.target.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        assertThat(alive(g.gameId, g.king.userId)).isFalse()
        assertThat(alive(g.gameId, g.target.userId)).isTrue()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.selfDestructTakenUserId).isNull()
        assertThat(game.winner).isEqualTo(WinnerSide.VILLAGER) // the last wolf is gone
    }
}
