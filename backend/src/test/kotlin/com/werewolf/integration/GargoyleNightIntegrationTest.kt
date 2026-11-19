package com.werewolf.integration

import com.werewolf.game.DomainEvent
import com.werewolf.game.night.NightOrchestrator
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
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

/**
 * Night-flow guarantees for the gargoyle (石像鬼) end to end, through POST /api/game/action:
 * its turn is the first of the night, a dead gargoyle's turn plays the same, the lone gargoyle
 * kills under the normal witch/guard rules, and its checks reach only the gargoyle and the review.
 *
 * Context config deliberately mirrors [MechWolfMediumNightIntegrationTest] (same annotations,
 * same single `@MockitoSpyBean StompPublisher`) so they share one cached Spring context and one
 * DB pool (CI Postgres connection budget).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(180)
class GargoyleNightIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var nightOrchestrator: NightOrchestrator

    @MockitoSpyBean lateinit var stompPublisher: StompPublisher

    companion object {
        private const val START_URL = "/api/game/start"
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val ACTION_URL = "/api/game/action"

        /** The 石像鬼守墓人 board plus a guard: 3 wolves + gargoyle, seer, witch, hunter, gravekeeper, guard, 3 villagers. */
        private val GRAVEKEEPER_BOARD = listOf("GARGOYLE", "SEER", "WITCH", "HUNTER", "GRAVEKEEPER", "GUARD")
        private val MEDIUM_BOARD = listOf("GARGOYLE", "MEDIUM", "WITCH", "HUNTER", "GRAVEKEEPER", "GUARD")
        private val KNIGHT_BOARD = listOf("GARGOYLE", "SEER", "WITCH", "HUNTER", "KNIGHT", "GUARD")
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    private class Game(val hostToken: String, val gameId: Int, val tokenByUserId: Map<String, String>) {
        lateinit var players: List<GamePlayer>
        fun first(role: PlayerRole) = players.first { it.role == role }
        fun has(role: PlayerRole) = players.any { it.role == role }
        fun token(p: GamePlayer) = tokenByUserId.getValue(p.userId)
        val pack get() = players.filter { it.role.isPackWolf }
        val gargoyle get() = first(PlayerRole.GARGOYLE)
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

    private fun get(path: String, token: String): Map<*, *> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Nothing>(headers(token)), Map::class.java).body!!

    private fun getList(path: String, token: String): List<*> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Nothing>(headers(token)), List::class.java).body!!

    private fun state(g: Game, p: GamePlayer): Map<*, *> = get("/api/game/${g.gameId}/state", g.token(p))

    /** 12 players, 4 wolves (the gargoyle + 3 pack), no sheriff; roles confirmed. */
    private fun setupGame(prefix: String, roles: List<String> = GRAVEKEEPER_BOARD): Game {
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
        return Game(hostToken, gameId, tokens).also { it.players = gamePlayerRepository.findByGameId(gameId) }
    }

    /** Dead before night 1 (diedDay 0), so the review also counts them out from the first night. */
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

    /** Every public `/topic/game/{id}` payload broadcast so far, in order. */
    private fun broadcasts(gameId: Int): List<Any> =
        mockingDetails(stompPublisher).invocations
            .filter { it.method.name == "broadcastGame" && it.arguments[0] == gameId }
            .map { it.arguments[1] }

    private fun privatePushes(): List<Pair<Any?, Any?>> =
        mockingDetails(stompPublisher).invocations
            .filter { it.method.name == "sendPrivateAfterCommit" }
            .map { it.arguments[0] to it.arguments[1] }

    private fun isDawnAudio(e: Any) = e is DomainEvent.AudioSequence &&
        e.audioSequence.phase == GamePhase.DAY_DISCUSSION && e.audioSequence.subPhase == DaySubPhase.RESULT_HIDDEN.name

    private fun waitForNightOver(g: Game, day: Int) =
        waitFor("dawn of night $day") { broadcasts(g.gameId).count(::isDawnAudio) >= day }

    /** One night through every role's turn. [gargoyleAction] = (actionType, target), or null when it is dead. */
    private fun driveNight(
        g: Game, day: Int,
        gargoyleAction: Pair<String, String?>?,
        killer: GamePlayer, killTarget: GamePlayer,
        witchSaves: Boolean = false,
        guardTarget: GamePlayer? = null,
        onGargoyleResult: () -> Unit = {},
        waitForDawn: Boolean = true,
    ) {
        if (gargoyleAction != null) {
            waitForSubPhase(g, day, NightSubPhase.GARGOYLE_PICK)
            ok(g.token(g.gargoyle), g.gameId, gargoyleAction.first, gargoyleAction.second)
            waitForSubPhase(g, day, NightSubPhase.GARGOYLE_RESULT)
            onGargoyleResult()
            ok(g.token(g.gargoyle), g.gameId, "GARGOYLE_CONFIRM")
        }

        waitForSubPhase(g, day, NightSubPhase.WEREWOLF_PICK)
        ok(g.token(killer), g.gameId, "WOLF_KILL", killTarget.userId)

        waitForSubPhase(g, day, NightSubPhase.WITCH_ACT)
        ok(g.token(g.first(PlayerRole.WITCH)), g.gameId, "WITCH_ACT", payload = mapOf("useAntidote" to witchSaves))

        val checkTarget = g.first(PlayerRole.HUNTER).userId
        if (g.has(PlayerRole.SEER)) {
            waitForSubPhase(g, day, NightSubPhase.SEER_PICK)
            ok(g.token(g.first(PlayerRole.SEER)), g.gameId, "SEER_CHECK", checkTarget)
            waitForSubPhase(g, day, NightSubPhase.SEER_RESULT)
            ok(g.token(g.first(PlayerRole.SEER)), g.gameId, "SEER_CONFIRM")
        }
        if (g.has(PlayerRole.MEDIUM)) {
            waitForSubPhase(g, day, NightSubPhase.MEDIUM_PICK)
            ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CHECK", g.gargoyle.userId)
            waitForSubPhase(g, day, NightSubPhase.MEDIUM_RESULT)
            ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CONFIRM")
        }
        if (g.has(PlayerRole.GRAVEKEEPER)) {
            waitForSubPhase(g, day, NightSubPhase.GRAVEKEEPER_RESULT)
            ok(g.token(g.first(PlayerRole.GRAVEKEEPER)), g.gameId, "GRAVEKEEPER_CONFIRM")
        }

        waitForSubPhase(g, day, NightSubPhase.GUARD_PICK)
        val guard = g.first(PlayerRole.GUARD)
        if (guardTarget != null) ok(g.token(guard), g.gameId, "GUARD_PROTECT", guardTarget.userId)
        else ok(g.token(guard), g.gameId, "GUARD_SKIP")

        if (waitForDawn) waitForNightOver(g, day)
    }

    /** Public broadcasts from the night's entry on, as comparable strings (no ids, timestamps or timer ticks). */
    private fun broadcastFingerprint(gameId: Int): List<String> =
        broadcasts(gameId)
            .dropWhile { !(it is DomainEvent.PhaseChanged && it.phase == GamePhase.NIGHT) }
            .filter { it !is DomainEvent.TimerUpdated }
            .map {
                when (it) {
                    is DomainEvent.PhaseChanged -> "PhaseChanged:${it.phase}/${it.subPhase}"
                    is DomainEvent.NightSubPhaseChanged -> "NightSubPhaseChanged:${it.subPhase}"
                    is DomainEvent.AudioSequence -> it.audioSequence.let { a ->
                        "AudioSequence:${a.phase}/${a.subPhase}/${a.audioFiles}/priority=${a.priority}"
                    }
                    else -> it.javaClass.simpleName
                }
            }

    private fun alive(g: Game, p: GamePlayer) = gamePlayerRepository.findByGameIdAndUserId(g.gameId, p.userId).orElseThrow().alive

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `G9 - full night order with the gargoyle first, and its result is pushed only to the gargoyle`() {
        val g = setupGame("GNO")
        val seer = g.first(PlayerRole.SEER)
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, "GARGOYLE_CHECK" to seer.userId, g.pack.first(), g.villagers[0])

        val subPhases = broadcasts(g.gameId).mapNotNull { (it as? DomainEvent.NightSubPhaseChanged)?.subPhase }
        assertThat(subPhases).containsExactly(
            NightSubPhase.GARGOYLE_PICK, NightSubPhase.GARGOYLE_RESULT,
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT,
            NightSubPhase.GRAVEKEEPER_RESULT,
            NightSubPhase.GUARD_PICK,
        )
        assertThat(broadcastFingerprint(g.gameId).joinToString("\n")).contains("[gargoyle_open_eyes.mp3]", "[gargoyle_close_eyes.mp3]")

        // G3 + G2: the exact role goes to the gargoyle alone, never to the table.
        val results = privatePushes().filter { it.second is DomainEvent.GargoyleResult }
        assertThat(results).containsExactly(g.gargoyle.userId to DomainEvent.GargoyleResult(g.gameId, seer.userId, PlayerRole.SEER))
        assertThat(broadcasts(g.gameId)).noneMatch { it is DomainEvent.GargoyleResult }
        // G2: no pick broadcast reaches the gargoyle while the pack lives.
        assertThat(privatePushes().filter { it.first == g.gargoyle.userId && it.second is DomainEvent.WolfSelectionChanged }).isEmpty()
    }

    @Test
    fun `G11 - the broadcast sequence is identical whether the gargoyle checks, skips or is dead`() {
        fun runNight1(prefix: String, action: (Game) -> Pair<String, String?>?, dead: Boolean = false): List<String> {
            val g = setupGame(prefix)
            if (dead) kill(g.gargoyle)
            ok(g.hostToken, g.gameId, "START_NIGHT")
            driveNight(g, 1, action(g), g.pack.first(), g.villagers[0])
            return broadcastFingerprint(g.gameId)
        }

        val checks = runNight1("GSC", { g -> "GARGOYLE_CHECK" to g.first(PlayerRole.SEER).userId })
        val skips = runNight1("GSS", { "GARGOYLE_SKIP" to null })
        val dead = runNight1("GSD", { null }, dead = true)

        assertThat(checks).describedAs("dawn was captured").anyMatch { it.startsWith("AudioSequence:DAY_DISCUSSION/RESULT_HIDDEN") }
        assertThat(checks.joinToString("\n")).contains("NightSubPhaseChanged:GARGOYLE_PICK", "NightSubPhaseChanged:GARGOYLE_RESULT")
        assertThat(skips).describedAs("skip vs check").isEqualTo(checks)
        assertThat(dead).describedAs("dead vs check").isEqualTo(checks)
    }

    @Test
    fun `G4 G14 - night 2 refuses the player checked on night 1 and the state lists them`() {
        val g = setupGame("GRC")
        val seer = g.first(PlayerRole.SEER)
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, "GARGOYLE_CHECK" to seer.userId, g.pack.first(), g.villagers[0])
        // Move to night 2 the way MechWolfMediumNightIntegrationTest does.
        val n1 = nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow()
        nightOrchestrator.applyNightKills(g.gameId, nightOrchestrator.computePendingKills(n1, emptySet(), emptyList()))
        nightOrchestrator.initNight(g.gameId, 2)

        waitForSubPhase(g, 2, NightSubPhase.GARGOYLE_PICK)
        @Suppress("UNCHECKED_CAST")
        val gargoyle = (state(g, g.gargoyle)["nightPhase"] as Map<String, Any?>)["gargoyle"] as Map<String, Any?>
        assertThat(gargoyle["checkedPlayerIds"] as List<*>).containsExactly(seer.userId)
        assertThat(act(g.token(g.gargoyle), g.gameId, "GARGOYLE_CHECK", seer.userId).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        // A fresh target is accepted; let the night finish.
        driveNight(g, 2, "GARGOYLE_CHECK" to g.first(PlayerRole.WITCH).userId, g.pack.first(), g.villagers[1])
        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 2).orElseThrow().gargoyleResultRole).isEqualTo(PlayerRole.WITCH)
    }

    @Test
    fun `G8 - the lone gargoyle's kill is saved by the witch`() {
        val g = setupGame("GLW")
        kill(*g.pack.toTypedArray())
        val victim = g.villagers[0]
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, "GARGOYLE_SKIP" to null, g.gargoyle, victim, witchSaves = true)

        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow().wolfTargetUserId).isEqualTo(victim.userId)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(alive(g, victim)).describedAs("witch saved the lone gargoyle's target").isTrue()
    }

    @Test
    fun `G8 - the lone gargoyle's kill is blocked by the guard`() {
        val g = setupGame("GLG")
        kill(*g.pack.toTypedArray())
        val victim = g.villagers[0]
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, "GARGOYLE_SKIP" to null, g.gargoyle, victim, guardTarget = victim)

        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(alive(g, victim)).describedAs("guard blocked the lone gargoyle's target").isTrue()
    }

    @Test
    fun `G8 G12 G15 - the lone gargoyle's kill wins for the wolves, and the review shows its check`() {
        val g = setupGame("GWN")
        val seer = g.first(PlayerRole.SEER)
        val (v1, v2, v3) = g.villagers
        kill(*g.pack.toTypedArray(), v1, v2)
        ok(g.hostToken, g.gameId, "START_NIGHT")

        // While the gargoyle waits in its own turn it is the killer already, and sees no teammates.
        driveNight(g, 1, "GARGOYLE_CHECK" to seer.userId, g.gargoyle, v3, onGargoyleResult = {
            @Suppress("UNCHECKED_CAST")
            val night = state(g, g.gargoyle)["nightPhase"] as Map<String, Any?>
            assertThat(night["wolfKiller"]).isEqualTo(true)
            assertThat(night).doesNotContainKey("teammates")
        }, waitForDawn = false)

        // CLASSIC 屠边: every villager is dead and the gargoyle counts as a wolf.
        waitFor("game over") { gameRepository.findById(g.gameId).orElseThrow().phase == GamePhase.GAME_OVER }
        assertThat(gameRepository.findById(g.gameId).orElseThrow().winner).isEqualTo(WinnerSide.WEREWOLF)
        assertThat(alive(g, v3)).isFalse()

        // God's-eye review: the night carries the gargoyle's check with the exact role.
        @Suppress("UNCHECKED_CAST")
        val night1 = ((get("/api/game/${g.gameId}/review", g.hostToken)["rounds"] as List<Map<String, Any?>>)
            .first { it["day"] == 1 }["night"]) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val turn = night1["gargoyle"] as Map<String, Any?>
        assertThat((turn["actor"] as Map<*, *>)["userId"]).isEqualTo(g.gargoyle.userId)
        assertThat((turn["target"] as Map<*, *>)["userId"]).isEqualTo(seer.userId)
        assertThat(turn["role"]).isEqualTo("SEER")
        assertThat(turn["dead"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        assertThat(((night1["wolf"] as Map<String, Any?>)["actors"] as List<Map<String, Any?>>).map { it["userId"] })
            .containsExactly(g.gargoyle.userId)

        // The gargoyle's own review: its check and its kill.
        @Suppress("UNCHECKED_CAST")
        val mine = ((get("/api/game/${g.gameId}/review/me", g.token(g.gargoyle))["rounds"] as List<Map<String, Any?>>)
            .first { it["day"] == 1 }["night"] as Map<String, Any?>)["action"] as Map<String, Any?>
        assertThat(mine["type"]).isEqualTo("GARGOYLE")
        assertThat((mine["target"] as Map<*, *>)["userId"]).isEqualTo(seer.userId)
        assertThat(mine["role"]).isEqualTo("SEER")
        assertThat((mine["kill"] as Map<*, *>)["userId"]).isEqualTo(v3.userId)

        // The public action log never shows a check.
        val log = getList("/api/game/${g.gameId}/events", g.hostToken).toString()
        assertThat(log).doesNotContain("GARGOYLE_CHECK").doesNotContainIgnoringCase("gargoyleChecked")
    }

    @Test
    fun `G7 - the medium sees GARGOYLE`() {
        val g = setupGame("GMD", MEDIUM_BOARD)
        ok(g.hostToken, g.gameId, "START_NIGHT")
        // driveNight's medium checks the gargoyle.
        driveNight(g, 1, "GARGOYLE_SKIP" to null, g.pack.first(), g.villagers[0])

        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow().mediumResultRole)
            .isEqualTo(PlayerRole.GARGOYLE)
    }

    @Test
    fun `a knight's duel on the gargoyle kills it`() {
        val g = setupGame("GKD", KNIGHT_BOARD)
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, "GARGOYLE_SKIP" to null, g.pack.first(), g.villagers[0])
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")

        ok(g.token(g.first(PlayerRole.KNIGHT)), g.gameId, "KNIGHT_DUEL", g.gargoyle.userId)

        assertThat(alive(g, g.gargoyle)).isFalse()
        assertThat(alive(g, g.first(PlayerRole.KNIGHT))).describedAs("a wolf duel leaves the knight alive").isTrue()
    }
}
