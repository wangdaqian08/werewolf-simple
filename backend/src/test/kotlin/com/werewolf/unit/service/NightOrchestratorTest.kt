package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.night.NightWaitingScheduler
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
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

@ExtendWith(MockitoExtension::class)
class NightOrchestratorTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var nightWaitingScheduler: NightWaitingScheduler

    private lateinit var nightOrchestrator: NightOrchestrator

    private val gameId = 1
    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        nightOrchestrator = makeOrchestrator(emptyList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeOrchestrator(handlers: List<RoleHandler>) = NightOrchestrator(
        handlers = handlers,
        gameRepository = gameRepository,
        gamePlayerRepository = gamePlayerRepository,
        nightPhaseRepository = nightPhaseRepository,
        winConditionChecker = winConditionChecker,
        stompPublisher = stompPublisher,
        contextLoader = contextLoader,
        nightWaitingScheduler = nightWaitingScheduler,
    )

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
        it.dayNumber = 1
    }

    private fun room(hasSeer: Boolean = false) =
        Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = hasSeer)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun nightPhase(
        wolfTarget: String? = null,
        guardTarget: String? = null,
        antidoteUsed: Boolean = false,
        poisonTarget: String? = null,
    ) = NightPhase(gameId = gameId, dayNumber = 1).also {
        it.wolfTargetUserId = wolfTarget
        it.guardTargetUserId = guardTarget
        it.witchAntidoteUsed = antidoteUsed
        it.witchPoisonTargetUserId = poisonTarget
    }

    private fun ctx(vararg players: GamePlayer) = GameContext(game(), room(), players.toList())

    /** Minimal stub handler for advance() sequence testing. */
    private fun stubHandler(r: PlayerRole, vararg phases: NightSubPhase) = object : RoleHandler {
        override val role = r
        override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
        override fun handle(action: GameActionRequest, context: GameContext) = GameActionResult.Success()
        override fun nightSubPhases() = phases.toList()
    }

    // ── initNight ────────────────────────────────────────────────────────────

    @Test
    fun `initNight - creates NightPhase with correct dayNumber and prevGuardTarget`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 2, previousGuardTarget = "u1")

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.dayNumber).isEqualTo(2)
        assertThat(captor.firstValue.prevGuardTargetUserId).isEqualTo("u1")
    }

    @Test
    fun `initNight - sets game phase to NIGHT and saves`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 2)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(captor.firstValue.dayNumber).isEqualTo(2)
    }

    @Test
    fun `initNight - broadcasts PhaseChanged with NIGHT`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 1)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.firstValue).isInstanceOf(DomainEvent.PhaseChanged::class.java)
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).phase).isEqualTo(GamePhase.NIGHT)
    }

    @Test
    fun `initNight without withWaiting - first sub-phase is WEREWOLF_PICK (wolf always present)`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        orchestrator.initNight(gameId, newDayNumber = 1, withWaiting = false)

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK)
    }

    @Test
    fun `initNight with withWaiting=true - sub-phase starts as WAITING`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = true)

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(NightSubPhase.WAITING)
    }

    @Test
    fun `initNight with withWaiting=true - schedules automatic advance via scheduler`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = true)

        verify(nightWaitingScheduler).scheduleAdvance(gameId)
    }

    @Test
    fun `initNight with withWaiting=false - does NOT schedule waiting advance`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), room(), listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        nightOrchestrator.initNight(gameId, newDayNumber = 1, withWaiting = false)

        verify(nightWaitingScheduler, never()).scheduleAdvance(gameId)
    }

    // ── resolveNightKills ────────────────────────────────────────────────────

    @Test
    fun `resolveNightKills - wolf kills target when no guard, no antidote`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2")
        val initialCtx = ctx(wolf, victim)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(victim))
        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim.also { it.alive = false }))
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(victim.alive).isFalse()
        verify(gamePlayerRepository).save(victim)
    }

    @Test
    fun `resolveNightKills - guard protects wolf target, victim survives`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2", guardTarget = "u2")
        val initialCtx = ctx(wolf, victim)

        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim))
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(victim.alive).isTrue()
        verify(gamePlayerRepository, never()).save(victim)
    }

    @Test
    fun `resolveNightKills - witch antidote saves wolf target`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2", antidoteUsed = true)
        val initialCtx = ctx(wolf, victim)

        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim))
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(victim.alive).isTrue()
        verify(gamePlayerRepository, never()).save(victim)
    }

    @Test
    fun `resolveNightKills - witch poisons separate target, two players die`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val wolfVictim = player("u2", 2)
        val poisonVictim = player("u3", 3)
        val np = nightPhase(wolfTarget = "u2", poisonTarget = "u3")
        val initialCtx = ctx(wolf, wolfVictim, poisonVictim)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(wolfVictim))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u3")).thenReturn(Optional.of(poisonVictim))
        whenever(contextLoader.load(gameId)).thenReturn(
            ctx(wolf, wolfVictim.also { it.alive = false }, poisonVictim.also { it.alive = false })
        )
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(wolfVictim.alive).isFalse()
        assertThat(poisonVictim.alive).isFalse()
    }

    @Test
    fun `resolveNightKills - no kills, transitions to DAY phase`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val v1 = player("u2", 2)
        val v2 = player("u3", 3)
        val np = nightPhase() // no targets
        val initialCtx = ctx(wolf, v1, v2)

        whenever(contextLoader.load(gameId)).thenReturn(initialCtx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.DAY)
        assertThat(captor.firstValue.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `resolveNightKills - werewolf wins when wolves outnumber others after kill`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2")
        val initialCtx = ctx(wolf, victim)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(victim))
        val afterKillCtx = ctx(wolf, victim.also { it.alive = false })
        whenever(contextLoader.load(gameId)).thenReturn(afterKillCtx)
        whenever(winConditionChecker.check(eq(listOf(wolf)), any())).thenReturn(WinnerSide.WEREWOLF)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(captor.firstValue.winner).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `resolveNightKills - villagers win when all wolves are dead`() {
        val villager = player("u2", 2)
        val deadWolf = player("u1", 1, PlayerRole.WEREWOLF, alive = false)
        val np = nightPhase()
        val initialCtx = GameContext(game(), room(), listOf(villager, deadWolf))

        val afterCtx = GameContext(game(), room(), listOf(villager, deadWolf))
        whenever(contextLoader.load(gameId)).thenReturn(afterCtx)
        whenever(winConditionChecker.check(eq(listOf(villager)), any())).thenReturn(WinnerSide.VILLAGER)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(captor.firstValue.winner).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── advance ──────────────────────────────────────────────────────────────

    @Test
    fun `advance - moves to next sub-phase when sequence has remaining phases`() {
        // Two-phase sequence: WEREWOLF_PICK → SEER_CHECK
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val room = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = true)
        val ctx = GameContext(game(), room, emptyList(), nightPhase = np)
        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        assertThat(np.subPhase).isEqualTo(NightSubPhase.SEER_PICK)
        verify(nightPhaseRepository).save(np)
    }

    @Test
    fun `advance - resolves night kills when last sub-phase completes`() {
        // Single-phase sequence: WEREWOLF_PICK only
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val ctx = GameContext(game(), room(), emptyList(), nightPhase = np)
        // advance() calls contextLoader.load once; resolveNightKills calls it again
        whenever(contextLoader.load(gameId)).thenReturn(ctx, ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Night kills resolved → DAY transition
        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.DAY)
    }
}
