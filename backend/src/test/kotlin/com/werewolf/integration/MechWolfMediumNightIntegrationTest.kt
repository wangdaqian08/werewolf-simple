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

    /**
     * Waits until the night loop has written [target]. No extra sleep: an action that lands between
     * the sub-phase write and the loop's `CompletableDeferred` is queued by
     * `NightOrchestrator.releaseActionSignal` and consumed by the next await.
     */
    private fun waitForSubPhase(gameId: Int, day: Int, target: NightSubPhase, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: NightSubPhase? = null
        while (System.currentTimeMillis() < deadline) {
            last = nightPhaseRepository.findByGameIdAndDayNumber(gameId, day).orElse(null)?.subPhase
            if (last == target) return
            Thread.sleep(100)
        }
        error("Night loop did not reach $target on day $day within ${timeoutMs}ms (last sub-phase: $last)")
    }

    /** Every public `/topic/game/{id}` payload broadcast so far, in order. */
    private fun broadcasts(gameId: Int): List<Any> =
        mockingDetails(stompPublisher).invocations
            .filter { it.method.name == "broadcastGame" && it.arguments[0] == gameId }
            .map { it.arguments[1] }

    private fun isDawnAudio(e: Any) = e is DomainEvent.AudioSequence &&
        e.audioSequence.phase == GamePhase.DAY_DISCUSSION && e.audioSequence.subPhase == DaySubPhase.RESULT_HIDDEN.name

    /** Waits for night [day]'s dawn: the RESULT_HIDDEN audio is the last broadcast the night resolution emits. */
    private fun waitForNightOver(gameId: Int, day: Int, timeoutMs: Long = 30_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (broadcasts(gameId).count(::isDawnAudio) >= day) return
            Thread.sleep(100)
        }
        error(
            "Dawn of night $day was not broadcast within ${timeoutMs}ms " +
                "(game.phase=${gameRepository.findById(gameId).orElseThrow().phase})"
        )
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

        waitForNightOver(g.gameId, day)
    }

    /**
     * Every public broadcast from the first night's entry on, as comparable strings. Sequence ids,
     * timestamps and user ids are left out (they differ per game); [DomainEvent.TimerUpdated] is
     * dropped because its count depends on timing. Event types without a listed payload compare
     * by type only.
     */
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
                    is DomainEvent.OpenEyes -> "OpenEyes:${it.role}/${it.phase}/night${it.nightNumber}"
                    is DomainEvent.CloseEyes -> "CloseEyes:${it.role}/${it.phase}/night${it.nightNumber}"
                    else -> it.javaClass.simpleName
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
            return broadcastFingerprint(g.gameId)
        }

        val learns = runNight1("SQL", { g -> "MECH_LEARN" to g.first(PlayerRole.HUNTER).userId })
        val skips = runNight1("SQS", { "MECH_SKIP" to null })
        val dead = runNight1("SQD", { null }, killMech = true)

        val learnsText = learns.joinToString("\n")
        assertThat(learnsText).contains(
            "NightSubPhaseChanged:MECH_WOLF_PICK", "NightSubPhaseChanged:MECH_WOLF_RESULT",
            "[mech_wolf_open_eyes.mp3]", "[mech_wolf_close_eyes.mp3]",
        )
        assertThat(learns).describedAs("dawn was captured").anyMatch { it.startsWith("AudioSequence:DAY_DISCUSSION/RESULT_HIDDEN") }
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

        // driveNight sends the mechanical wolf's WOLF_KILL; night 1 is also its learning night, so it then acts in its own turn.
        driveNight(g, 1, g.mech, victim, "MECH_SKIP" to null, g.villagers[1])

        assertThat(nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow().wolfTargetUserId)
            .isEqualTo(victim.userId)
        ok(g.hostToken, g.gameId, "REVEAL_NIGHT_RESULT")
        assertThat(gamePlayerRepository.findByGameIdAndUserId(g.gameId, victim.userId).orElseThrow().alive)
            .describedAs("lone mechanical wolf's target dies at dawn").isFalse()
    }

    private fun loneMechKillsOnNight2(prefix: String, night1Action: (Game) -> Pair<String, String?>) {
        val g = setupGame(prefix)
        val pack = g.packWolves.first()
        val n1Victim = g.villagers[0]
        // Not the last villager: with night 1's victim that wipes the villager side and ends the game before dawn.
        val n2Victim = g.first(PlayerRole.IDIOT)

        // Night 1: the pack is alive and kills; the mechanical wolf only learns or skips.
        ok(g.hostToken, g.gameId, "START_NIGHT")
        driveNight(g, 1, pack, n1Victim, night1Action(g), g.villagers[1])

        // Move to night 2 the way NightPhaseDeadRoleIntegrationTest does, with the pack gone first.
        val n1 = nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 1).orElseThrow()
        nightOrchestrator.applyNightKills(g.gameId, nightOrchestrator.computePendingKills(n1))
        g.packWolves.forEach { gamePlayerRepository.save(it.also { p -> p.alive = false }) }
        nightOrchestrator.initNight(g.gameId, 2)

        // Night 2: WEREWOLF_PICK waits for the mechanical wolf, the only wolf left, and accepts its kill.
        driveNight(g, 2, g.mech, n2Victim, "MECH_SKIP" to null, g.first(PlayerRole.HUNTER))

        val n2 = nightPhaseRepository.findByGameIdAndDayNumber(g.gameId, 2).orElseThrow()
        assertThat(n2.wolfTargetUserId).isEqualTo(n2Victim.userId)
        nightOrchestrator.applyNightKills(g.gameId, nightOrchestrator.computePendingKills(n2))
        assertThat(gamePlayerRepository.findByGameIdAndUserId(g.gameId, n2Victim.userId).orElseThrow().alive)
            .describedAs("target of the lone mechanical wolf on night 2 dies").isFalse()
    }

    @Test
    fun `lone mechanical wolf kills on night 2 after learning on night 1 with the pack alive`() =
        loneMechKillsOnNight2("LM2L") { g -> "MECH_LEARN" to g.first(PlayerRole.HUNTER).userId }

    @Test
    fun `lone mechanical wolf kills on night 2 after skipping on night 1 with the pack alive`() =
        loneMechKillsOnNight2("LM2S") { "MECH_SKIP" to null }

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
        driveNight(g, 1, pack1, g.villagers[0], "MECH_SKIP" to null, g.villagers[1])
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
        // Not villagers[1]: with night 1's victim that wipes the villager side and ends the game before dawn.
        driveNight(g, 2, pack, g.first(PlayerRole.IDIOT), "MECH_LEARN" to hunter.userId, g.mech) {
            val (role, history) = mediumSaw(2)
            assertThat(role).isEqualTo("HUNTER")
            assertThat(history).containsExactly("MECHANICAL_WOLF", "HUNTER")
        }
    }
}
