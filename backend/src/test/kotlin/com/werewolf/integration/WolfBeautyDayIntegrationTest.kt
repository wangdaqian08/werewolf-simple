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
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.VotingSubPhase
import com.werewolf.model.WinnerSide
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GameEventRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * The 狼美人's day 殉情 end to end, through POST /api/game/action: a real game in which it charms
 * on night 2 and is voted out on day 2 (exit X2, the W15 order), and the two exits that never
 * take the charmed player along (X7 duel, X8 host declare-out). Also the shots on the 狼美人
 * (X3, X4/Q10, X5), the W15 night win, the HARD_MODE first check (Q12) and the charmed hunter (W6).
 *
 * Plain context config (no spy beans), shared with the other plain integration tests.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(180)
class WolfBeautyDayIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var gameEventRepository: GameEventRepository
    @Autowired lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Autowired lateinit var gameService: GameService

    companion object {
        private const val START_URL = "/api/game/start"
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val ACTION_URL = "/api/game/action"

        /** 4 wolves (狼美人 + 3 pack), 4 gods, 4 villagers. */
        private val BOARD = listOf("WOLF_BEAUTY", "SEER", "WITCH", "HUNTER", "GUARD")
        private val KNIGHT_BOARD = listOf("WOLF_BEAUTY", "SEER", "WITCH", "KNIGHT", "GUARD")
        private val KING_BOARD = listOf("WOLF_BEAUTY", "WHITE_WOLF_KING", "SEER", "WITCH", "GUARD")
    }

    private class Game(val hostId: String, val hostToken: String, val gameId: Int, val tokenByUserId: Map<String, String>) {
        lateinit var players: List<GamePlayer>
        fun first(role: PlayerRole) = players.first { it.role == role }
        fun token(p: GamePlayer) = tokenByUserId.getValue(p.userId)
        val beauty get() = first(PlayerRole.WOLF_BEAUTY)
        val werewolves get() = players.filter { it.role == PlayerRole.WEREWOLF }
        val villagers get() = players.filter { it.role == PlayerRole.VILLAGER }
    }

    private fun login(nickname: String): Pair<String, String> {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        return (body[FIELD_TOKEN] as String) to ((body["user"] as Map<*, *>)["userId"] as String)
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun act(token: String, gameId: Int, actionType: String, target: String? = null, payload: Map<String, Any?>? = null) =
        restTemplate.postForEntity(
            ACTION_URL,
            HttpEntity(
                buildMap<String, Any?> {
                    put("gameId", gameId)
                    put("actionType", actionType)
                    if (target != null) put("targetUserId", target)
                    if (payload != null) put("payload", payload)
                },
                headers(token),
            ),
            Map::class.java,
        )

    private fun ok(token: String, gameId: Int, actionType: String, target: String? = null, payload: Map<String, Any?>? = null) {
        val resp = act(token, gameId, actionType, target, payload)
        assertThat(resp.statusCode).describedAs("$actionType accepted, body=${resp.body}").isEqualTo(HttpStatus.OK)
    }

    /** 12 players, no sheriff; roles confirmed, game in ROLE_REVEAL. */
    private fun setupGame(prefix: String, roles: List<String>, winCondition: String = "CLASSIC"): Game {
        val (hostToken, hostId) = login("${prefix}H")
        val guests = (1..11).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        "perksAllowed" to false,
                        FIELD_TOTAL_PLAYERS to 12,
                        "wolfCount" to 4,
                        "roles" to roles,
                        "hasSheriff" to false,
                        "guardSkipAllowed" to true,
                        "winCondition" to winCondition,
                    ),
                ),
                headers(hostToken),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>
        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)), Map::class.java)
        guests.forEachIndexed { idx, (t, _) ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(t)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(t)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(t)), Map::class.java)
        }
        val tokens = buildMap { put(hostId, hostToken); guests.forEach { (t, id) -> put(id, t) } }

        assertThat(restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java).statusCode)
            .isEqualTo(HttpStatus.OK)
        val gameId = gameRepository.findAll().first { it.roomId == roomId }.gameId!!
        tokens.values.forEach { ok(it, gameId, "CONFIRM_ROLE") }
        return Game(hostId, hostToken, gameId, tokens).also { it.players = gamePlayerRepository.findByGameId(gameId) }
    }

    /** Dead before night 1. */
    private fun kill(vararg ps: GamePlayer) = ps.forEach { gamePlayerRepository.save(it.also { p -> p.alive = false; p.diedDay = 0 }) }

    private fun waitFor(what: String, timeoutMs: Long = 30_000L, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (done()) return
            Thread.sleep(100)
        }
        error("$what did not happen within ${timeoutMs}ms")
    }

    private fun waitForSubPhase(g: Game, day: Int, target: NightSubPhase) =
        waitFor("night $day reaching $target") {
            nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, day).orElse(null)?.subPhase == target
        }

    private fun waitForDawn(g: Game, day: Int) = waitFor("dawn (or game over) of night $day") {
        gameRepository.findById(g.gameId).orElseThrow().let {
            it.phase == GamePhase.GAME_OVER || (it.phase == GamePhase.DAY_DISCUSSION && it.dayNumber == day)
        }
    }

    /** One night: the 狼美人 knifes, charms or skips; a living seer checks it; a living witch acts with [witch]. */
    private fun driveNight(g: Game, day: Int, knifeTarget: GamePlayer, charm: GamePlayer?, witch: Map<String, Any?>? = null) {
        waitForSubPhase(g, day, NightSubPhase.WEREWOLF_PICK)
        ok(g.token(g.beauty), g.gameId, "WOLF_KILL", knifeTarget.userId)
        waitForSubPhase(g, day, NightSubPhase.WOLF_BEAUTY_PICK)
        if (charm != null) ok(g.token(g.beauty), g.gameId, "WOLF_BEAUTY_CHARM", charm.userId)
        else ok(g.token(g.beauty), g.gameId, "WOLF_BEAUTY_SKIP")
        if (witch != null) {
            waitForSubPhase(g, day, NightSubPhase.WITCH_ACT)
            ok(g.token(g.first(PlayerRole.WITCH)), g.gameId, "WITCH_ACT", payload = witch)
        }
        val seer = g.first(PlayerRole.SEER)
        if (alive(g, seer)) {
            waitForSubPhase(g, day, NightSubPhase.SEER_PICK)
            ok(g.token(seer), g.gameId, "SEER_CHECK", g.beauty.userId)
            waitForSubPhase(g, day, NightSubPhase.SEER_RESULT)
            ok(g.token(seer), g.gameId, "SEER_CONFIRM")
        }
        waitForDawn(g, day)
    }

    /** Overwrite a player's (val) role in the DB. */
    private fun setRole(p: GamePlayer, role: PlayerRole) {
        val row = gamePlayerRepository.findByGameIdAndUserId(p.gameId, p.userId).orElseThrow()
        GamePlayer::class.java.getDeclaredField("role").also { it.isAccessible = true }.set(row, role)
        gamePlayerRepository.save(row)
    }

    private fun alive(g: Game, p: GamePlayer) = gamePlayerRepository.findByGameIdAndUserId(g.gameId, p.userId).orElseThrow().alive

    /** Park the game on day 2 after the reveal, with the 狼美人's night-2 charm on [charmed]. */
    private fun parkDay2WithCharm(g: Game, charmed: GamePlayer) {
        nightPhaseRepository.save(NightPhase(gameId = g.gameId, dayNumber = 2).also { it.wolfBeautyCharmUserId = charmed.userId })
        val game = gameRepository.findById(g.gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        game.dayNumber = 2
        gameRepository.save(game)
    }

    /** The public 游戏记录 as GET /api/game/{id}/events serves it. */
    private fun publicLog(g: Game): List<Map<*, *>> =
        restTemplate.exchange("/api/game/${g.gameId}/events", HttpMethod.GET, HttpEntity<Void>(headers(g.hostToken)), List::class.java)
            .body!!.map { it as Map<*, *> }

    private fun assertNoCharmDeath(g: Game, charmed: GamePlayer) {
        assertThat(alive(g, charmed)).describedAs("no 殉情").isTrue()
        assertThat(gameRepository.findById(g.gameId).orElseThrow().charmDeathUserId).isNull()
        assertThat(gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(g.gameId)).noneMatch { it.eventType == "CHARM_DEATH" }
    }

    /** Night 1 (knife [knifeTarget], no charm), then a day 1 with no vote-out: the game is in night 2. */
    private fun playNight1AndEmptyDay1(g: Game, knifeTarget: GamePlayer, witch: Map<String, Any?>? = null) {
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, knifeTarget = knifeTarget, charm = null, witch = witch)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
    }

    /** Day 2 vote: every living player but [target] votes [target] out. */
    private fun voteOut(g: Game, target: GamePlayer) {
        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        g.players.filter { it.userId != target.userId && alive(g, it) }.forEach { ok(g.token(it), g.gameId, "SUBMIT_VOTE", target.userId) }
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
    }

    private fun charmDeathTargets(g: Game) =
        gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(g.gameId).filter { it.eventType == "CHARM_DEATH" }.map { it.targetUserId }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `X2 W15 - charmed on night 2, voted out on day 2 - the last god 殉情 and the wolves win`() {
        val g = setupGame("WBD", BOARD)
        val seer = g.first(PlayerRole.SEER)
        val (v0, v1, v2, v3) = g.villagers
        // The 狼美人 is the last wolf and the seer is the last god.
        kill(*g.werewolves.toTypedArray(), g.first(PlayerRole.WITCH), g.first(PlayerRole.HUNTER), g.first(PlayerRole.GUARD))

        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, knifeTarget = v0, charm = null)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        // Day 1: no vote-out (two empty rounds) → night 2.
        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")

        driveNight(g, 2, knifeTarget = v1, charm = seer)
        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 2).orElseThrow().wolfBeautyCharmUserId).isEqualTo(seer.userId)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(gameRepository.findById(g.gameId).orElseThrow().phase).isEqualTo(GamePhase.DAY_DISCUSSION)

        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        listOf(seer, v2, v3).forEach { ok(g.token(it), g.gameId, "SUBMIT_VOTE", g.beauty.userId) }
        ok(g.token(g.beauty), g.gameId, "SUBMIT_VOTE", v2.userId)
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, seer)).describedAs("the charmed seer 殉情").isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.charmDeathUserId).isEqualTo(seer.userId)
        // W15: the 殉情 wipes the gods with the 狼美人 still in; the villagers' check never runs.
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.winner).isEqualTo(WinnerSide.WEREWOLF)
        val charmEvents = gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(g.gameId).filter { it.eventType == "CHARM_DEATH" }
        assertThat(charmEvents.map { it.targetUserId }).containsExactly(seer.userId)
        // W13: only the 狼美人 is a vote-out.
        assertThat(eliminationHistoryRepository.findByGameId(g.gameId).map { it.eliminatedUserId }).containsExactly(g.beauty.userId)
    }

    @Test
    fun `B Q13 - the vote-out 殉情 banner names both seats, survives a reload and is cleared at night`() {
        val g = setupGame("WBB", BOARD)
        val charmed = g.villagers.first { it.userId != g.hostId }
        parkDay2WithCharm(g, charmed)

        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        g.players.filter { it.userId != g.beauty.userId }.forEach { ok(g.token(it), g.gameId, "SUBMIT_VOTE", g.beauty.userId) }
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
        assertThat(alive(g, charmed)).describedAs("the charmed villager 殉情").isFalse()

        @Suppress("UNCHECKED_CAST")
        val voting = gameService.getGameState(g.gameId, g.hostId)["votingPhase"] as Map<String, Any?>
        // setupGame seats the host at 0 ("WBBH") and guest N at seat N ("WBBGN").
        fun nick(p: GamePlayer) = if (p.seatIndex == 0) "WBBH" else "WBBG${p.seatIndex}"
        assertThat(voting["charmDeath"]).isEqualTo(
            mapOf(
                "beautySeatIndex" to g.beauty.seatIndex, "beautyNickname" to nick(g.beauty),
                "victimSeatIndex" to charmed.seatIndex, "victimNickname" to nick(charmed),
            ),
        )

        ok(g.hostToken, g.gameId, "VOTING_CONTINUE")
        val night = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(night.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(night.charmDeathUserId).isNull()
    }

    @Test
    fun `X7 - a knight's duel on the 狼美人 takes no one along`() {
        val g = setupGame("WBK", KNIGHT_BOARD)
        val charmed = g.villagers.first()
        parkDay2WithCharm(g, charmed)

        ok(g.token(g.first(PlayerRole.KNIGHT)), g.gameId, "KNIGHT_DUEL", g.beauty.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        assertNoCharmDeath(g, charmed)
    }

    @Test
    fun `X8 - a host declare-out of the 狼美人 takes no one along`() {
        val g = setupGame("WBX", BOARD)
        // The host cannot declare themselves out: a dealt seat-0 狼美人 swaps roles with a villager.
        if (g.beauty.userId == g.hostId) {
            val villager = g.villagers.first()
            setRole(g.beauty, PlayerRole.VILLAGER)
            setRole(villager, PlayerRole.WOLF_BEAUTY)
            g.players = gamePlayerRepository.findByGameId(g.gameId)
        }
        val charmed = g.villagers.first { it.userId != g.hostId }
        parkDay2WithCharm(g, charmed)

        ok(g.hostToken, g.gameId, "HOST_DECLARE_OUT", g.beauty.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        assertNoCharmDeath(g, charmed)
    }

    @Test
    fun `X1 - a witch-poisoned 狼美人 takes its night-2 charm along at dawn, kept out of the public log`() {
        val g = setupGame("WBP", BOARD)
        val (v0, v1, v2) = g.villagers
        // One pack wolf stays, so the poisoned 狼美人 does not end the game at night.
        kill(*g.werewolves.drop(1).toTypedArray(), g.first(PlayerRole.GUARD))
        val noPotion = mapOf("useAntidote" to false)

        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, knifeTarget = v0, charm = null, witch = noPotion)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        ok(g.hostToken, g.gameId, "DAY_ADVANCE")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")
        ok(g.hostToken, g.gameId, "VOTING_REVEAL_TALLY")

        driveNight(g, 2, knifeTarget = v1, charm = v2, witch = noPotion + ("poisonTargetUserId" to g.beauty.userId))
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")

        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        @Suppress("UNCHECKED_CAST")
        val dayPhase = gameService.getGameState(g.gameId, g.hostId)["dayPhase"] as Map<String, Any?>
        val killed = ((dayPhase["nightResult"] as Map<*, *>)["killedPlayers"] as List<*>).map { (it as Map<*, *>)["killedPlayerId"] }
        assertThat(killed).describedAs("the 殉情 is a plain name in the dawn list").containsExactlyInAnyOrder(v1.userId, g.beauty.userId, v2.userId)
        assertThat(alive(g, v2)).isFalse()
        val charmEvents = gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(g.gameId).filter { it.eventType == "CHARM_DEATH" }
        assertThat(charmEvents.map { it.targetUserId }).containsExactly(v2.userId)
        // W11/W14: the public log shows the dawn deaths, never which one was a 殉情.
        val log = publicLog(g)
        assertThat(log.map { it["eventType"] }).contains("NIGHT_DEATH").doesNotContain("CHARM_DEATH")
    }

    @Test
    fun `X6 - a 白狼王 taking the 狼美人 takes its charmed sheriff too, who hands over the badge`() {
        val g = setupGame("WBW", KING_BOARD)
        val sheriff = g.villagers.first()
        parkDay2WithCharm(g, sheriff)
        gameRepository.save(gameRepository.findById(g.gameId).orElseThrow().also { it.sheriffUserId = sheriff.userId })
        gamePlayerRepository.save(gamePlayerRepository.findByGameIdAndUserId(g.gameId, sheriff.userId).orElseThrow().also { it.sheriff = true })

        ok(g.token(g.first(PlayerRole.WHITE_WOLF_KING)), g.gameId, "WOLF_SELF_DESTRUCT", g.beauty.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, sheriff)).describedAs("the charmed sheriff 殉情").isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.charmDeathUserId).isEqualTo(sheriff.userId)
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        val charmEvents = gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(g.gameId).filter { it.eventType == "CHARM_DEATH" }
        assertThat(charmEvents.map { it.targetUserId }).containsExactly(sheriff.userId)
        // A day 殉情 is public (Q13): the 游戏记录 keeps it.
        assertThat(publicLog(g).filter { it["eventType"] == "CHARM_DEATH" }.map { it["targetUserId"] }).containsExactly(sheriff.userId)
    }

    @Test
    fun `C7 X3 - a voted-out hunter shoots the 狼美人 - its night-2 charm 殉情 at the shot`() {
        val g = setupGame("WC7", BOARD)
        val hunter = g.first(PlayerRole.HUNTER)
        val charmed = g.villagers.first()
        parkDay2WithCharm(g, charmed)

        voteOut(g, hunter)
        assertThat(alive(g, hunter)).isFalse()
        assertThat(gameRepository.findById(g.gameId).orElseThrow().pendingShooterUserId).isEqualTo(hunter.userId)
        ok(g.token(hunter), g.gameId, "HUNTER_SHOOT", g.beauty.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, charmed)).describedAs("the charmed villager 殉情").isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.charmDeathUserId).isEqualTo(charmed.userId)
        assertThat(charmDeathTargets(g)).containsExactly(charmed.userId)
        assertThat(game.pendingShooterUserId).describedAs("the shot is spent and the 殉情 gets none").isNull()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(game.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    @Test
    fun `C8 X4 Q10 - the shot sheriff 狼美人 - the charmed player dies at the shot and cannot take the badge`() {
        val g = setupGame("WC8", BOARD)
        val hunter = g.first(PlayerRole.HUNTER)
        val (charmed, heir) = g.villagers
        parkDay2WithCharm(g, charmed)
        gameRepository.save(gameRepository.findById(g.gameId).orElseThrow().also { it.sheriffUserId = g.beauty.userId })
        gamePlayerRepository.save(gamePlayerRepository.findByGameIdAndUserId(g.gameId, g.beauty.userId).orElseThrow().also { it.sheriff = true })

        voteOut(g, hunter)
        ok(g.token(hunter), g.gameId, "HUNTER_SHOOT", g.beauty.userId)

        assertThat(alive(g, charmed)).describedAs("Q10: the 殉情 happens at the shot").isFalse()
        assertThat(alive(g, g.beauty)).describedAs("the sheriff hands over first").isTrue()
        val handover = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(handover.subPhase).isEqualTo(VotingSubPhase.BADGE_HANDOVER.name)
        assertThat(handover.charmDeathUserId).isEqualTo(charmed.userId)
        assertThat(charmDeathTargets(g)).containsExactly(charmed.userId)

        assertThat(act(g.token(g.beauty), g.gameId, "BADGE_PASS", charmed.userId).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        ok(g.token(g.beauty), g.gameId, "BADGE_PASS", heir.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.sheriffUserId).isEqualTo(heir.userId)
        assertThat(game.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    @Test
    fun `C9 X5 - a hunter knifed on night 2 shoots the 狼美人 at the reveal - its charm 殉情`() {
        val g = setupGame("WC9", BOARD)
        val hunter = g.first(PlayerRole.HUNTER)
        val (v0, charmed) = g.villagers
        kill(g.first(PlayerRole.WITCH), g.first(PlayerRole.GUARD))

        playNight1AndEmptyDay1(g, knifeTarget = v0)
        driveNight(g, 2, knifeTarget = hunter, charm = charmed)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(alive(g, hunter)).isFalse()
        assertThat(gameRepository.findById(g.gameId).orElseThrow().pendingShooterUserId).isEqualTo(hunter.userId)

        ok(g.token(hunter), g.gameId, "HUNTER_SHOOT", g.beauty.userId)

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, charmed)).describedAs("the charmed villager 殉情").isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.charmDeathUserId).isEqualTo(charmed.userId)
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(charmDeathTargets(g)).containsExactly(charmed.userId)
        @Suppress("UNCHECKED_CAST")
        val dayPhase = gameService.getGameState(g.gameId, g.hostId)["dayPhase"] as Map<String, Any?>
        val banner = dayPhase["charmDeath"] as Map<*, *>
        assertThat(banner["beautySeatIndex"]).isEqualTo(g.beauty.seatIndex)
        assertThat(banner["victimSeatIndex"]).isEqualTo(charmed.seatIndex)
    }

    @Test
    fun `C14 W15 - a poisoned last-wolf 狼美人 charming the last god wins for the wolves at night end`() {
        val g = setupGame("WC14", BOARD)
        val witch = g.first(PlayerRole.WITCH)
        val (v0, v1) = g.villagers
        // The 狼美人 is the last wolf and the witch is the last god.
        kill(*g.werewolves.toTypedArray(), g.first(PlayerRole.SEER), g.first(PlayerRole.HUNTER), g.first(PlayerRole.GUARD))
        val noPotion = mapOf("useAntidote" to false)

        playNight1AndEmptyDay1(g, knifeTarget = v0, witch = noPotion)
        driveNight(g, 2, knifeTarget = v1, charm = witch, witch = noPotion + ("poisonTargetUserId" to g.beauty.userId))

        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.winner).describedAs("W15: the 殉情 wipes the gods with the 狼美人 still in").isEqualTo(WinnerSide.WEREWOLF)
        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, witch)).describedAs("the charmed witch 殉情").isFalse()
        assertThat(charmDeathTargets(g)).containsExactly(witch.userId)
    }

    @Test
    fun `C15 Q12 - HARD_MODE, 2 villagers and the 狼美人 - its vote-out 殉情 gives no early wolf win`() {
        val g = setupGame("WC15", BOARD, winCondition = "HARD_MODE")
        val (charmed, other, v2, v3) = g.villagers
        // Alive: the 狼美人 and two villagers; no counterplay left, so the HARD_MODE shortcut would fire if used.
        kill(*g.werewolves.toTypedArray(), *g.players.filter { it.role.isGod }.toTypedArray(), v2, v3)
        parkDay2WithCharm(g, charmed)

        voteOut(g, g.beauty)

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, charmed)).describedAs("the charmed villager 殉情").isFalse()
        assertThat(alive(g, other)).isTrue()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.charmDeathUserId).isEqualTo(charmed.userId)
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.winner).describedAs("Q12: the first check finds no wiped side; then no wolf is left").isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `C16 W6 - a charmed hunter knifed the night the 狼美人 is poisoned gets no shot`() {
        val g = setupGame("WC16", BOARD)
        val hunter = g.first(PlayerRole.HUNTER)
        val v0 = g.villagers.first()
        kill(g.first(PlayerRole.GUARD))
        val noPotion = mapOf("useAntidote" to false)

        playNight1AndEmptyDay1(g, knifeTarget = v0, witch = noPotion)
        driveNight(g, 2, knifeTarget = hunter, charm = hunter, witch = noPotion + ("poisonTargetUserId" to g.beauty.userId))
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")

        assertThat(alive(g, g.beauty)).isFalse()
        assertThat(alive(g, hunter)).isFalse()
        val game = gameRepository.findById(g.gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(game.pendingShooterUserId).describedAs("W6: a 殉情 hunter cannot shoot").isNull()
        @Suppress("UNCHECKED_CAST")
        val dayPhase = gameService.getGameState(g.gameId, hunter.userId)["dayPhase"] as Map<String, Any?>
        assertThat(dayPhase["canShoot"]).isEqualTo(false)
        assertThat(act(g.token(hunter), g.gameId, "HUNTER_SHOOT", g.villagers.last().userId).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(charmDeathTargets(g)).containsExactly(hunter.userId)
    }
}
