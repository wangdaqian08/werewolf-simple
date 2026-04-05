package com.werewolf.integration

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.role.GuardHandler
import com.werewolf.game.role.WerewolfHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

/**
 * Integration test for guard protection feature.
 * Tests the complete flow: wolf kills target, guard protects target, victim survives the night.
 */
@ExtendWith(MockitoExtension::class)
class GuardProtectionIntegrationTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var winConditionChecker: com.werewolf.game.phase.WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader

    private lateinit var nightOrchestrator: NightOrchestrator
    private lateinit var guardHandler: GuardHandler
    private lateinit var wolfHandler: WerewolfHandler

    private val gameId = 1
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val guardId = "guard:001"
    private val victimId = "victim:001"

    @BeforeEach
    fun setUp() {
        guardHandler = GuardHandler(nightPhaseRepository)
        wolfHandler = WerewolfHandler(nightPhaseRepository)
        nightOrchestrator = NightOrchestrator(
            handlers = listOf(wolfHandler, guardHandler),
            gameRepository = gameRepository,
            gamePlayerRepository = gamePlayerRepository,
            nightPhaseRepository = nightPhaseRepository,
            winConditionChecker = winConditionChecker,
            stompPublisher = stompPublisher,
            contextLoader = contextLoader,
            nightWaitingScheduler = mock() // Not needed for this test
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun game(phase: GamePhase = GamePhase.NIGHT, subPhase: String = NightSubPhase.WEREWOLF_PICK.name) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
            it.dayNumber = 1
        }

    private fun room() = Room(
        roomCode = "ABCD",
        hostUserId = hostId,
        totalPlayers = 6,
        hasSeer = false,
        hasWitch = false,
        hasGuard = true
    )

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(
        dayNumber: Int = 1,
        subPhase: NightSubPhase = NightSubPhase.WEREWOLF_PICK,
        wolfTarget: String? = null,
        guardTarget: String? = null,
        prevGuardTarget: String? = null
    ) = NightPhase(gameId = gameId, dayNumber = dayNumber).also {
        it.subPhase = subPhase
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.prevGuardTargetUserId = prevGuardTarget
    }

    private fun ctx(vararg players: GamePlayer, nightPhase: NightPhase = nightPhase()) =
        GameContext(game(), room(), players.toList(), nightPhase = nightPhase)

    // ── Integration Tests ────────────────────────────────────────────────────

    @Test
    fun `Guard protects victim - Complete night flow with wolf kill and guard protection`() {
        // Setup players: wolf, guard, and victim
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val guard = player(guardId, 2, PlayerRole.GUARD)
        val victim = player(victimId, 3, PlayerRole.VILLAGER)

        val np = nightPhase()

        // Step 1: Wolf picks kill target
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        val wolfRequest = GameActionRequest(
            gameId = gameId,
            actorUserId = wolfId,
            actionType = ActionType.WOLF_KILL,
            targetUserId = victimId
        )
        val wolfCtx = ctx(wolf, guard, victim, nightPhase = np)
        val wolfResult = wolfHandler.handle(wolfRequest, wolfCtx)

        assertThat(wolfResult).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.wolfTargetUserId).isEqualTo(victimId)

        // Step 2: Guard protects the victim (advance to GUARD_PICK sub-phase)
        np.subPhase = NightSubPhase.GUARD_PICK
        val guardRequest = GameActionRequest(
            gameId = gameId,
            actorUserId = guardId,
            actionType = ActionType.GUARD_PROTECT,
            targetUserId = victimId
        )
        val guardCtx = ctx(wolf, guard, victim, nightPhase = np)
        val guardResult = guardHandler.handle(guardRequest, guardCtx)

        assertThat(guardResult).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(np.guardTargetUserId).isEqualTo(victimId)

        // Step 3: Resolve night kills
        whenever(contextLoader.load(gameId))
            .thenReturn(ctx(wolf, guard, victim))
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(guardCtx, np)

        // Verify victim survives
        assertThat(victim.alive).isTrue()

        // Verify victim is not saved (no kill)
        verify(gamePlayerRepository, never()).save(victim)

        // Verify NightResult is broadcast with no kills
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { e ->
            e is DomainEvent.NightResult && e.kills.isEmpty()
        })

        // Verify game transitions to DAY phase
        verify(gameRepository).save(argThat<Game> { g -> g.phase == GamePhase.DAY })
    }

    @Test
    fun `Guard protects different player - Wolf target dies`() {
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val guard = player(guardId, 2, PlayerRole.GUARD)
        val victim = player(victimId, 3, PlayerRole.VILLAGER)
        val otherPlayer = player("other:001", 4, PlayerRole.VILLAGER)

        val np = nightPhase()

        // Wolf kills victim
        val wolfRequest = GameActionRequest(
            gameId = gameId,
            actorUserId = wolfId,
            actionType = ActionType.WOLF_KILL,
            targetUserId = victimId
        )
        val wolfCtx = ctx(wolf, guard, victim, otherPlayer, nightPhase = np)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        wolfHandler.handle(wolfRequest, wolfCtx)

        // Guard protects other player (not victim)
        val guardRequest = GameActionRequest(
            gameId = gameId,
            actorUserId = guardId,
            actionType = ActionType.GUARD_PROTECT,
            targetUserId = "other:001"
        )
        guardHandler.handle(guardRequest, wolfCtx)

        // Resolve night kills
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, victimId))
            .thenReturn(Optional.of(victim))
        whenever(contextLoader.load(gameId))
            .thenReturn(ctx(wolf, guard, victim, otherPlayer))
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(wolfCtx, np)

        // Verify victim dies (not protected)
        assertThat(victim.alive).isFalse()
        verify(gamePlayerRepository).save(victim)

        // Verify NightResult includes victim
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { e ->
            e is DomainEvent.NightResult && e.kills.contains(victimId)
        })
    }

    @Test
    fun `Guard cannot protect same player two nights in a row`() {
        val wolf = player(wolfId, 1, PlayerRole.WEREWOLF)
        val guard = player(guardId, 2, PlayerRole.GUARD)
        val victim = player(victimId, 3, PlayerRole.VILLAGER)

        // First night: guard protects victim
        val night1 = nightPhase(dayNumber = 1, prevGuardTarget = null, subPhase = NightSubPhase.GUARD_PICK)
        val guardRequest1 = GameActionRequest(
            gameId = gameId,
            actorUserId = guardId,
            actionType = ActionType.GUARD_PROTECT,
            targetUserId = victimId
        )
        val ctx1 = ctx(wolf, guard, victim, nightPhase = night1)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        val result1 = guardHandler.handle(guardRequest1, ctx1)

        assertThat(result1).isInstanceOf(GameActionResult.Success::class.java)

        // Second night: guard tries to protect same victim
        val night2 = nightPhase(dayNumber = 2, prevGuardTarget = victimId, subPhase = NightSubPhase.GUARD_PICK)
        val guardRequest2 = GameActionRequest(
            gameId = gameId,
            actorUserId = guardId,
            actionType = ActionType.GUARD_PROTECT,
            targetUserId = victimId
        )
        val ctx2 = ctx(wolf, guard, victim, nightPhase = night2)
        val result2 = guardHandler.handle(guardRequest2, ctx2)

        // Should be rejected
        assertThat(result2).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result2 as GameActionResult.Rejected).reason).contains("same player two nights")
    }
}