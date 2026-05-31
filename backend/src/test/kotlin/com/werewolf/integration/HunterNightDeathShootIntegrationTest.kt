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
import com.werewolf.model.WinnerSide
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
 * Integration coverage for the "hunter killed by wolves at night shoots during
 * the day-reveal flow" contract (DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH).
 *
 * Setup mirrors SheriffNightDeathHandoverIntegrationTest: the night flow isn't
 * under test, so we park the game at "Day 2 morning, ready to reveal", force the
 * roles we need, populate the NightPhase row directly, then drive the real HTTP
 * endpoints.
 *
 * Roles are forced to a fixed layout (1 werewolf, rest village-side) so no test
 * accidentally trips a win condition except the dedicated game-ending case. The
 * HARD_MODE counterplay accounting is correct by construction — a wolf-killed
 * hunter is dead, so WinConditionChecker.buildCounterplay's hasHunterWithBullet
 * (which only counts *alive* hunters) already excludes them; no EliminationHistory
 * row is written for the night shot (it would collide with uq_game_day).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HunterNightDeathShootIntegrationTest {

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
            ACTION_URL,
            HttpEntity(
                mapOf("gameId" to gameId, "actionType" to actionType, "targetUserId" to targetUserId),
                headers(token)
            ),
            Map::class.java
        )

    private data class Room6(
        val host: TestPlayer,
        val g1: TestPlayer,
        val g2: TestPlayer,
        val g3: TestPlayer,
        val g4: TestPlayer,
        val g5: TestPlayer,
        val roomId: Int,
    ) {
        val all get() = listOf(host, g1, g2, g3, g4, g5)
    }

    private fun setupRoom(prefix: String): Room6 {
        val host = login("${prefix}H")
        val g1 = login("${prefix}G1")
        val g2 = login("${prefix}G2")
        val g3 = login("${prefix}G3")
        val g4 = login("${prefix}G4")
        val g5 = login("${prefix}G5")

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        FIELD_TOTAL_PLAYERS to 6,
                        "roles" to listOf("WEREWOLF", "SEER", "HUNTER", "VILLAGER", "VILLAGER"),
                        "hasSheriff" to true,
                    ),
                ),
                headers(host.token),
            ),
            Map::class.java,
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(
            SEAT_URL,
            HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(host.token)),
            Map::class.java,
        )

        listOf(g1, g2, g3, g4, g5).forEachIndexed { idx, player ->
            restTemplate.postForEntity(
                JOIN_ROOM_URL,
                HttpEntity(mapOf("roomCode" to roomCode), headers(player.token)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                SEAT_URL,
                HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(player.token)),
                Map::class.java,
            )
            restTemplate.postForEntity(
                READY_URL,
                HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(player.token)),
                Map::class.java,
            )
        }

        return Room6(host, g1, g2, g3, g4, g5, roomId)
    }

    /** Overwrite a player's (val) role in the DB — the night flow isn't under test. */
    private fun forceRole(gameId: Int, userId: String, role: PlayerRole) {
        val gp = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow()
        val f = com.werewolf.model.GamePlayer::class.java.getDeclaredField("role")
        f.isAccessible = true
        f.set(gp, role)
        gamePlayerRepository.save(gp)
    }

    /**
     * Start the game, confirm roles, and park it at Day-2 RESULT_HIDDEN with a
     * fixed role layout: host=VILLAGER, g1=HUNTER, g2=WEREWOLF, g3=SEER,
     * g4/g5=VILLAGER. Returns the gameId.
     */
    private fun openDay2(room: Room6): Int {
        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to room.roomId), headers(room.host.token)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == room.roomId }
        val gameId = game.gameId!!

        room.all.forEach { player ->
            assertThat(action(player.token, gameId, "CONFIRM_ROLE").statusCode).isEqualTo(HttpStatus.OK)
        }

        forceRole(gameId, room.host.userId, PlayerRole.VILLAGER)
        forceRole(gameId, room.g1.userId, PlayerRole.HUNTER)
        forceRole(gameId, room.g2.userId, PlayerRole.WEREWOLF)
        forceRole(gameId, room.g3.userId, PlayerRole.SEER)
        forceRole(gameId, room.g4.userId, PlayerRole.VILLAGER)
        forceRole(gameId, room.g5.userId, PlayerRole.VILLAGER)

        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_HIDDEN.name
        game.dayNumber = 2
        gameRepository.save(game)

        return gameId
    }

    private fun setSheriff(gameId: Int, userId: String) {
        val game = gameRepository.findById(gameId).orElseThrow()
        game.sheriffUserId = userId
        gameRepository.save(game)
        gamePlayerRepository.findByGameIdAndUserId(gameId, userId).ifPresent {
            it.sheriff = true; gamePlayerRepository.save(it)
        }
    }

    private fun saveNight(gameId: Int, wolfTarget: String?, poisonTarget: String? = null) {
        nightPhaseRepository.save(NightPhase(gameId = gameId, dayNumber = 2).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = wolfTarget
            it.witchPoisonTargetUserId = poisonTarget
        })
    }

    private fun reveal(room: Room6, gameId: Int) =
        action(room.host.token, gameId, "REVEAL_NIGHT_RESULT").also {
            assertThat(it.statusCode).isEqualTo(HttpStatus.OK)
        }

    private fun subPhase(gameId: Int) = gameRepository.findById(gameId).orElseThrow().subPhase
    private fun phase(gameId: Int) = gameRepository.findById(gameId).orElseThrow().phase
    private fun alive(gameId: Int, userId: String) =
        gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow().alive

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `wolf-killed hunter lands on HUNTER_SHOOT_NIGHT_DEATH at reveal`() {
        val room = setupRoom("HNS1")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId) // g1 = hunter

        reveal(room, gameId)

        assertThat(phase(gameId)).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)
        assertThat(alive(gameId, room.g1.userId)).isFalse() // dead from the wolf attack
    }

    @Test
    fun `hunter shoot kills the target and lands on RESULT_REVEALED`() {
        val room = setupRoom("HNS2")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId)
        reveal(room, gameId)

        // g1 (the dead hunter) shoots g4 (a villager — keeps a wolf alive, no win).
        val resp = action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(phase(gameId)).isEqualTo(GamePhase.DAY_DISCUSSION)
    }

    @Test
    fun `hunter pass lands on RESULT_REVEALED with no extra death`() {
        val room = setupRoom("HNS3")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId)
        reveal(room, gameId)

        val resp = action(room.g1.token, gameId, "HUNTER_PASS")
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        // Everyone except the wolf-killed hunter is still alive.
        assertThat(alive(gameId, room.g4.userId)).isTrue()
    }

    @Test
    fun `poisoned-only hunter cannot shoot - reveal goes straight to RESULT_REVEALED`() {
        val room = setupRoom("HNS4")
        val gameId = openDay2(room)
        // Hunter killed by poison, not wolves → no gun.
        saveNight(gameId, wolfTarget = null, poisonTarget = room.g1.userId)

        reveal(room, gameId)

        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(alive(gameId, room.g1.userId)).isFalse()
    }

    @Test
    fun `hunter both wolf-targeted and poisoned cannot shoot`() {
        val room = setupRoom("HNS5")
        val gameId = openDay2(room)
        // Poison disables the gun even though the wolves also attacked.
        saveNight(gameId, wolfTarget = room.g1.userId, poisonTarget = room.g1.userId)

        reveal(room, gameId)

        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        // And a HUNTER_SHOOT attempt is rejected (not in the shoot sub-phase).
        val resp = action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `sheriff and a different hunter both die - badge handover first then hunter shoot`() {
        val room = setupRoom("HNS6")
        val gameId = openDay2(room)
        setSheriff(gameId, room.g3.userId) // g3 (SEER) holds the badge
        // Wolves kill the hunter (g1); witch poisons the sheriff (g3).
        saveNight(gameId, wolfTarget = room.g1.userId, poisonTarget = room.g3.userId)

        reveal(room, gameId)
        // Badge handover comes first (国标-style: badge before the hunter shoots).
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)

        // Sheriff passes the badge to g4 → chains into the hunter shoot.
        assertThat(action(room.g3.token, gameId, "BADGE_PASS", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // Hunter fires at g5 (villager) → finally lands on RESULT_REVEALED.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(alive(gameId, room.g5.userId)).isFalse()
    }

    @Test
    fun `hunter is the sheriff - badge handover then shoot then RESULT_REVEALED with no loop`() {
        val room = setupRoom("HNS7")
        val gameId = openDay2(room)
        setSheriff(gameId, room.g1.userId) // the hunter also holds the badge
        saveNight(gameId, wolfTarget = room.g1.userId)

        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)

        // Dead sheriff-hunter passes the badge to g4 → now gets the shoot turn.
        assertThat(action(room.g1.token, gameId, "BADGE_PASS", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // Shoots g5 (villager) → terminal RESULT_REVEALED, no re-entry.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `night-death hunter shooting the alive sheriff triggers badge handover`() {
        val room = setupRoom("HNS8")
        val gameId = openDay2(room)
        setSheriff(gameId, room.g3.userId) // g3 (SEER, non-wolf) is the living sheriff
        saveNight(gameId, wolfTarget = room.g1.userId) // only the hunter dies at night

        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // Hunter shoots the living sheriff (g3) → kill, then badge handover.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g3.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(alive(gameId, room.g3.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)

        // New sheriff handover finishes the day.
        assertThat(action(room.g3.token, gameId, "BADGE_PASS", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `night-death hunter shot that clears the last wolf ends the game`() {
        val room = setupRoom("HNS9")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId) // hunter dies

        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // Hunter shoots the only werewolf (g2) → villagers win.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g2.userId).statusCode)
            .isEqualTo(HttpStatus.OK)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.winner).isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `a double fire is rejected once the hunter has already shot`() {
        val room = setupRoom("HNS10")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId)
        reveal(room, gameId)

        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        // Sub-phase has advanced; the resolved flag is set → second shot rejected.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(alive(gameId, room.g5.userId)).isTrue()
    }

    @Test
    fun `only the wolf-killed hunter may fire - other players are rejected`() {
        val room = setupRoom("HNS11")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId)
        reveal(room, gameId)

        // Host (a villager, not the hunter) cannot shoot.
        assertThat(action(room.host.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        // g3 (the seer) cannot shoot either.
        assertThat(action(room.g3.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        // The real hunter still can.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `night-death hunter cannot shoot an already-dead target`() {
        val room = setupRoom("HNS12")
        val gameId = openDay2(room)
        // Wolves kill the hunter (g1); witch poisons g4 — both are dead at reveal.
        saveNight(gameId, wolfTarget = room.g1.userId, poisonTarget = room.g4.userId)
        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // g4 is already dead (poison) → the shot is rejected; sub-phase unchanged.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)
    }

    @Test
    fun `night-death hunter shoot requires a target`() {
        val room = setupRoom("HNS13")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.g1.userId)
        reveal(room, gameId)

        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", null).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)
    }

    @Test
    fun `sheriff BADGE_DESTROY at reveal still chains into the hunter shoot`() {
        val room = setupRoom("HNS14")
        val gameId = openDay2(room)
        setSheriff(gameId, room.g3.userId)
        // Wolves kill the hunter (g1); witch poisons the sheriff (g3).
        saveNight(gameId, wolfTarget = room.g1.userId, poisonTarget = room.g3.userId)

        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)

        // Sheriff destroys the badge → chains into the hunter shoot (badge-first).
        assertThat(action(room.g3.token, gameId, "BADGE_DESTROY").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        // Hunter fires → RESULT_REVEALED.
        assertThat(action(room.g1.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.OK)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }
}
