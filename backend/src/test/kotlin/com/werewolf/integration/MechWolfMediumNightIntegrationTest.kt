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
import com.werewolf.model.GamePhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
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
 * Night-flow guarantees for the mechanical wolf and the medium: no tells in the broadcast
 * sequence, the lone mechanical wolf still kills, and the private state fields never leak
 * between the pack and the mechanical wolf.
 *
 * Context config deliberately mirrors [NightAudioSequenceGuaranteeIntegrationTest]
 * (same annotations, same single `@MockitoSpyBean StompPublisher`) so the two share one
 * cached Spring context and one DB pool (CI Postgres connection budget).
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(180)
class MechWolfMediumNightIntegrationTest {

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
    }

    // ── Setup helpers ─────────────────────────────────────────────────────────

    private class Game(
        val hostToken: String,
        val gameId: Int,
        val tokenByUserId: Map<String, String>,
    ) {
        lateinit var players: List<GamePlayer>
        fun first(role: PlayerRole) = players.first { it.role == role }
        fun token(p: GamePlayer) = tokenByUserId.getValue(p.userId)
        val packWolves get() = players.filter { it.role == PlayerRole.WEREWOLF }
        val mech get() = first(PlayerRole.MECHANICAL_WOLF)
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

    private fun act(
        token: String, gameId: Int, actionType: String,
        target: String? = null, payload: Map<String, Any?>? = null,
    ) = restTemplate.postForEntity(
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

    private fun ok(token: String, gameId: Int, actionType: String, target: String? = null, payload: Map<String, Any?>? = null) =
        assertThat(act(token, gameId, actionType, target, payload).statusCode)
            .describedAs("$actionType accepted").isEqualTo(HttpStatus.OK)

    private fun state(gameId: Int, token: String): Map<*, *> =
        restTemplate.exchange("/api/game/$gameId/state", HttpMethod.GET, HttpEntity<Nothing>(headers(token)), Map::class.java).body!!

    /**
     * 10 players: 3 wolves (mechanical wolf + 2 pack), witch, guard, medium, hunter, idiot, 2 villagers.
     * Stops right after the game starts (ROLE_REVEAL) unless [confirmRoles].
     */
    private fun setupGame(prefix: String, confirmRoles: Boolean = true): Game {
        val (hostToken, hostId) = login("${prefix}H")
        val guests = (1..9).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 10,
                    "wolfCount" to 3,
                    "roles" to listOf("MECHANICAL_WOLF", "WITCH", "GUARD", "MEDIUM", "HUNTER", "IDIOT"),
                    "hasSheriff" to false,
                    "guardSkipAllowed" to true,
                )),
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

        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        val gameId = gameRepository.findAll().first { it.roomId == roomId }.gameId!!
        if (confirmRoles) tokens.values.forEach { ok(it, gameId, "CONFIRM_ROLE") }
        return Game(hostToken, gameId, tokens).also { it.players = gamePlayerRepository.findByGameId(gameId) }
    }

    private fun waitForSubPhase(gameId: Int, day: Int, target: NightSubPhase, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (nightPhaseRepository.findByGameIdAndDayNumber(gameId, day).orElse(null)?.subPhase == target) {
                Thread.sleep(300) // the loop creates its action deferred right after writing the sub-phase
                return
            }
            Thread.sleep(100)
        }
        error("Night loop did not reach $target on day $day within ${timeoutMs}ms")
    }

    private fun waitForNightOver(gameId: Int, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (gameRepository.findById(gameId).orElseThrow().phase != GamePhase.NIGHT) {
                Thread.sleep(1_000) // let the dawn broadcasts land
                return
            }
            Thread.sleep(100)
        }
        error("Night did not resolve within ${timeoutMs}ms")
    }

    /**
     * Drives one full night through every role's sub-phases.
     * [mechAction] is (actionType, target) for an alive mechanical wolf, or null when it is dead.
     * [onMediumResult] runs while the loop waits in MEDIUM_RESULT.
     */
    private fun driveNight(
        g: Game, day: Int, killer: GamePlayer, killTarget: GamePlayer,
        mechAction: Pair<String, String?>?, mediumTarget: GamePlayer,
        onMediumResult: () -> Unit = {},
    ) {
        waitForSubPhase(g.gameId, day, NightSubPhase.WEREWOLF_PICK)
        ok(g.token(killer), g.gameId, "WOLF_KILL", killTarget.userId)

        if (mechAction != null) {
            waitForSubPhase(g.gameId, day, NightSubPhase.MECH_WOLF_PICK)
            ok(g.token(g.mech), g.gameId, mechAction.first, mechAction.second)
            waitForSubPhase(g.gameId, day, NightSubPhase.MECH_WOLF_RESULT)
            ok(g.token(g.mech), g.gameId, "MECH_CONFIRM")
        }

        waitForSubPhase(g.gameId, day, NightSubPhase.WITCH_ACT)
        ok(g.token(g.first(PlayerRole.WITCH)), g.gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false))

        val medium = g.first(PlayerRole.MEDIUM)
        waitForSubPhase(g.gameId, day, NightSubPhase.MEDIUM_PICK)
        ok(g.token(medium), g.gameId, "MEDIUM_CHECK", mediumTarget.userId)
        waitForSubPhase(g.gameId, day, NightSubPhase.MEDIUM_RESULT)
        onMediumResult()
        ok(g.token(medium), g.gameId, "MEDIUM_CONFIRM")

        waitForSubPhase(g.gameId, day, NightSubPhase.GUARD_PICK)
        ok(g.token(g.first(PlayerRole.GUARD)), g.gameId, "GUARD_SKIP")

        waitForNightOver(g.gameId)
    }

    /** Ordered NightSubPhaseChanged + AudioSequence broadcasts for one game. */
    private fun broadcastSequence(gameId: Int): List<String> {
        val captor = argumentCaptor<Any>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        return captor.allValues.mapNotNull {
            when (it) {
                is DomainEvent.NightSubPhaseChanged -> "SUB:${it.subPhase}"
                is DomainEvent.AudioSequence -> "AUDIO:${it.audioSequence.audioFiles}"
                else -> null
            }
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `sub-phase broadcast sequence is identical whether the mechanical wolf learns, skips or is dead`() {
        fun runNight1(prefix: String, mechAction: (Game) -> Pair<String, String?>?, killMech: Boolean = false): List<String> {
            val g = setupGame(prefix)
            val killer = g.packWolves.first()
            if (killMech) gamePlayerRepository.save(g.mech.also { it.alive = false })
            ok(g.hostToken, g.gameId, "START_NIGHT")
            // Villager target and a medium check on a villager: identical for all three nights.
            driveNight(g, 1, killer, g.villagers[0], mechAction(g), g.villagers[1])
            return broadcastSequence(g.gameId)
        }

        val learns = runNight1("SQL", { g -> "MECH_LEARN" to g.first(PlayerRole.HUNTER).userId })
        val skips = runNight1("SQS", { "MECH_SKIP" to null })
        val dead = runNight1("SQD", { null }, killMech = true)

        assertThat(learns).contains("SUB:MECH_WOLF_PICK", "SUB:MECH_WOLF_RESULT", "AUDIO:[mech_wolf_open_eyes.mp3]", "AUDIO:[mech_wolf_close_eyes.mp3]")
        assertThat(skips).describedAs("skip vs learn").isEqualTo(learns)
        assertThat(dead).describedAs("dead vs learn").isEqualTo(learns)
    }

    @Test
    fun `lone mechanical wolf kills`() {
        val g = setupGame("LMK")
        // Kill the pack (both pack wolves); the mechanical wolf is the last wolf standing.
        g.packWolves.forEach { gamePlayerRepository.save(it.also { p -> p.alive = false }) }
        val victim = g.villagers[0]

        ok(g.hostToken, g.gameId, "START_NIGHT")
        waitForSubPhase(g.gameId, 1, NightSubPhase.WEREWOLF_PICK)
        // The WEREWOLF turn waits for the mechanical wolf: it is the killer and sees the pick.
        val mechState = state(g.gameId, g.token(g.mech))["nightPhase"] as Map<*, *>
        assertThat(mechState["wolfKiller"]).isEqualTo(true)
        ok(g.token(g.mech), g.gameId, "WOLF_KILL", victim.userId)

        // Night 1 is also the learning night: the mechanical wolf then acts in its own turn.
        waitForSubPhase(g.gameId, 1, NightSubPhase.MECH_WOLF_PICK)
        ok(g.token(g.mech), g.gameId, "MECH_SKIP")
        waitForSubPhase(g.gameId, 1, NightSubPhase.MECH_WOLF_RESULT)
        ok(g.token(g.mech), g.gameId, "MECH_CONFIRM")
        waitForSubPhase(g.gameId, 1, NightSubPhase.WITCH_ACT)
        ok(g.token(g.first(PlayerRole.WITCH)), g.gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false))
        waitForSubPhase(g.gameId, 1, NightSubPhase.MEDIUM_PICK)
        ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CHECK", g.villagers[1].userId)
        waitForSubPhase(g.gameId, 1, NightSubPhase.MEDIUM_RESULT)
        ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CONFIRM")
        waitForSubPhase(g.gameId, 1, NightSubPhase.GUARD_PICK)
        ok(g.token(g.first(PlayerRole.GUARD)), g.gameId, "GUARD_SKIP")
        waitForNightOver(g.gameId)

        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow().wolfTargetUserId)
            .isEqualTo(victim.userId)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(gamePlayerRepository.findByGameIdAndUserId(g.gameId, victim.userId).orElseThrow().alive)
            .describedAs("lone mechanical wolf's target dies at dawn").isFalse()
    }

    @Test
    fun `state API - pack wolves never see the mechanical wolf and the reverse`() {
        val g = setupGame("SAP", confirmRoles = false)
        val (pack1, pack2) = g.packWolves
        val mech = g.mech

        // Role reveal: the wolf card lists only the pack.
        @Suppress("UNCHECKED_CAST")
        fun reveal(p: GamePlayer) = (state(g.gameId, g.token(p))["roleReveal"] as Map<*, *>)["teammates"] as List<String>
        assertThat(reveal(pack1)).hasSize(1).doesNotContain(mech.userId)
        assertThat(reveal(mech)).isEmpty()

        g.tokenByUserId.values.forEach { ok(it, g.gameId, "CONFIRM_ROLE") }
        // Whole-game wolf card (top-level wolfTeammates).
        @Suppress("UNCHECKED_CAST")
        fun mates(p: GamePlayer) = state(g.gameId, g.token(p))["wolfTeammates"] as List<String>
        assertThat(mates(pack1)).containsExactly(pack2.userId)
        assertThat(mates(pack2)).containsExactly(pack1.userId)
        assertThat(mates(mech)).isEmpty()
        g.players.filter { !it.role.isWolf }.forEach { assertThat(mates(it)).isEmpty() }

        // Night: private per-turn fields.
        ok(g.hostToken, g.gameId, "START_NIGHT")
        waitForSubPhase(g.gameId, 1, NightSubPhase.WEREWOLF_PICK)
        @Suppress("UNCHECKED_CAST")
        val packNight = state(g.gameId, g.token(pack1))["nightPhase"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val mechNight = state(g.gameId, g.token(mech))["nightPhase"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val packMates = packNight["teammates"] as List<String>
        assertThat(packMates).hasSize(1)
        assertThat(packMates.single().toString()).startsWith("${pack2.seatIndex}·")
        assertThat(packNight["wolfKiller"]).isEqualTo(true)
        assertThat(packNight).doesNotContainKey("mechWolf")

        assertThat(mechNight).doesNotContainKey("teammates")
        assertThat(mechNight["wolfKiller"]).describedAs("pack alive: mech is not the killer").isEqualTo(false)
        assertThat(mechNight).containsKey("mechWolf")

        // Let the night finish so the coroutine does not outlive the test.
        ok(g.token(pack1), g.gameId, "WOLF_KILL", g.villagers[0].userId)
        waitForSubPhase(g.gameId, 1, NightSubPhase.MECH_WOLF_PICK)
        ok(g.token(mech), g.gameId, "MECH_SKIP")
        waitForSubPhase(g.gameId, 1, NightSubPhase.MECH_WOLF_RESULT)
        ok(g.token(mech), g.gameId, "MECH_CONFIRM")
        waitForSubPhase(g.gameId, 1, NightSubPhase.WITCH_ACT)
        ok(g.token(g.first(PlayerRole.WITCH)), g.gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false))
        waitForSubPhase(g.gameId, 1, NightSubPhase.MEDIUM_PICK)
        ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CHECK", g.villagers[1].userId)
        waitForSubPhase(g.gameId, 1, NightSubPhase.MEDIUM_RESULT)
        ok(g.token(g.first(PlayerRole.MEDIUM)), g.gameId, "MEDIUM_CONFIRM")
        waitForSubPhase(g.gameId, 1, NightSubPhase.GUARD_PICK)
        ok(g.token(g.first(PlayerRole.GUARD)), g.gameId, "GUARD_SKIP")
        waitForNightOver(g.gameId)
    }

    @Test
    fun `medium checks the mechanical wolf before and after learning`() {
        val g = setupGame("MCH")
        val pack = g.packWolves.first()
        val medium = g.first(PlayerRole.MEDIUM)
        val hunter = g.first(PlayerRole.HUNTER)

        fun mediumSaw(day: Int): Pair<Any?, List<Any?>> {
            val night = state(g.gameId, g.token(medium))["nightPhase"] as Map<*, *>
            assertThat((night["dayNumber"] as Number).toInt()).isEqualTo(day)
            val result = night["mediumResult"] as Map<*, *>
            return result["role"] to (result["history"] as List<*>).map { (it as Map<*, *>)["role"] as Any? }
        }

        // Night 1: the mechanical wolf has not learned (skips), so the medium sees the wolf itself.
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, pack, g.villagers[0], "MECH_SKIP" to null, g.mech) {
            assertThat(mediumSaw(1).first).isEqualTo("MECHANICAL_WOLF")
        }

        val n1 = nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow()
        nightOrchestrator.applyNightKills(g.gameId, nightOrchestrator.computePendingKills(n1))
        nightOrchestrator.initNight(g.gameId, 2)

        // Night 2: it learns the hunter before the medium's turn, so the medium sees the learned role.
        driveNight(g, 2, pack, g.villagers[1], "MECH_LEARN" to hunter.userId, g.mech) {
            val (role, history) = mediumSaw(2)
            assertThat(role).isEqualTo("HUNTER")
            assertThat(history).containsExactly("MECHANICAL_WOLF", "HUNTER")
        }
    }
}
