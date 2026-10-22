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
 * Integration coverage for the Black Wolf King (黑狼王) shot — the HUNTER_SHOOT flow
 * shared with the hunter — and the hunter ↔ king chain (docs/black-wolf-king.md).
 *
 * Setup mirrors HunterNightDeathShootIntegrationTest: park the game at "Day 2
 * morning, ready to reveal" with forced roles, then drive the real HTTP endpoints
 * (reveal, vote, shoot). Default layout: host=GUARD, g1=HUNTER, g2=BLACK_WOLF_KING,
 * g3=WEREWOLF, g4/g5=VILLAGER — a god and a villager survive every chain below,
 * so no case trips 屠边 by accident.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BlackWolfKingShootIntegrationTest {

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
        val hunter get() = g1
        val king get() = g2
    }

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

    private fun ok(token: String, gameId: Int, actionType: String, targetUserId: String? = null) =
        assertThat(action(token, gameId, actionType, targetUserId).statusCode)
            .withFailMessage("$actionType should be accepted")
            .isEqualTo(HttpStatus.OK)

    private fun setupRoom(prefix: String, winCondition: String = "CLASSIC"): Room6 {
        val host = login("${prefix}H")
        val guests = (1..5).map { login("${prefix}G$it") }

        @Suppress("UNCHECKED_CAST")
        val roomBody = restTemplate.postForEntity(
            CREATE_ROOM_URL,
            HttpEntity(
                mapOf(
                    FIELD_CONFIG to mapOf(
                        FIELD_TOTAL_PLAYERS to 6,
                        "wolfCount" to 2,
                        "roles" to listOf("BLACK_WOLF_KING", "HUNTER", "GUARD"),
                        "hasSheriff" to true,
                        "winCondition" to winCondition,
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
        guests.forEachIndexed { idx, player ->
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), headers(player.token)), Map::class.java)
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
        return Room6(host, guests[0], guests[1], guests[2], guests[3], guests[4], roomId)
    }

    /** Overwrite a player's (val) role in the DB — the deal isn't under test. */
    private fun forceRole(gameId: Int, userId: String, role: PlayerRole) {
        val gp = gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow()
        val f = GamePlayer::class.java.getDeclaredField("role")
        f.isAccessible = true
        f.set(gp, role)
        gamePlayerRepository.save(gp)
    }

    /** Start the game, force [layout] (default: see class doc) and park at Day-2 RESULT_HIDDEN. */
    private fun openDay2(
        room: Room6,
        layout: List<PlayerRole> = listOf(
            PlayerRole.GUARD, PlayerRole.HUNTER, PlayerRole.BLACK_WOLF_KING,
            PlayerRole.WEREWOLF, PlayerRole.VILLAGER, PlayerRole.VILLAGER,
        ),
    ): Int {
        assertThat(
            restTemplate.postForEntity(START_URL, HttpEntity(mapOf("roomId" to room.roomId), headers(room.host.token)), Map::class.java)
                .statusCode
        ).isEqualTo(HttpStatus.OK)
        val game = gameRepository.findAll().first { it.roomId == room.roomId }
        val gameId = game.gameId!!
        room.all.forEach { ok(it.token, gameId, "CONFIRM_ROLE") }
        room.all.zip(layout).forEach { (p, role) -> forceRole(gameId, p.userId, role) }

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

    private fun reveal(room: Room6, gameId: Int) = ok(room.host.token, gameId, "REVEAL_NIGHT_RESULT")

    /** Peaceful night, reveal, then everyone alive votes [target] out. */
    private fun voteOut(room: Room6, gameId: Int, target: TestPlayer) {
        saveNight(gameId, wolfTarget = null)
        reveal(room, gameId)
        ok(room.host.token, gameId, "DAY_ADVANCE")
        room.all.filter { it != target && alive(gameId, it.userId) }
            .forEach { ok(it.token, gameId, "SUBMIT_VOTE", target.userId) }
        ok(room.host.token, gameId, "VOTING_REVEAL_TALLY")
    }

    private fun game(gameId: Int) = gameRepository.findById(gameId).orElseThrow()
    private fun subPhase(gameId: Int) = game(gameId).subPhase
    private fun alive(gameId: Int, userId: String) =
        gamePlayerRepository.findByGameIdAndUserId(gameId, userId).orElseThrow().alive

    // ── Single shot ────────────────────────────────────────────────────────────

    @Test
    fun `voted-out BLACK_WOLF_KING shoots a villager - day pauses on VOTE_RESULT`() {
        val room = setupRoom("BK1")
        val gameId = openDay2(room)
        voteOut(room, gameId, room.king)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.HUNTER_SHOOT.name)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(game(gameId).phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    @Test
    fun `self-knifed BLACK_WOLF_KING shoots at day reveal - then RESULT_REVEALED`() {
        val room = setupRoom("BK2")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.king.userId)

        reveal(room, gameId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `poisoned BLACK_WOLF_KING gets no shot`() {
        val room = setupRoom("BK3")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.king.userId, poisonTarget = room.king.userId)

        reveal(room, gameId)

        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(action(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `BLACK_WOLF_KING self-destruct gives no shot and cannot take anyone`() {
        val room = setupRoom("BK4")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = null)
        reveal(room, gameId)

        // Only the White Wolf King may take a player.
        assertThat(action(room.king.token, gameId, "WOLF_SELF_DESTRUCT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        ok(room.king.token, gameId, "WOLF_SELF_DESTRUCT")

        assertThat(alive(gameId, room.king.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(action(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(alive(gameId, room.g4.userId)).isTrue()
    }

    // ── Chains ─────────────────────────────────────────────────────────────────

    @Test
    fun `vote-out chain - BLACK_WOLF_KING shoots the hunter, the hunter shoots back`() {
        val room = setupRoom("BK5")
        val gameId = openDay2(room)
        voteOut(room, gameId, room.king)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.hunter.userId)

        assertThat(alive(gameId, room.hunter.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.HUNTER_SHOOT.name)
        assertThat(game(gameId).chainShooterUserId).isEqualTo(room.hunter.userId)
        // The king already fired: a second shot from it is rejected.
        assertThat(action(room.king.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)

        ok(room.hunter.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(alive(gameId, room.g5.userId)).isTrue()
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(game(gameId).chainShooterUserId).isNull()
    }

    @Test
    fun `vote-out chain - the hunter shoots the BLACK_WOLF_KING, the king shoots back`() {
        val room = setupRoom("BK6")
        val gameId = openDay2(room)
        voteOut(room, gameId, room.hunter)

        ok(room.hunter.token, gameId, "HUNTER_SHOOT", room.king.userId)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.HUNTER_SHOOT.name)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.king.userId)).isFalse()
        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    @Test
    fun `night chain - knifed BLACK_WOLF_KING shoots the hunter, the hunter shoots, then RESULT_REVEALED`() {
        val room = setupRoom("BK7")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.king.userId)
        reveal(room, gameId)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.hunter.userId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)
        assertThat(action(room.king.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)

        ok(room.hunter.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.hunter.userId)).isFalse()
        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game(gameId).chainShooterUserId).isNull()
    }

    @Test
    fun `night chain - knifed hunter shoots the BLACK_WOLF_KING, the king shoots, then RESULT_REVEALED`() {
        val room = setupRoom("BK10")
        val gameId = openDay2(room)
        saveNight(gameId, wolfTarget = room.hunter.userId)
        reveal(room, gameId)

        ok(room.hunter.token, gameId, "HUNTER_SHOOT", room.king.userId)
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH.name)
        assertThat(game(gameId).chainShooterUserId).isEqualTo(room.king.userId)
        assertThat(action(room.hunter.token, gameId, "HUNTER_SHOOT", room.g5.userId).statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)

        ok(room.king.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        assertThat(alive(gameId, room.king.userId)).isFalse()
        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(subPhase(gameId)).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        assertThat(game(gameId).chainShooterUserId).isNull()
    }

    @Test
    fun `a chain ending at the sheriff hands the badge over, then VOTE_RESULT`() {
        val room = setupRoom("BK8")
        val gameId = openDay2(room)
        setSheriff(gameId, room.g4.userId)
        voteOut(room, gameId, room.king)
        ok(room.king.token, gameId, "HUNTER_SHOOT", room.hunter.userId)

        ok(room.hunter.token, gameId, "HUNTER_SHOOT", room.g4.userId)

        // 国标: the shot sheriff stays alive until the badge is handed over.
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.BADGE_HANDOVER.name)
        assertThat(alive(gameId, room.g4.userId)).isTrue()

        ok(room.g4.token, gameId, "BADGE_PASS", room.g5.userId)

        assertThat(alive(gameId, room.g4.userId)).isFalse()
        assertThat(game(gameId).sheriffUserId).isEqualTo(room.g5.userId)
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    // ── HARD_MODE counterplay ──────────────────────────────────────────────────

    @Test
    fun `HARD_MODE - a living hunter keeps the counterplay after the BLACK_WOLF_KING fired`() {
        val room = setupRoom("BK9", winCondition = "HARD_MODE")
        // host=VILLAGER, g1=HUNTER, g2=BLACK_WOLF_KING, g3/g4=WEREWOLF, g5=VILLAGER.
        val gameId = openDay2(
            room,
            listOf(
                PlayerRole.VILLAGER, PlayerRole.HUNTER, PlayerRole.BLACK_WOLF_KING,
                PlayerRole.WEREWOLF, PlayerRole.WEREWOLF, PlayerRole.VILLAGER,
            ),
        )
        voteOut(room, gameId, room.king)

        // 2 wolves vs host + hunter after the shot: parity, but the hunter still holds his bullet.
        ok(room.king.token, gameId, "HUNTER_SHOOT", room.g5.userId)

        assertThat(game(gameId).phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(game(gameId).winner).isNull()
        assertThat(subPhase(gameId)).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }
}
