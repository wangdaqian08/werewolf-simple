package com.werewolf.integration

import com.werewolf.game.DomainEvent
import com.werewolf.integration.TestConstants.CREATE_ROOM_URL
import com.werewolf.integration.TestConstants.FIELD_CONFIG
import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_ROLES
import com.werewolf.integration.TestConstants.FIELD_ROOM_CODE
import com.werewolf.integration.TestConstants.FIELD_ROOM_ID
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_TOTAL_PLAYERS
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.JOIN_ROOM_URL
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.OrphanedGameRecovery
import com.werewolf.service.PERK_NIGHT1_IMMUNITY
import com.werewolf.service.RolePickService
import com.werewolf.service.isRolePickCode
import com.werewolf.service.StompPublisher
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean

/**
 * Regression for the prod incident on 2026-04-29 (game=10): a backend
 * redeploy mid-game wiped the in-memory NightOrchestrator state, so any
 * subsequent WOLF_KILL hit "No pending action — queuing signal for next
 * await", queued forever, and the game stuck.
 *
 * The recovery contract: on startup, every in-flight game is force-ended
 * (forfeit), the room reopens to WAITING, every non-host player resets to
 * NOT_READY, and a GameOver event is broadcast so any client still on
 * GameView auto-routes to the Result screen.
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrphanedGameRecoveryIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var gameRepository: GameRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomPlayerRepository: RoomPlayerRepository
    @Autowired lateinit var orphanedGameRecovery: OrphanedGameRecovery
    @Autowired lateinit var abandonedGameSweep: com.werewolf.service.AbandonedGameSweep
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var perkRepository: PerkRepository
    @Autowired lateinit var rolePickService: RolePickService

    @MockitoSpyBean lateinit var stompPublisher: StompPublisher

    companion object {
        private const val SEAT_URL = "/api/room/seat"
        private const val READY_URL = "/api/room/ready"
        private const val START_URL = "/api/game/start"
        private const val ROLE_PICK_URL = "/api/room/role-pick"
        private const val TOTAL_PLAYERS = 4
        // 2 wolves (RoomConfigRequest default) + 1 god + 1 villager = 4 seats.
        // Backend now rejects role lists that overflow the seat budget; the
        // historical SEER+WITCH+HUNTER set silently over-filled 4-player rooms.
        private val DEFAULT_ROLES = listOf(PlayerRole.SEER)
    }

    private fun login(nickname: String): String {
        val resp = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val token = resp.body!![FIELD_TOKEN] as String
        userIdByToken[token] = (resp.body!![FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String
        return token
    }

    private val userIdByToken = mutableMapOf<String, String>()

    private fun authHeaders(token: String) = HttpHeaders().also {
        it.setBearerAuth(token)
        it.contentType = MediaType.APPLICATION_JSON
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupRoomAndStartGame(
        prefix: String,
        perksAllowed: Boolean = false,
        // Runs for every player (host included) after seating, before ready; arguments: token, roomId.
        beforeReady: (String, Int) -> Unit = { _, _ -> },
    ): Triple<String, Int, Int> {
        val hostToken = login("${prefix}Host")
        val createBody = mapOf(FIELD_CONFIG to mapOf(FIELD_TOTAL_PLAYERS to TOTAL_PLAYERS, FIELD_ROLES to DEFAULT_ROLES, "perksAllowed" to perksAllowed))
        val room = restTemplate.postForEntity(
            CREATE_ROOM_URL, HttpEntity(createBody, authHeaders(hostToken)), Map::class.java
        ).body!! as Map<String, Any?>
        val roomId = (room[FIELD_ROOM_ID] as String).toInt()
        val roomCode = room[FIELD_ROOM_CODE] as String

        restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to 0, "roomId" to roomId), authHeaders(hostToken)), Map::class.java)
        beforeReady(hostToken, roomId)
        repeat(TOTAL_PLAYERS - 1) { i ->
            val token = login("${prefix}Guest$i")
            restTemplate.postForEntity(JOIN_ROOM_URL, HttpEntity(mapOf("roomCode" to roomCode), authHeaders(token)), Map::class.java)
            restTemplate.postForEntity(SEAT_URL, HttpEntity(mapOf("seatIndex" to i + 1, "roomId" to roomId), authHeaders(token)), Map::class.java)
            beforeReady(token, roomId)
            restTemplate.postForEntity(READY_URL, HttpEntity(mapOf("ready" to true, "roomId" to roomId), authHeaders(token)), Map::class.java)
        }

        val startResp = restTemplate.postForEntity(
            START_URL, HttpEntity(mapOf("roomId" to roomId), authHeaders(hostToken)), Map::class.java
        )
        assertThat(startResp.statusCode).isEqualTo(HttpStatus.OK)

        val game = gameRepository.findAll().first { it.roomId == roomId && it.endedAt == null }
        return Triple(hostToken, roomId, game.gameId!!)
    }

    @Test
    fun `cancelInFlightGames force-ends in-flight games and reopens their rooms`() {
        val (_, roomId, gameId) = setupRoomAndStartGame("Orphan1")

        // Sanity: game is currently in-flight (just created via /api/game/start).
        assertThat(gameRepository.findById(gameId).orElseThrow().endedAt).isNull()
        assertThat(roomRepository.findById(roomId).orElseThrow().status).isEqualTo(RoomStatus.IN_GAME)

        // Drop the broadcasts that came from setup so we can assert exactly the
        // one this method should produce.
        org.mockito.Mockito.clearInvocations(stompPublisher)

        orphanedGameRecovery.cancelInFlightGames()

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.endedAt).isNotNull
        assertThat(game.winner).isNull() // forfeit — no winner

        val room = roomRepository.findById(roomId).orElseThrow()
        assertThat(room.status).isEqualTo(RoomStatus.WAITING)

        val players = roomPlayerRepository.findByRoomId(roomId)
        assertThat(players).hasSize(TOTAL_PLAYERS)
        // Every non-host seat is reset to NOT_READY.
        assertThat(players.filter { !it.host }).allMatch { it.status == ReadyStatus.NOT_READY }

        // Exactly one GameOver event went out on this game's topic.
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val event = captor.firstValue as DomainEvent.GameOver
        assertThat(event.gameId).isEqualTo(gameId)
        assertThat(event.winner).isNull()
    }

    @Test
    fun `cancelInFlightGames is idempotent — already-ended games are not re-cancelled`() {
        val (_, _, gameId) = setupRoomAndStartGame("Orphan2")

        // First pass: cancels.
        orphanedGameRecovery.cancelInFlightGames()
        val firstEndedAt = gameRepository.findById(gameId).orElseThrow().endedAt
        assertThat(firstEndedAt).isNotNull

        org.mockito.Mockito.clearInvocations(stompPublisher)

        // Second pass: nothing to do — endedAt query excludes this row.
        orphanedGameRecovery.cancelInFlightGames()

        val secondEndedAt = gameRepository.findById(gameId).orElseThrow().endedAt
        assertThat(secondEndedAt).isEqualTo(firstEndedAt)
        verify(stompPublisher, org.mockito.kotlin.never()).broadcastGame(eq(gameId), org.mockito.kotlin.any())
    }

    @Test
    fun `cancelling an in-flight game refunds its bound ACTIVE perk activation`() {
        // Seed directly: an in-flight game with a bound, still-ACTIVE activation
        // (the holder was charged at activation time; the credit row is not
        // needed for the refund path).
        val user = "guest:orphan-perk-${System.nanoTime()}"
        // room_code is VARCHAR(3) — use a 3-digit code like the join-flow does.
        val room = roomRepository.save(Room(roomCode = TestConstants.nextSeededRoomCode(), hostUserId = user, totalPlayers = 6))
        val roomId = room.roomId ?: error("room not persisted")
        val game = gameRepository.save(Game(roomId = roomId, hostUserId = user))
        val gameId = game.gameId ?: error("game not persisted")
        val activation = perkActivationRepository.save(
            PerkActivation(roomId = roomId, gameId = gameId, userId = user, perkCode = PERK_NIGHT1_IMMUNITY, pricePaid = 30),
        )

        orphanedGameRecovery.cancelInFlightGames()

        assertThat(gameRepository.findById(gameId).orElseThrow().endedAt).isNotNull
        val settled = perkActivationRepository.findById(activation.id ?: error("no id")).orElseThrow()
        assertThat(settled.status).isEqualTo(PerkActivationStatus.REFUNDED)
        assertThat(settled.settledAt).isNotNull()
        assertThat(walletService.balance(user)).isEqualTo(30)
        val refunds = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(user)
            .filter { it.type == CreditTxType.REFUND }
        assertThat(refunds).hasSize(1)
        assertThat(refunds.single().perkActivationId).isEqualTo(settled.id)
    }

    private fun chooseRolePick(token: String, roomId: Int, choice: RolePickChoice) {
        val resp = restTemplate.postForEntity(
            ROLE_PICK_URL, HttpEntity(mapOf("roomId" to roomId, "choice" to choice.name), authHeaders(token)), Map::class.java,
        )
        assertThat(resp.statusCode).describedAs("role-pick %s: %s", choice, resp.body).isEqualTo(HttpStatus.OK)
    }

    private fun ensureRolePickPerks() {
        listOf("ROLE_PICK_WOLF", "ROLE_PICK_GOD", "ROLE_PICK_VILLAGER").forEach { code ->
            if (perkRepository.findById(code).isEmpty)
                perkRepository.save(Perk(perkCode = code, name = code, description = "test", priceCredits = 20))
        }
    }

    @Test
    fun `recovery clears every rolePick, refunds the cancelled game's role cards, and the player can choose again`() {
        ensureRolePickPerks()
        val userIds = mutableListOf<String>()
        val (_, roomId, gameId) = setupRoomAndStartGame("OrphanPick", perksAllowed = true) { token, rid ->
            val userId = userIdByToken.getValue(token)
            userIds += userId
            walletService.credit(userId, 100, CreditTxType.GAME_REWARD, note = "test-seed")
            chooseRolePick(token, rid, RolePickChoice.WOLF)
        }
        val picker = userIds.first()
        assertThat(perkActivationRepository.findByGameId(gameId).filter { isRolePickCode(it.perkCode) }).hasSize(TOTAL_PLAYERS)

        orphanedGameRecovery.cancelInFlightGames()

        assertThat(roomPlayerRepository.findByRoomId(roomId)).allMatch { it.rolePick == null }
        assertThat(perkActivationRepository.findByGameId(gameId).filter { isRolePickCode(it.perkCode) })
            .allMatch { it.status == PerkActivationStatus.REFUNDED }
        userIds.forEach { assertThat(walletService.balance(it)).isEqualTo(100) }

        val view = rolePickService.choose(picker, roomId, RolePickChoice.WOLF)
        assertThat(view.choice).isEqualTo(RolePickChoice.WOLF)
        assertThat(walletService.balance(picker)).isEqualTo(80)
    }

    // ── Abandoned live games (no restart needed) ─────────────────────────────

    @Test
    fun `a live game nobody touched for two hours is cancelled like a restart would`() {
        val (_, roomId, gameId) = setupRoomAndStartGame("Abandon1")
        org.mockito.Mockito.clearInvocations(stompPublisher)

        assertThat(abandonedGameSweep.cancelAbandoned(java.time.LocalDateTime.now().plusMinutes(121))).contains(gameId)

        val game = gameRepository.findById(gameId).orElseThrow()
        assertThat(game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(game.endedAt).isNotNull
        assertThat(game.winner).isNull()
        assertThat(roomRepository.findById(roomId).orElseThrow().status).isEqualTo(RoomStatus.WAITING)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.GameOver).winner).isNull()
    }

    @Test
    fun `a live game with a recent action is left alone`() {
        val (hostToken, _, gameId) = setupRoomAndStartGame("Abandon2")
        val confirm = restTemplate.postForEntity(
            "/api/game/action",
            HttpEntity(mapOf("gameId" to gameId, "actionType" to "CONFIRM_ROLE"), authHeaders(hostToken)),
            Map::class.java,
        )
        assertThat(confirm.statusCode).isEqualTo(HttpStatus.OK)

        assertThat(abandonedGameSweep.cancelAbandoned(java.time.LocalDateTime.now().plusMinutes(119))).doesNotContain(gameId)
        assertThat(gameRepository.findById(gameId).orElseThrow().endedAt).isNull()
    }
}
