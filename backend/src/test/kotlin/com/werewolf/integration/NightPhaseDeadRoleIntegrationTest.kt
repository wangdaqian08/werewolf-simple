package com.werewolf.integration

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
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.WinnerSide
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Timeout(120)
class NightPhaseDeadRoleIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var gamePlayerRepository: GamePlayerRepository
    @Autowired lateinit var nightPhaseRepository: NightPhaseRepository
    @Autowired lateinit var nightOrchestrator: NightOrchestrator

    companion object {
        const val START_URL = "/api/game/start"
        const val SEAT_URL = "/api/room/seat"
        const val READY_URL = "/api/room/ready"
        const val ACTION_URL = "/api/game/action"
    }

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
        token: String, gameId: Int, actionType: String,
        targetUserId: String? = null, payload: Map<String, Any?>? = null,
    ) = restTemplate.postForEntity(
        ACTION_URL,
        HttpEntity(
            buildMap<String, Any?> {
                put("gameId", gameId)
                put("actionType", actionType)
                if (targetUserId != null) put("targetUserId", targetUserId)
                if (payload != null) put("payload", payload)
            },
            headers(token)
        ),
        Map::class.java
    )

    /**
     * Create a 6-player room (no sheriff) with specific roles.
     * Returns (hostToken, roomId, tokenByUserId map).
     */
    private fun setupRoom(prefix: String): Triple<String, Int, Map<String, String>> {
        val (hostToken, hostId) = login("${prefix}H")
        val guestPairs = (1..5).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(FIELD_CONFIG to mapOf(
                    FIELD_TOTAL_PLAYERS to 6,
                    "roles" to listOf("WEREWOLF", "SEER", "WITCH", "GUARD", "VILLAGER"),
                    "hasSheriff" to false,
                )),
                headers(hostToken)
            ),
            Map::class.java
        ).body!! as Map<String, Any?>

        val roomId = (roomBody[FIELD_ROOM_ID] as String).toInt()
        val roomCode = roomBody[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), headers(hostToken)), Map::class.java)

        guestPairs.forEachIndexed { idx, (guestToken, _) ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to idx + 1, "roomId" to roomId), headers(guestToken)), Map::class.java)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), headers(guestToken)), Map::class.java)
        }

        val tokenByUserId = buildMap<String, String> {
            put(hostId, hostToken)
            guestPairs.forEach { (t, id) -> put(id, t) }
        }

        return Triple(hostToken, roomId, tokenByUserId)
    }

    /** Find the token for a specific role using game state API. */
    private fun findRoleToken(gameId: Int, tokenByUserId: Map<String, String>, role: PlayerRole): String {
        return tokenByUserId.entries.first { (_, token) ->
            @Suppress("UNCHECKED_CAST")
            val state = restTemplate.exchange(
                "/api/game/$gameId/state",
                HttpMethod.GET,
                HttpEntity<Nothing>(headers(token)),
                Map::class.java
            ).body
            state?.get("myRole") as? String == role.name
        }.value
    }

    /** Find the userId for a specific role. */
    private fun findRoleUserId(gameId: Int, tokenByUserId: Map<String, String>, role: PlayerRole): String {
        return tokenByUserId.entries.first { (_, token) ->
            @Suppress("UNCHECKED_CAST")
            val state = restTemplate.exchange(
                "/api/game/$gameId/state",
                HttpMethod.GET,
                HttpEntity<Nothing>(headers(token)),
                Map::class.java
            ).body
            state?.get("myRole") as? String == role.name
        }.key
    }

    // ── Case 2: Dead seer triggers correct phase transition on night 2 ────────

    @Test
    fun `dead seer on night 2 - audio plays and advance chain completes`() {
        val (hostToken, roomId, tokenByUserId) = setupRoom("NR1")

        // Start game and confirm roles
        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!
        tokenByUserId.values.forEach { token -> action(token, gameId, "CONFIRM_ROLE") }

        // Discover role assignments (6 players → 2 wolves + seer + witch + guard + 1 villager)
        val wolves = gamePlayerRepository.findByGameId(gameId).filter { it.role == PlayerRole.WEREWOLF }
        val wolf1UserId = wolves[0].userId
        val wolf2UserId = wolves[1].userId
        val wolfToken = tokenByUserId[wolf1UserId] ?: error("Wolf1 token not found")
        val seerToken = findRoleToken(gameId, tokenByUserId, PlayerRole.SEER)
        val seerUserId = findRoleUserId(gameId, tokenByUserId, PlayerRole.SEER)
        val witchToken = findRoleToken(gameId, tokenByUserId, PlayerRole.WITCH)
        val guardToken = findRoleToken(gameId, tokenByUserId, PlayerRole.GUARD)
        val villagerUserId = gamePlayerRepository.findByGameId(gameId)
            .first { it.role == PlayerRole.VILLAGER }.userId

        // ── Night 1: Wolf kills seer, all roles act ──
        nightOrchestrator.initNight(gameId, 1, withWaiting = true)
        nightOrchestrator.advanceFromWaiting(gameId)

        // Wolf kills seer
        assertThat(action(wolfToken, gameId, "WOLF_KILL", seerUserId).statusCode).isEqualTo(HttpStatus.OK)

        // Seer checks a villager + confirms
        assertThat(action(seerToken, gameId, "SEER_CHECK", villagerUserId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(seerToken, gameId, "SEER_CONFIRM").statusCode).isEqualTo(HttpStatus.OK)

        // Witch poisons the 2nd wolf (needed: with 2 wolves, if both survive,
        // night 2 kill would make wolves >= others → GAME_OVER)
        assertThat(action(witchToken, gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false, "poisonTargetUserId" to wolf2UserId)).statusCode).isEqualTo(HttpStatus.OK)

        // Guard skips
        assertThat(action(guardToken, gameId, "GUARD_SKIP").statusCode).isEqualTo(HttpStatus.OK)

        // Wait for night resolution (last role close eyes audio + resolveNightKills)
        Thread.sleep(5000)

        // Verify seer and wolf2 are dead after night 1
        val seerPlayer = gamePlayerRepository.findByGameIdAndUserId(gameId, seerUserId).orElseThrow()
        assertThat(seerPlayer.alive).isFalse()
        val wolf2Player = gamePlayerRepository.findByGameIdAndUserId(gameId, wolf2UserId).orElseThrow()
        assertThat(wolf2Player.alive).isFalse()

        // Verify game is in DAY
        val dayGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(dayGame.phase).isEqualTo(GamePhase.DAY_DISCUSSION)

        // ── Night 2: Wolf kills villager, dead seer audio plays ──
        // Find a second target (not seer, not wolf, alive)
        val alivePlayers = gamePlayerRepository.findByGameId(gameId).filter { it.alive }
        val night2Target = alivePlayers.first { it.role == PlayerRole.VILLAGER }

        nightOrchestrator.initNight(gameId, 2)
        nightOrchestrator.advanceFromWaiting(gameId)

        // Verify night phase was created with correct dayNumber
        val nightPhase2 = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2).orElseThrow()
        assertThat(nightPhase2.dayNumber).isEqualTo(2)

        // Wolf kills villager → advance(WEREWOLF_PICK) → detects dead seer → schedules audio
        assertThat(action(wolfToken, gameId, "WOLF_KILL", night2Target.userId).statusCode).isEqualTo(HttpStatus.OK)

        // Wait for dead seer audio chain (2s open + 5s pause + 2s close + 5s inter-role gap = 14s)
        Thread.sleep(16000)

        // After dead seer audio, advance chain should have moved to WITCH_ACT
        val nightPhaseAfterSeer = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2).orElseThrow()
        assertThat(nightPhaseAfterSeer.subPhase).isIn(NightSubPhase.WITCH_ACT, NightSubPhase.GUARD_PICK)

        // Witch passes (poison already used in night 1)
        assertThat(action(witchToken, gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false)).statusCode).isEqualTo(HttpStatus.OK)

        // Guard protects someone
        assertThat(action(guardToken, gameId, "GUARD_SKIP").statusCode).isEqualTo(HttpStatus.OK)

        // Wait for night resolution
        Thread.sleep(5000)

        // Verify game transitions to DAY with correct day number
        val day2Game = gameRepository.findById(gameId).orElseThrow()
        assertThat(day2Game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(day2Game.dayNumber).isEqualTo(2)

        // Verify villager is dead
        val villagerPlayer = gamePlayerRepository.findByGameIdAndUserId(gameId, night2Target.userId).orElseThrow()
        assertThat(villagerPlayer.alive).isFalse()

        // Verify Bug 1 fix: night 2's NightPhase was used (not night 1's)
        val night1Phase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1).orElseThrow()
        val night2Phase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2).orElseThrow()
        // Night 2 should have the wolf target set (confirming correct NightPhase was used)
        assertThat(night2Phase.wolfTargetUserId).isEqualTo(night2Target.userId)
        // Night 1 should still have the original seer target
        assertThat(night1Phase.wolfTargetUserId).isEqualTo(seerUserId)
    }

    // ── Case 2.a: All special roles dead → dead role audio chain → GAME_OVER ──

    @Test
    fun `all special roles dead - dead audio chain completes and wolf wins`() {
        val (hostToken, roomId, tokenByUserId) = setupRoom("NR2")

        // Start game and confirm roles
        restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to roomId), headers(hostToken)), Map::class.java)
        val game = gameRepository.findAll().first { it.roomId == roomId }
        val gameId = game.gameId!!
        tokenByUserId.values.forEach { token -> action(token, gameId, "CONFIRM_ROLE") }

        // Discover role assignments (6 players → 2W + Seer + Witch + Guard + 1V)
        val wolves = gamePlayerRepository.findByGameId(gameId).filter { it.role == PlayerRole.WEREWOLF }
        val wolf1UserId = wolves[0].userId
        val wolf2UserId = wolves[1].userId
        val wolfToken = tokenByUserId[wolf1UserId] ?: error("Wolf1 token not found")
        val seerToken = findRoleToken(gameId, tokenByUserId, PlayerRole.SEER)
        val seerUserId = findRoleUserId(gameId, tokenByUserId, PlayerRole.SEER)
        val witchToken = findRoleToken(gameId, tokenByUserId, PlayerRole.WITCH)
        val witchUserId = findRoleUserId(gameId, tokenByUserId, PlayerRole.WITCH)
        val guardToken = findRoleToken(gameId, tokenByUserId, PlayerRole.GUARD)
        val guardUserId = findRoleUserId(gameId, tokenByUserId, PlayerRole.GUARD)
        val villagerUserId = gamePlayerRepository.findByGameId(gameId)
            .first { it.role == PlayerRole.VILLAGER }.userId

        // ── Night 1: Wolf kills seer, witch poisons wolf2 ──
        nightOrchestrator.initNight(gameId, 1, withWaiting = true)
        nightOrchestrator.advanceFromWaiting(gameId)

        assertThat(action(wolfToken, gameId, "WOLF_KILL", seerUserId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(seerToken, gameId, "SEER_CHECK", villagerUserId).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(seerToken, gameId, "SEER_CONFIRM").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(witchToken, gameId, "WITCH_ACT", payload = mapOf("useAntidote" to false, "poisonTargetUserId" to wolf2UserId)).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(action(guardToken, gameId, "GUARD_SKIP").statusCode).isEqualTo(HttpStatus.OK)

        // Wait for night 1 resolution
        Thread.sleep(5000)

        // After night 1: 1W + Witch + Guard + 1V alive (seer + wolf2 dead)
        val dayGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(dayGame.phase).isEqualTo(GamePhase.DAY_DISCUSSION)

        // ── Simulate prior rounds: mark witch and guard as dead ──
        // This lets us test the "all special roles dead" night scenario
        // without simulating multiple full day/night cycles
        gamePlayerRepository.findByGameIdAndUserId(gameId, witchUserId).ifPresent { it.alive = false; gamePlayerRepository.save(it) }
        gamePlayerRepository.findByGameIdAndUserId(gameId, guardUserId).ifPresent { it.alive = false; gamePlayerRepository.save(it) }

        // Now alive: 1W + 1V. All special roles (seer, witch, guard) are dead.

        // ── Night 2: All special roles dead, wolf kills villager → GAME_OVER ──
        nightOrchestrator.initNight(gameId, 2)
        nightOrchestrator.advanceFromWaiting(gameId)

        assertThat(action(wolfToken, gameId, "WOLF_KILL", villagerUserId).statusCode).isEqualTo(HttpStatus.OK)

        // Wait for dead role audio chain: seer(14s) + witch(14s) + guard(9s) + resolveNightKills = 37s
        // Per role with gap: 2s open_eyes + 5s pause + 2s close_eyes + 5s inter-role gap = 14s (last role: 9s)
        Thread.sleep(45000)

        // Verify game ended with WEREWOLF win
        val endGame = gameRepository.findById(gameId).orElseThrow()
        assertThat(endGame.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(endGame.winner).isEqualTo(WinnerSide.WEREWOLF)

        // Verify villager is dead
        val villagerPlayer = gamePlayerRepository.findByGameIdAndUserId(gameId, villagerUserId).orElseThrow()
        assertThat(villagerPlayer.alive).isFalse()

        // Verify night 2 phase completed
        val night2Phase = nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2).orElseThrow()
        assertThat(night2Phase.subPhase).isEqualTo(NightSubPhase.COMPLETE)
        assertThat(night2Phase.wolfTargetUserId).isEqualTo(villagerUserId)
    }
}
