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
 * The knight's duel (骑士决斗) end to end: every action goes through
 * POST /api/game/action → GameActionDispatcher, against the real services (Spring + H2).
 * Covers the dispatcher's KNIGHT_DUEL route, D2 (a duelled wolf has no death skill),
 * D3 / row 7 (duel vs 自爆 the same day), K5 (badge handover) and K8 (an open night shot is lost).
 *
 * Boards have two wolves, so one wolf duel never ends the game.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class KnightDuelFlowIntegrationTest {

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

    private data class KnightGame(val all: List<TestPlayer>, val gameId: Int, val hostId: String, val players: List<GamePlayer>) {
        val knight get() = players.single { it.role == PlayerRole.KNIGHT }
        val wolves get() = players.filter { it.role.isWolf }
        fun one(role: PlayerRole) = players.single { it.role == role }
        fun token(userId: String) = all.first { it.userId == userId }.token
    }

    private fun login(nickname: String): TestPlayer {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        return TestPlayer(body[FIELD_TOKEN] as String, (body["user"] as Map<*, *>)["userId"] as String)
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun action(g: KnightGame, actorUserId: String, actionType: String, targetUserId: String? = null) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                mapOf("gameId" to g.gameId, "actionType" to actionType, "targetUserId" to targetUserId),
                headers(g.token(actorUserId)),
            ),
            Map::class.java,
        )

    /** 6 players, 2 wolves, sheriff enabled; [roles] picks the specials. Arranged at Day 2 with no sheriff election. */
    private fun startGame(prefix: String, roles: List<String>): KnightGame {
        val host = login("${prefix}H")
        val guests = (1..5).map { login("$prefix$it") }
        val all = listOf(host) + guests

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        "perksAllowed" to false,
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
        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!
        val g = KnightGame(all, gameId, game.hostUserId, gamePlayerRepository.findByGameId(gameId))
        all.forEach { action(g, it.userId, "CONFIRM_ROLE") }
        return g
    }

    /** Day 2 discussion in [subPhase]; last night is complete with [night] applied to its row. */
    private fun arrangeDay2(
        g: KnightGame,
        subPhase: DaySubPhase = DaySubPhase.RESULT_REVEALED,
        sheriffUserId: String? = null,
        night: NightPhase.() -> Unit = {},
    ) {
        val game = gameRepository.findById(g.gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = subPhase.name
        game.dayNumber = 2
        game.sheriffUserId = sheriffUserId
        gameRepository.save(game)
        if (sheriffUserId != null) {
            gamePlayerRepository.save(gamePlayerRepository.findByGameIdAndUserId(g.gameId, sheriffUserId).orElseThrow().also { it.sheriff = true })
        }
        nightPhaseRepository.save(NightPhase(gameId = g.gameId, dayNumber = 2).also { it.subPhase = NightSubPhase.COMPLETE; it.night() })
    }

    private fun game(g: KnightGame) = gameRepository.findById(g.gameId).orElseThrow()
    private fun alive(g: KnightGame, userId: String) = gamePlayerRepository.findByGameIdAndUserId(g.gameId, userId).orElseThrow().alive

    private fun assertOk(resp: org.springframework.http.ResponseEntity<Map<*, *>>) {
        assertThat(resp.statusCode).describedAs("body=%s", resp.body).isEqualTo(HttpStatus.OK)
    }

    private fun assertRejected(resp: org.springframework.http.ResponseEntity<Map<*, *>>) {
        assertThat(resp.statusCode).describedAs("body=%s", resp.body).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /** Duel [wolf] → no pending shot now; VOTING_CONTINUE → night 3, still none. */
    private fun assertDuelledWolfOpensNoShot(g: KnightGame, wolf: GamePlayer) {
        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", wolf.userId))
        assertThat(alive(g, wolf.userId)).isFalse()
        assertThat(game(g).pendingShooterUserId).isNull()

        assertOk(action(g, g.hostId, "VOTING_CONTINUE"))
        val after = game(g)
        assertThat(after.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(after.dayNumber).isEqualTo(3)
        assertThat(after.pendingShooterUserId).isNull()
    }

    // ── D2: a duelled wolf has no death skill ────────────────────────────────

    @Test
    fun `duelled BLACK_WOLF_KING gets no pending shot`() {
        val g = startGame("KD1", listOf("WEREWOLF", "BLACK_WOLF_KING", "KNIGHT", "SEER", "VILLAGER"))
        arrangeDay2(g)
        assertDuelledWolfOpensNoShot(g, g.one(PlayerRole.BLACK_WOLF_KING))
    }

    @Test
    fun `duelled mech wolf with SHOOT gets no pending shot`() {
        val g = startGame("KD2", listOf("WEREWOLF", "MECHANICAL_WOLF", "KNIGHT", "SEER", "VILLAGER"))
        val mech = gamePlayerRepository.save(
            gamePlayerRepository.findByGameIdAndUserId(g.gameId, g.one(PlayerRole.MECHANICAL_WOLF).userId).orElseThrow()
                .also { it.learnedRole = PlayerRole.HUNTER },
        )
        arrangeDay2(g)
        assertDuelledWolfOpensNoShot(g, mech)
    }

    @Test
    fun `duelled WHITE_WOLF_KING cannot self-destruct afterwards`() {
        val g = startGame("KD3", listOf("WEREWOLF", "WHITE_WOLF_KING", "KNIGHT", "SEER", "VILLAGER"))
        val king = g.one(PlayerRole.WHITE_WOLF_KING)
        val bystander = g.one(PlayerRole.SEER)
        arrangeDay2(g)

        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", king.userId))
        assertRejected(action(g, king.userId, "WOLF_SELF_DESTRUCT", bystander.userId))

        assertThat(alive(g, bystander.userId)).isTrue()
        assertThat(game(g).selfDestructUserId).isNull()
        assertThat(game(g).selfDestructTakenUserId).isNull()
    }

    // ── D3 / row 7: duel and 自爆 on the same day ────────────────────────────

    private val plainRoles = listOf("WEREWOLF", "KNIGHT", "SEER", "WITCH", "VILLAGER")

    @Test
    fun `self-destruct then duel same day - duel rejected`() {
        val g = startGame("KD4", plainRoles)
        val (boomer, other) = g.wolves
        arrangeDay2(g)

        assertOk(action(g, boomer.userId, "WOLF_SELF_DESTRUCT"))
        assertRejected(action(g, g.knight.userId, "KNIGHT_DUEL", other.userId))

        assertThat(alive(g, other.userId)).isTrue()
        assertThat(alive(g, g.knight.userId)).isTrue()
        assertThat(game(g).knightDuelTargetUserId).isNull()
    }

    @Test
    fun `duel then self-destruct same day - self-destruct rejected`() {
        val g = startGame("KD5", plainRoles)
        val (duelled, other) = g.wolves
        arrangeDay2(g)

        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", duelled.userId))
        assertRejected(action(g, other.userId, "WOLF_SELF_DESTRUCT"))

        assertThat(alive(g, other.userId)).isTrue()
        assertThat(game(g).selfDestructUserId).isNull()
    }

    // ── K3 / K4: what the day does next ──────────────────────────────────────

    @Test
    fun `after a wolf duel only VOTING_CONTINUE is accepted`() {
        val g = startGame("KD6", plainRoles)
        arrangeDay2(g)

        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", g.wolves.first().userId))
        assertThat(game(g).daySkipVoting).isTrue()

        assertRejected(action(g, g.hostId, "DAY_ADVANCE"))
        assertThat(game(g).phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertOk(action(g, g.hostId, "VOTING_CONTINUE"))
        assertThat(game(g).phase).isEqualTo(GamePhase.NIGHT)
    }

    @Test
    fun `after a failed duel the vote still happens`() {
        val g = startGame("KD7", plainRoles)
        val villager = g.one(PlayerRole.VILLAGER)
        arrangeDay2(g)

        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", villager.userId))
        assertThat(alive(g, g.knight.userId)).isFalse()
        assertThat(alive(g, villager.userId)).isTrue()

        assertOk(action(g, g.hostId, "DAY_ADVANCE"))
        val after = game(g)
        assertThat(after.phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(after.subPhase).isEqualTo(VotingSubPhase.VOTING.name)
    }

    // ── K8: an open night shot is lost ───────────────────────────────────────

    @Test
    fun `hunter's open night shot is dropped by a wolf duel`() {
        val g = startGame("KD8", listOf("WEREWOLF", "KNIGHT", "HUNTER", "SEER", "VILLAGER"))
        val hunter = g.one(PlayerRole.HUNTER)
        arrangeDay2(g, subPhase = DaySubPhase.RESULT_HIDDEN) { wolfTargetUserId = hunter.userId }

        // The real reveal applies the kill and opens the hunter's private shot.
        assertOk(action(g, g.hostId, "REVEAL_NIGHT_RESULT"))
        assertThat(alive(g, hunter.userId)).isFalse()
        assertThat(game(g).subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game(g).pendingShooterUserId).isEqualTo(hunter.userId)

        val duel = action(g, g.knight.userId, "KNIGHT_DUEL", g.wolves.first().userId)
        assertOk(duel)
        assertThat(duel.body).isEqualTo(mapOf("success" to true)) // nothing about the secret shooter

        assertOk(action(g, g.hostId, "VOTING_CONTINUE"))
        val after = game(g)
        assertThat(after.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(after.pendingShooterUserId).isNull()
    }

    // ── K5: badge handover ───────────────────────────────────────────────────

    @Test
    fun `badge handover after a duelled sheriff returns to RESULT_REVEALED with only VOTING_CONTINUE`() {
        val g = startGame("KD9", plainRoles)
        val sheriff = g.wolves.first()
        val heir = g.one(PlayerRole.SEER)
        arrangeDay2(g, sheriffUserId = sheriff.userId)

        assertOk(action(g, g.knight.userId, "KNIGHT_DUEL", sheriff.userId))
        val afterDuel = game(g)
        assertThat(afterDuel.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        assertThat(afterDuel.sheriffUserId).isEqualTo(sheriff.userId)

        assertOk(action(g, sheriff.userId, "BADGE_PASS", heir.userId))
        val afterBadge = game(g)
        assertThat(afterBadge.sheriffUserId).isEqualTo(heir.userId)
        assertThat(afterBadge.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(afterBadge.daySkipVoting).isTrue()

        assertRejected(action(g, g.hostId, "DAY_ADVANCE"))
        assertOk(action(g, g.hostId, "VOTING_CONTINUE"))
        assertThat(game(g).phase).isEqualTo(GamePhase.NIGHT)
    }
}
