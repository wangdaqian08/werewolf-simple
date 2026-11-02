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
import com.werewolf.repository.*
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
 * HOST_DECLARE_OUT: the host declares a player out because they had to leave.
 * Drives the real services (Spring + H2). Fixed layout (CLASSIC / 屠边):
 * host=SEER, g1/g2=WEREWOLF, g3=HUNTER, g4/g5=VILLAGER.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HostDeclareOutIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Autowired lateinit var sheriffCandidateRepository: SheriffCandidateRepository
    @Autowired lateinit var voteRepository: VoteRepository
    @Autowired lateinit var eliminationHistoryRepository: EliminationHistoryRepository

    private data class P(val token: String, val userId: String)
    private class Table(val gameId: Int, val all: List<P>) {
        val host get() = all[0]
        fun g(i: Int) = all[i]
    }

    private fun login(nickname: String): P {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        return P(body[FIELD_TOKEN] as String, (body["user"] as Map<*, *>)["userId"] as String)
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun action(p: P, gameId: Int, actionType: String, targetUserId: String? = null) =
        restTemplate.postForEntity(
            "/api/game/action",
            HttpEntity(mapOf("gameId" to gameId, "actionType" to actionType, "targetUserId" to targetUserId), headers(p.token)),
            Map::class.java,
        )

    private fun declareOut(t: Table, target: P, by: P = t.host) = action(by, t.gameId, "HOST_DECLARE_OUT", target.userId)

    private fun forceRole(gameId: Int, userId: String, role: PlayerRole) {
        val gp = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow()
        GamePlayer::class.java.getDeclaredField("role").also { it.isAccessible = true }.set(gp, role)
        gamePlayerRepository.save(gp)
    }

    /** Started 6-player game in ROLE_REVEAL with the fixed layout; [confirm] = everyone confirms. */
    private fun startGame(prefix: String, confirm: Boolean = true): Table {
        val all = listOf(login("${prefix}H")) + (1..5).map { login("$prefix$it") }
        @Suppress("UNCHECKED_CAST")
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 6, "wolfCount" to 2,
                    "roles" to listOf("WEREWOLF", "SEER", "HUNTER", "VILLAGER", "VILLAGER"),
                    "hasSheriff" to true,
                )),
                headers(all[0].token),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>
        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        restTemplate.postForEntity("/api/room/seat", HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(all[0].token)), Map::class.java)
        all.drop(1).forEachIndexed { i, p ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to room[FIELD_ROOM_CODE]), headers(p.token)), Map::class.java)
            restTemplate.postForEntity("/api/room/seat", HttpEntity(mapOf("seatIndex" to i + 1, "roomId" to roomId), headers(p.token)), Map::class.java)
            restTemplate.postForEntity("/api/room/ready", HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(p.token)), Map::class.java)
        }
        assertThat(restTemplate.postForEntity("/api/game/start", HttpEntity(mapOf("roomId" to roomId), headers(all[0].token)), Map::class.java).statusCode)
            .isEqualTo(HttpStatus.OK)
        val gameId = gameRepository.findAll().first { it.roomId == roomId }.gameId!!
        listOf(PlayerRole.SEER, PlayerRole.WEREWOLF, PlayerRole.WEREWOLF, PlayerRole.HUNTER, PlayerRole.VILLAGER, PlayerRole.VILLAGER)
            .forEachIndexed { i, role -> forceRole(gameId, all[i].userId, role) }
        if (confirm) all.forEach { action(it, gameId, "CONFIRM_ROLE") }
        return Table(gameId, all)
    }

    private fun setPhase(t: Table, phase: GamePhase, subPhase: String?, day: Int = 2, block: (Game) -> Unit = {}) {
        val game = gameRepository.findById(t.gameId).orElseThrow()
        game.phase = phase
        game.subPhase = subPhase
        game.dayNumber = day
        block(game)
        gameRepository.save(game)
    }

    private fun setSheriff(t: Table, p: P) {
        setPhase(t, gameRepository.findById(t.gameId).orElseThrow().phase, gameRepository.findById(t.gameId).orElseThrow().subPhase,
            gameRepository.findById(t.gameId).orElseThrow().dayNumber) { it.sheriffUserId = p.userId }
        gamePlayerRepository.findByGameIdAndUserId(t.gameId, p.userId).orElseThrow().also { it.sheriff = true; gamePlayerRepository.save(it) }
    }

    private fun kill(t: Table, p: P) =
        gamePlayerRepository.findByGameIdAndUserId(t.gameId, p.userId).orElseThrow().also { it.alive = false; gamePlayerRepository.save(it) }

    private fun player(t: Table, p: P) = gamePlayerRepository.findByGameIdAndUserId(t.gameId, p.userId).orElseThrow()
    private fun game(t: Table) = gameRepository.findById(t.gameId).orElseThrow()

    // ── Rejections ──────────────────────────────────────────────────────────────

    @Test
    fun `rejected for a non-host, the host themselves, at night, before the reveal, and for a dead player`() {
        val t = startGame("DOR")
        setPhase(t, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)

        assertThat(declareOut(t, t.g(4), by = t.g(1)).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(declareOut(t, t.host).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        kill(t, t.g(5))
        assertThat(declareOut(t, t.g(5)).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        setPhase(t, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name)
        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        setPhase(t, GamePhase.NIGHT, null)
        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        assertThat(player(t, t.g(4)).alive).isTrue()
    }

    // ── Day discussion ──────────────────────────────────────────────────────────

    @Test
    fun `day discussion - declared-out sheriff dies, badge destroyed, the day goes on to voting`() {
        val t = startGame("DOD")
        setPhase(t, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)
        nightPhaseRepository.save(NightPhase(gameId = t.gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE })
        setSheriff(t, t.g(4))

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        val out = player(t, t.g(4))
        assertThat(out.alive).isFalse()
        assertThat(out.removedByHost).isTrue()
        assertThat(out.sheriff).isFalse()
        assertThat(game(t).sheriffUserId).isNull()
        assertThat(game(t).subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)

        assertThat(action(t.host, t.gameId, "DAY_ADVANCE").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(game(t).phase).isEqualTo(GamePhase.DAY_VOTING)
    }

    @Test
    fun `declaring the last wolf out ends the game for the villagers`() {
        val t = startGame("DOW")
        setPhase(t, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name)
        kill(t, t.g(1))

        assertThat(declareOut(t, t.g(2)).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(game(t).phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game(t).winner).isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `badge handover - host destroys the badge for a dead sheriff who left`() {
        val t = startGame("DOB")
        setPhase(t, GamePhase.DAY_DISCUSSION, DaySubPhase.BADGE_HANDOVER.name)
        nightPhaseRepository.save(NightPhase(gameId = t.gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE })
        setSheriff(t, t.g(4))
        kill(t, t.g(4))

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(game(t).sheriffUserId).isNull()
        assertThat(game(t).subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(player(t, t.g(4)).removedByHost).isTrue()
    }

    // ── Voting ──────────────────────────────────────────────────────────────────

    @Test
    fun `voting - the declared player's vote is dropped and votes for them count for no one`() {
        val t = startGame("DOV")
        setPhase(t, GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name)
        nightPhaseRepository.save(NightPhase(gameId = t.gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE })
        assertThat(action(t.g(4), t.gameId, "SUBMIT_VOTE", t.g(5).userId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(t.g(5), t.gameId, "SUBMIT_VOTE", t.g(4).userId).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(t.gameId, VoteContext.ELIMINATION, 2)
        assertThat(votes.map { it.voterUserId }).containsExactly(t.g(5).userId) // g5's vote for g4 is kept

        // g5's vote no longer counts → no one is voted out on that tally (tie → PK re-vote).
        assertThat(action(t.host, t.gameId, "VOTING_REVEAL_TALLY").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(game(t).subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)
        assertThat(eliminationHistoryRepository.findByGameIdAndDayNumber(t.gameId, 2)).isEmpty
    }

    @Test
    fun `PK re-vote - a declared-out candidate leaves the PK, it continues with the rest`() {
        val t = startGame("DOP")
        setPhase(t, GamePhase.DAY_VOTING, VotingSubPhase.RE_VOTING.name) {
            it.pkCandidateUserIds = "${t.g(4).userId},${t.g(5).userId}"
        }

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(game(t).pkCandidates()).containsExactly(t.g(5).userId)
        assertThat(game(t).subPhase).isEqualTo(VotingSubPhase.RE_VOTING.name)
    }

    // ── Role reveal ─────────────────────────────────────────────────────────────

    @Test
    fun `role reveal - the declared player counts as confirmed so the night can start`() {
        val t = startGame("DOR2", confirm = false)
        t.all.filter { it != t.g(4) }.forEach { action(it, t.gameId, "CONFIRM_ROLE") }
        assertThat(action(t.host, t.gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(player(t, t.g(4)).alive).isFalse()
        assertThat(action(t.host, t.gameId, "START_NIGHT").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(game(t).phase).isEqualTo(GamePhase.NIGHT)
    }

    // ── Sheriff election ────────────────────────────────────────────────────────

    private fun openCampaign(t: Table, wolfTarget: P? = null): SheriffElection {
        setPhase(t, GamePhase.SHERIFF_ELECTION, null, day = 1)
        nightPhaseRepository.save(NightPhase(gameId = t.gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = wolfTarget?.userId
        })
        return sheriffElectionRepository.save(SheriffElection(gameId = t.gameId))
    }

    @Test
    fun `sign-up - the last undecided player declared out completes the sign-up`() {
        val t = startGame("DOS")
        openCampaign(t)
        t.all.filter { it != t.g(4) }.forEach {
            assertThat(action(it, t.gameId, "SHERIFF_PASS").statusCode).isEqualTo(HttpStatus.OK)
        }
        assertThat(game(t).phase).isEqualTo(GamePhase.SHERIFF_ELECTION)

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        // Everyone else passed → no campaign → the day continues before the reveal.
        assertThat(game(t).phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game(t).subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `speech - the only running candidate declared out quits, the campaign ends`() {
        val t = startGame("DOC")
        val election = openCampaign(t)
        sheriffCandidateRepository.save(SheriffCandidate(electionId = election.id!!, userId = t.g(4).userId))
        election.subPhase = ElectionSubPhase.SPEECH
        election.speakingOrder = t.g(4).userId
        sheriffElectionRepository.save(election)

        assertThat(declareOut(t, t.g(4)).statusCode).isEqualTo(HttpStatus.OK)

        assertThat(sheriffCandidateRepository.findByElectionId(election.id!!).single().status).isEqualTo(CandidateStatus.QUIT)
        assertThat(game(t).phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game(t).subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `a hunter declared out during the campaign gets no shot when last night's kill on them is revealed`() {
        val t = startGame("DOH")
        openCampaign(t, wolfTarget = t.g(3))

        assertThat(declareOut(t, t.g(3)).statusCode).isEqualTo(HttpStatus.OK)
        // Nobody decided yet; the host ends the campaign the normal way by passing everyone.
        t.all.filter { it != t.g(3) }.forEach { action(it, t.gameId, "SHERIFF_PASS") }
        assertThat(game(t).subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        assertThat(action(t.host, t.gameId, "REVEAL_NIGHT_RESULT").statusCode).isEqualTo(HttpStatus.OK)

        assertThat(game(t).pendingShooterUserId).isNull()
        assertThat(player(t, t.g(3)).alive).isFalse()
    }
}
