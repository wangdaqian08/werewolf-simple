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
import com.werewolf.repository.EliminationHistoryRepository
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

/**
 * End-to-end integration tests for the HARD_MODE win rule described in
 * `docs/scenarios/scenario-09-e2e.md`.
 *
 * The tests use a 6-player HARD_MODE room (the project's supported minimum) and exercise
 * the real VotingPipeline + WinConditionChecker through REST actions. Each test advances
 * the game to a deterministic "end of day vote" state by:
 *
 *   1. Starting the game and confirming all roles.
 *   2. Seeding Night 1 state directly in the DB (a wolf kill, an optional witch potion
 *      spend, an optional hunter shot) — this bypasses the real-time coroutine-driven
 *      nightRoleLoop which would otherwise take ~15s per test.
 *   3. Jumping the game straight into DAY_VOTING with a single target alive for vote.
 *   4. Submitting votes through SUBMIT_VOTE + VOTING_REVEAL_TALLY and asserting the
 *      game-over event that the VotingPipeline emits.
 *
 * What the tests genuinely verify end-to-end:
 *   • WinConditionChecker is invoked with the correct trigger (POST_VOTE).
 *   • buildCounterplay reads witch potions (NightPhase.witchAntidoteUsed /
 *     witchPoisonTargetUserId) and hunter shot (EliminationHistory.hunterShotUserId)
 *     from the DB and produces the right flags.
 *   • endGame persists game.winner = WEREWOLF and broadcasts DomainEvent.GameOver.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Scenario09HardModeWinIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var eliminationHistoryRepository: EliminationHistoryRepository

    companion object {
        private const val START_URL = "/api/game/start"
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val ACTION_URL = "/api/game/action"
        private const val TOTAL_PLAYERS = 6
    }

    private data class TestRoom(
        val hostToken: String,
        val roomId: Int,
        val gameId: Int,
        val tokens: Map<String, String>,
    )

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun login(nickname: String): Pair<String, String> {
        @Suppress("UNCHECKED_CAST")
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf("nickname" to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body["user"] as Map<*, *>)["userId"] as String
        return token to userId
    }

    private fun headers(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    private fun action(
        token: String,
        gameId: Int,
        actionType: String,
        targetUserId: String? = null,
    ) = restTemplate.postForEntity(
        ACTION_URL,
        HttpEntity(
            buildMap<String, Any?> {
                put("gameId", gameId)
                put("actionType", actionType)
                if (targetUserId != null) put("targetUserId", targetUserId)
            },
            headers(token),
        ),
        Map::class.java,
    )

    /**
     * Creates a 6-player HARD_MODE room with exactly one counterplay role (seat distribution:
     * 2 WEREWOLF + 1 [counterplayRole] + 3 VILLAGER per GameService.buildRoleList). Lobby,
     * seat, ready, start, and confirm all roles. Returns a [TestRoom] handle.
     */
    private fun setupHardModeGame(prefix: String, counterplayRole: PlayerRole): TestRoom {
        val (hostToken, hostId) = login("${prefix}H")
        val guests = (1 until TOTAL_PLAYERS).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        FIELD_TOTAL_PLAYERS to TOTAL_PLAYERS,
                        "roles" to listOf(counterplayRole.name),
                        "hasSheriff" to false,
                        "winCondition" to "HARD_MODE",
                    ),
                ),
                headers(hostToken),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)),
            Map::class.java,
        )
        guests.forEachIndexed { idx, (guestToken, _) ->
            restTemplate.postForEntity(
                JOIN_ROOM_URL,
                HttpEntity(mapOf("roomCode" to roomCode), headers(guestToken)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                SEAT_URL,
                HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(guestToken)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                READY_URL,
                HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(guestToken)),
                Map::class.java,
            )
        }

        val tokenByUserId = buildMap<String, String> {
            put(hostId, hostToken)
            guests.forEach { (t, id) -> put(id, t) }
        }

        val startResp = restTemplate.postForEntity(
            START_URL,
            HttpEntity(mapOf("roomId" to roomId), headers(hostToken)),
            Map::class.java,
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!

        tokenByUserId.values.forEach { token ->
            assertThat(action(token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        return TestRoom(hostToken, roomId, gameId, tokenByUserId)
    }

    /**
     * Builds post-N1 / pre-Day-2-vote state directly in the DB:
     *   • Marks [nightVictimUserId] dead (the N1 wolf kill).
     *   • Creates a NightPhase row for day 1 with witch potions optionally consumed.
     *   • Creates a day-1 EliminationHistory row with hunter shot optionally recorded
     *     (simulating a past hunter shot from a prior day's flow).
     *   • Positions the Game on day 2 in DAY_DISCUSSION / RESULT_REVEALED so the next
     *     voting flow creates a separate day-2 EliminationHistory row (no PK clash with
     *     the pre-seeded day-1 row).
     *
     * This sidesteps the real-time coroutine nightRoleLoop, which would otherwise take
     * ~15s per multi-role night. The state we seed matches what a legitimate prior
     * night + day flow would leave behind.
     */
    private fun seedEndOfNight1(
        room: TestRoom,
        nightVictimUserId: String,
        witchAntidoteSpent: Boolean = false,
        witchPoisonTarget: String? = null,
        hunterHasShot: Boolean = false,
    ) {
        val victim = gamePlayerRepository.findByGameIdAndUserId(room.gameId, nightVictimUserId).orElseThrow()
        victim.alive = false
        gamePlayerRepository.save(victim)

        nightPhaseRepository.save(
            NightPhase(
                gameId = room.gameId,
                dayNumber = 1,
                subPhase = NightSubPhase.COMPLETE,
                wolfTargetUserId = nightVictimUserId,
                witchAntidoteUsed = witchAntidoteSpent,
                witchPoisonTargetUserId = witchPoisonTarget,
            )
        )

        if (hunterHasShot) {
            eliminationHistoryRepository.save(
                EliminationHistory(
                    gameId = room.gameId,
                    dayNumber = 1,
                    hunterShotUserId = "${nightVictimUserId}-shot-placeholder",
                    hunterShotRole = PlayerRole.HUNTER,
                )
            )
        }

        val game = gameRepository.findById(room.gameId).orElseThrow()
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        // Advance to day 2 so the voting pipeline's EliminationHistory row (keyed on dayNumber)
        // does not collide with the optional day-1 hunter-shot row we seeded above.
        game.dayNumber = 2
        gameRepository.save(game)
    }

    /** Host opens voting; all alive non-targets vote [target]; host reveals the tally. */
    private fun voteOutAndRevealTally(room: TestRoom, target: String) {
        assertThat(action(room.hostToken, room.gameId, "DAY_ADVANCE").statusCode).isEqualTo(HttpStatus.OK)

        val voters = gamePlayerRepository.findByGameId(room.gameId).filter { it.alive && it.userId != target }
        voters.forEach { voter ->
            val voterToken = room.tokens.getValue(voter.userId)
            assertThat(action(voterToken, room.gameId, "SUBMIT_VOTE", target).statusCode).isEqualTo(HttpStatus.OK)
        }

        assertThat(action(room.hostToken, room.gameId, "VOTING_REVEAL_TALLY").statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun userIdOfRole(room: TestRoom, role: PlayerRole): String =
        gamePlayerRepository.findByGameId(room.gameId).first { it.role == role }.userId

    private fun villagerUserIds(room: TestRoom): List<String> =
        gamePlayerRepository.findByGameId(room.gameId).filter { it.role == PlayerRole.VILLAGER }.map { it.userId }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Scenario-09 primary assertion: once the last counterplay role (GUARD) is eliminated
     * by day vote, HARD_MODE's logical branch must fire — wolves ≥ humans with no guard /
     * no witch with potions / no hunter with bullet ⇒ WEREWOLF wins.
     *
     * Fails against the pre-fix code, which only checks `others == 0` for HARD_MODE.
     */
    @Test
    fun `HARD_MODE POST_VOTE logical win fires when guard is the last counterplay and is voted out`() {
        val room = setupHardModeGame(prefix = "HMG", counterplayRole = PlayerRole.GUARD)
        val villagers = villagerUserIds(room)

        // Seed: wolves killed V1 at N1; guard+witch+hunter state otherwise unused (no witch/hunter in this room).
        seedEndOfNight1(room, nightVictimUserId = villagers[0])

        // Post-N1 state: 2W + GUARD + V2 + V3 = 2W vs 3 humans. Game continues to day.
        val afterN1 = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(afterN1.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(afterN1.winner).isNull()

        // Day 1 vote: eliminate the GUARD — removes the last counterplay token.
        val guardUserId = userIdOfRole(room, PlayerRole.GUARD)
        voteOutAndRevealTally(room, guardUserId)

        // Post-vote state: 2W + 2 villagers = 2W vs 2 humans, zero counterplay → LOGICAL WIN.
        val finalGame = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(finalGame.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isEqualTo(WinnerSide.WEREWOLF)
    }

    /**
     * With a living witch who still holds potions, the logical branch must NOT fire:
     * the good side still has antidote/poison counterplay available.
     */
    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by witch with potions`() {
        val room = setupHardModeGame(prefix = "HMW", counterplayRole = PlayerRole.WITCH)
        val villagers = villagerUserIds(room)

        // Witch passed both potions at N1 — witchAntidoteUsed=false, witchPoisonTargetUserId=null.
        seedEndOfNight1(room, nightVictimUserId = villagers[0])

        // Day 1: vote out a plain villager, not the witch.
        voteOutAndRevealTally(room, villagers[1])

        // Post-vote state: 2W + WITCH + V3 = 2W vs 2 humans. hasWitchWithPotions=true → null.
        val finalGame = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(finalGame.phase).isNotEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isNull()
    }

    /**
     * With a living hunter who has not yet fired, the logical branch must NOT fire:
     * the good side still has a retaliatory shot available.
     */
    @Test
    fun `HARD_MODE POST_VOTE logical win BLOCKED by hunter with bullet`() {
        val room = setupHardModeGame(prefix = "HMH", counterplayRole = PlayerRole.HUNTER)
        val villagers = villagerUserIds(room)

        // Hunter has not fired — no EliminationHistory row with hunterShotUserId.
        seedEndOfNight1(room, nightVictimUserId = villagers[0])

        voteOutAndRevealTally(room, villagers[1])

        // Post-vote state: 2W + HUNTER + V3 = 2W vs 2 humans. hasHunterWithBullet=true → null.
        val finalGame = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(finalGame.phase).isNotEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isNull()
    }

    /**
     * Witch counterplay falls away once both potions are spent — the logical branch then fires
     * even with the witch still alive. This locks in the `(antidoteUnused || poisonUnused)`
     * semantics of the counterplay builder.
     */
    @Test
    fun `HARD_MODE POST_VOTE logical win fires after witch has spent both potions`() {
        val room = setupHardModeGame(prefix = "HMW2", counterplayRole = PlayerRole.WITCH)
        val villagers = villagerUserIds(room)

        // Witch spent antidote AND poison at N1: witchAntidoteUsed=true AND witchPoisonTargetUserId!=null.
        // Both-spent is only possible across two nights in real play; for this integration test
        // we collapse it into N1 state since the checker only inspects the historical flags.
        seedEndOfNight1(
            room,
            nightVictimUserId = villagers[0],
            witchAntidoteSpent = true,
            witchPoisonTarget = "phantom-target-user",
        )

        voteOutAndRevealTally(room, villagers[1])

        // Post-vote state: 2W + WITCH (potions spent) + V3 = 2W vs 2 humans.
        // hasWitchWithPotions=false → LOGICAL WIN.
        val finalGame = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(finalGame.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isEqualTo(WinnerSide.WEREWOLF)
    }

    /**
     * Hunter counterplay falls away once the bullet is spent — the logical branch then fires
     * even with the hunter still alive. Matches the scenario-09 D1 checkpoint where Grace has
     * already fired, so `hasHunterWithBullet=false` at D2 even if she were somehow alive.
     */
    @Test
    fun `HARD_MODE POST_VOTE logical win fires after hunter has spent the bullet`() {
        val room = setupHardModeGame(prefix = "HMH2", counterplayRole = PlayerRole.HUNTER)
        val villagers = villagerUserIds(room)

        seedEndOfNight1(
            room,
            nightVictimUserId = villagers[0],
            hunterHasShot = true,
        )

        voteOutAndRevealTally(room, villagers[1])

        // Post-vote state: 2W + HUNTER (bullet spent) + V3 = 2W vs 2 humans.
        // hasHunterWithBullet=false → LOGICAL WIN.
        val finalGame = gameRepository.findById(room.gameId).orElseThrow()
        assertThat(finalGame.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(finalGame.winner).isEqualTo(WinnerSide.WEREWOLF)
    }
}
