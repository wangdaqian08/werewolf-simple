package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import java.util.*

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NightOrchestratorTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var eliminationHistoryRepository: com.werewolf.repository.EliminationHistoryRepository
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var audioService: com.werewolf.service.AudioService
    @Mock lateinit var actionLogService: com.werewolf.service.ActionLogService

    private lateinit var nightOrchestrator: NightOrchestrator

    private val gameId = 1
    private val hostId = "host:001"

    @BeforeEach
    fun setUp() {
        // Initialize RoleRegistry for tests
        com.werewolf.audio.RoleRegistry.registerAll(
            listOf(
                com.werewolf.audio.impl.WerewolfAudioConfig(),
                com.werewolf.audio.impl.SeerAudioConfig(),
                com.werewolf.audio.impl.WitchAudioConfig(),
                com.werewolf.audio.impl.GuardAudioConfig(),
                com.werewolf.audio.impl.HunterAudioConfig(),
                com.werewolf.audio.impl.IdiotAudioConfig(),
                com.werewolf.audio.impl.VillagerAudioConfig()
            )
        )
        nightOrchestrator = makeOrchestrator(emptyList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeOrchestrator(handlers: List<RoleHandler>) = NightOrchestrator(
        handlers = handlers,
        gameRepository = gameRepository,
        gamePlayerRepository = gamePlayerRepository,
        nightPhaseRepository = nightPhaseRepository,
        eliminationHistoryRepository = eliminationHistoryRepository,
        winConditionChecker = winConditionChecker,
        stompPublisher = stompPublisher,
        contextLoader = contextLoader,
        audioService = audioService,
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        actionLogService = actionLogService,
        timing = com.werewolf.config.GameTimingProperties(),
    )

    private fun mockAudioServiceForDayTransition(r: Room) {
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-DAY",
            phase = GamePhase.DAY_DISCUSSION,
            subPhase = DaySubPhase.RESULT_HIDDEN.name,
            audioFiles = listOf("day_time.mp3"),
            priority = 10,
            timestamp = System.currentTimeMillis()
        )
        whenever(audioService.calculatePhaseTransition(
            eq(gameId),
            eq(GamePhase.NIGHT),
            eq(GamePhase.DAY_DISCUSSION),
            eq(null),
            eq(DaySubPhase.RESULT_HIDDEN.name),
            eq(r)
        )).thenReturn(audioSequence)
    }

    private fun mockAudioServiceForNightSubPhaseTransition(oldSubPhase: NightSubPhase?, newSubPhase: NightSubPhase) {
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-NIGHT-TRANSITION",
            phase = GamePhase.NIGHT,
            subPhase = newSubPhase.name,
            audioFiles = if (oldSubPhase != null) {
                listOf("wolf_close_eyes.mp3", "seer_open_eyes.mp3")
            } else {
                listOf("seer_open_eyes.mp3")
            },
            priority = 5,
            timestamp = System.currentTimeMillis()
        )
        whenever(audioService.calculateNightSubPhaseTransition(
            eq(gameId),
            eq(oldSubPhase),
            eq(newSubPhase)
        )).thenReturn(audioSequence)
    }

    private fun mockAudioServiceForNightTransition(r: Room, initialSubPhase: NightSubPhase) {
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-NIGHT",
            phase = GamePhase.NIGHT,
            subPhase = initialSubPhase.name,
            audioFiles = if (initialSubPhase == NightSubPhase.WAITING) emptyList() else listOf("goes_dark_close_eyes.mp3"),
            priority = 10,
            timestamp = System.currentTimeMillis()
        )
        whenever(audioService.calculatePhaseTransition(
            eq(gameId),
            eq(GamePhase.DAY_DISCUSSION),
            eq(GamePhase.NIGHT),
            eq(null),
            eq(initialSubPhase.name),
            eq(r)
        )).thenReturn(audioSequence)
    }

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
        it.dayNumber = 1
    }

    private fun room(hasSeer: Boolean = false, hasWitch: Boolean = false, hasGuard: Boolean = false) =
        Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = hasSeer, hasWitch = hasWitch, hasGuard = hasGuard)

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

    private fun ctx(vararg players: GamePlayer, roomOverride: Room? = null) = GameContext(game(), roomOverride ?: room(), players.toList())

    /** Minimal stub handler for night sequence testing. */
    private fun stubHandler(r: PlayerRole, vararg phases: NightSubPhase) = object : RoleHandler {
        override val role = r
        override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
        override fun handle(action: GameActionRequest, context: GameContext) = GameActionResult.Success()
        override fun nightSubPhases() = phases.toList()
    }

    // ── startNightPhase ────────────────────────────────────────────────────────────

    @Test
    fun `startNightPhase - creates NightPhase with correct dayNumber and prevGuardTarget`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        runBlocking {
            nightOrchestrator.startNightPhase(gameId, newDayNumber = 2, previousGuardTarget = "u1").join()
        }

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.dayNumber).isEqualTo(2)
        assertThat(captor.firstValue.prevGuardTargetUserId).isEqualTo("u1")
    }

    @Test
    fun `startNightPhase - sets game phase to NIGHT and saves`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        runBlocking {
            nightOrchestrator.startNightPhase(gameId, newDayNumber = 2).join()
        }

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.NIGHT)
        assertThat(captor.firstValue.dayNumber).isEqualTo(2)
    }

    @Test
    fun `startNightPhase - broadcasts PhaseChanged with NIGHT`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        runBlocking {
            nightOrchestrator.startNightPhase(gameId, newDayNumber = 1).join()
        }

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues.any { it is DomainEvent.PhaseChanged }).isTrue()
        val phaseChangedEvent = captor.allValues.first { it is DomainEvent.PhaseChanged } as DomainEvent.PhaseChanged
        assertThat(phaseChangedEvent.phase).isEqualTo(GamePhase.NIGHT)
    }

    @Test
    fun `startNightPhase without withWaiting - first sub-phase is WEREWOLF_PICK (wolf always present)`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        runBlocking {
            orchestrator.startNightPhase(gameId, newDayNumber = 1, withWaiting = false).join()
        }

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK)
    }

    @Test
    fun `startNightPhase with withWaiting=true - sub-phase starts as WAITING`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WAITING)

        nightOrchestrator.startNightPhase(gameId, newDayNumber = 1, withWaiting = true)
        Thread.sleep(500) // Allow coroutine to complete initNightInternal

        val captor = argumentCaptor<NightPhase>()
        verify(nightPhaseRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(NightSubPhase.WAITING)
    }

    // ── resolveNightKills ────────────────────────────────────────────────────

    @Test
    fun `resolveNightKills - wolf kills target when no guard, no antidote`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2")
        val r = room()
        val initialCtx = ctx(wolf, victim, roomOverride = r)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(victim))
        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim.also { it.alive = false }, roomOverride = r))
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(victim.alive).isFalse()
        verify(gamePlayerRepository).save(victim)
    }

    @Test
    fun `resolveNightKills - guard protects wolf target, victim survives`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2", guardTarget = "u2")
        val r = room()
        val initialCtx = ctx(wolf, victim, roomOverride = r)

        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim, roomOverride = r))
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(victim.alive).isTrue()
        verify(gamePlayerRepository, never()).save(victim)
    }

    @Test
    fun `resolveNightKills - witch antidote saves wolf target`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2", antidoteUsed = true)
        val r = room()
        val initialCtx = ctx(wolf, victim, roomOverride = r)

        whenever(contextLoader.load(gameId)).thenReturn(ctx(wolf, victim, roomOverride = r))
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

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
        val r = room()
        val initialCtx = ctx(wolf, wolfVictim, poisonVictim, roomOverride = r)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(wolfVictim))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u3")).thenReturn(Optional.of(poisonVictim))
        whenever(contextLoader.load(gameId)).thenReturn(
            ctx(wolf, wolfVictim.also { it.alive = false }, poisonVictim.also { it.alive = false }, roomOverride = r)
        )
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        assertThat(wolfVictim.alive).isFalse()
        assertThat(poisonVictim.alive).isFalse()
    }

    @Test
    fun `resolveNightKills - no kills, transitions to DAY_DISCUSSION phase`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val v1 = player("u2", 2)
        val v2 = player("u3", 3)
        val np = nightPhase() // no targets
        val r = room()
        val initialCtx = ctx(wolf, v1, v2, roomOverride = r)

        whenever(contextLoader.load(gameId)).thenReturn(initialCtx)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(captor.firstValue.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `resolveNightKills - werewolf wins when wolves outnumber others after kill`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val victim = player("u2", 2)
        val np = nightPhase(wolfTarget = "u2")
        val r = room()
        val initialCtx = ctx(wolf, victim, roomOverride = r)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(victim))
        val afterKillCtx = ctx(wolf, victim.also { it.alive = false }, roomOverride = r)
        whenever(contextLoader.load(gameId)).thenReturn(afterKillCtx)
        whenever(winConditionChecker.check(eq(listOf(wolf)), any(), any(), any())).thenReturn(WinnerSide.WEREWOLF)

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
        val r = room()
        val initialCtx = GameContext(game(), r, listOf(villager, deadWolf))

        val afterCtx = GameContext(game(), r, listOf(villager, deadWolf))
        whenever(contextLoader.load(gameId)).thenReturn(afterCtx)
        whenever(winConditionChecker.check(eq(listOf(villager)), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(captor.firstValue.winner).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── advanceFromWaiting ───────────────────────────────────────────────────

    @Test
    fun `advanceFromWaiting - transitions from WAITING to first sub-phase`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WAITING
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val ctx = GameContext(game(), room(), listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightSubPhaseTransition(NightSubPhase.WAITING, NightSubPhase.WEREWOLF_PICK)

        orchestrator.advanceFromWaiting(gameId)

        assertThat(np.subPhase).isEqualTo(NightSubPhase.WEREWOLF_PICK)
        verify(nightPhaseRepository).save(np)
        verify(stompPublisher).broadcastGame(eq(gameId), any<DomainEvent.NightSubPhaseChanged>())
    }

    @Test
    fun `advanceFromWaiting - idempotent when already past WAITING`() {
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK // already advanced
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val ctx = GameContext(game(), room(), listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        nightOrchestrator.advanceFromWaiting(gameId)

        verify(nightPhaseRepository, never()).save(any())
        verify(stompPublisher, never()).broadcastGame(any(), any())
    }

    @Test
    fun `advanceFromWaiting - does nothing when no night phase exists`() {
        val ctx = GameContext(game(), room(), emptyList(), nightPhase = null)
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        nightOrchestrator.advanceFromWaiting(gameId)

        verify(nightPhaseRepository, never()).save(any())
    }

    // ── advanceToSubPhase ────────────────────────────────────────────────────

    @Test
    fun `advanceToSubPhase - updates sub-phase and broadcasts`() {
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val ctx = GameContext(game(), room(), listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightSubPhaseTransition(NightSubPhase.WEREWOLF_PICK, NightSubPhase.SEER_PICK)

        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.SEER_PICK)

        assertThat(np.subPhase).isEqualTo(NightSubPhase.SEER_PICK)
        verify(nightPhaseRepository).save(np)
        verify(stompPublisher).broadcastGame(eq(gameId), any<DomainEvent.NightSubPhaseChanged>())
    }

    @Test
    fun `advanceToSubPhase - does nothing when targetSubPhase is null`() {
        nightOrchestrator.advanceToSubPhase(gameId, null)

        verify(contextLoader, never()).load(any())
        verify(nightPhaseRepository, never()).save(any())
    }

    @Test
    fun `advanceToSubPhase - does nothing when no night phase exists`() {
        val ctx = GameContext(game(), room(), emptyList(), nightPhase = null)
        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.SEER_PICK)

        verify(nightPhaseRepository, never()).save(any())
    }

    // ── nightSequence / firstSubPhase ────────────────────────────────────────

    @Test
    fun `nightSequence - wolves-only room returns only WEREWOLF_PICK`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val ctx = GameContext(game(), room(), emptyList())
        val sequence = orchestrator.nightSequence(ctx)

        assertThat(sequence).containsExactly(NightSubPhase.WEREWOLF_PICK)
    }

    @Test
    fun `nightSequence - full role room returns correct ordering`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, witchHandler, guardHandler))

        val fullRoom = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 12,
            hasSeer = true, hasWitch = true, hasGuard = true)
        val ctx = GameContext(game(), fullRoom, emptyList())
        val sequence = orchestrator.nightSequence(ctx)

        assertThat(sequence).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.SEER_RESULT,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    @Test
    fun `firstSubPhase - returns WEREWOLF_PICK by default`() {
        val ctx = GameContext(game(), room(), emptyList())
        val result = nightOrchestrator.firstSubPhase(ctx)
        assertThat(result).isEqualTo(NightSubPhase.WEREWOLF_PICK)
    }

    // ── Audio Sequence Tests ─────────────────────────────────────────────────

    @Test
    fun `startNightPhase - broadcasts AudioSequence with goes_dark_close_eyes mp3`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        runBlocking {
            nightOrchestrator.startNightPhase(gameId, newDayNumber = 1).join()
        }

        // Verify AudioSequence event was broadcast
        val audioEventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), audioEventCaptor.capture())

        val events = audioEventCaptor.allValues
        val audioEvent = events.find { it is DomainEvent.AudioSequence }
        assertThat(audioEvent).isNotNull()
        assertThat((audioEvent as DomainEvent.AudioSequence).audioSequence.audioFiles)
            .containsExactly("goes_dark_close_eyes.mp3")
    }

    @Test
    fun `startNightPhase with WAITING - broadcasts AudioSequence with empty audioFiles`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WAITING)

        runBlocking {
            nightOrchestrator.startNightPhase(gameId, newDayNumber = 1, withWaiting = true).join()
        }

        // Verify AudioSequence event was broadcast
        val audioEventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), audioEventCaptor.capture())

        val events = audioEventCaptor.allValues
        val audioEvent = events.find { it is DomainEvent.AudioSequence }
        assertThat(audioEvent).isNotNull()
        assertThat((audioEvent as DomainEvent.AudioSequence).audioSequence.audioFiles).isEmpty()
    }

    @Test
    fun `resolveNightKills - broadcasts AudioSequence with day_time mp3 when transitioning to DAY`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val v1 = player("u2", 2)
        val v2 = player("u3", 3)
        val np = nightPhase()
        val r = room()
        val initialCtx = ctx(wolf, v1, v2, roomOverride = r)

        whenever(contextLoader.load(gameId)).thenReturn(initialCtx)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        nightOrchestrator.resolveNightKills(initialCtx, np)

        // Verify AudioSequence event was broadcast
        val audioEventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), audioEventCaptor.capture())

        val events = audioEventCaptor.allValues
        val audioEvent = events.find { it is DomainEvent.AudioSequence }
        assertThat(audioEvent).isNotNull()
        assertThat((audioEvent as DomainEvent.AudioSequence).audioSequence.audioFiles)
            .containsExactly("day_time.mp3")
    }

    @Test
    fun `advanceFromWaiting - broadcasts AudioSequence with wolf_open_eyes mp3`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WAITING
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val ctx = GameContext(game(), r, listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        // Setup correct audio files for WAITING to WEREWOLF_PICK transition
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-NIGHT-TRANSITION",
            phase = GamePhase.NIGHT,
            subPhase = NightSubPhase.WEREWOLF_PICK.name,
            audioFiles = listOf("wolf_open_eyes.mp3"), // Only open eyes audio
            priority = 5,
            timestamp = System.currentTimeMillis()
        )
        whenever(audioService.calculateNightSubPhaseTransition(
            eq(gameId),
            eq(NightSubPhase.WAITING),
            eq(NightSubPhase.WEREWOLF_PICK)
        )).thenReturn(audioSequence)

        orchestrator.advanceFromWaiting(gameId)

        // Verify AudioSequence event was broadcast
        val audioEventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), audioEventCaptor.capture())

        val events = audioEventCaptor.allValues
        val audioEvent = events.find { it is DomainEvent.AudioSequence }
        assertThat(audioEvent).isNotNull()
        assertThat((audioEvent as DomainEvent.AudioSequence).audioSequence.audioFiles)
            .containsExactly("wolf_open_eyes.mp3")
    }

    @Test
    fun `advanceToSubPhase - broadcasts AudioSequence when advancing to WITCH_ACT`() {
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.SEER_RESULT
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room(hasSeer = true, hasWitch = true)
        val ctx = GameContext(game(), r, listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        // Setup correct audio files for SEER_RESULT to WITCH_ACT transition
        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-NIGHT-TRANSITION",
            phase = GamePhase.NIGHT,
            subPhase = NightSubPhase.WITCH_ACT.name,
            audioFiles = listOf("seer_close_eyes.mp3", "witch_open_eyes.mp3"),
            priority = 5,
            timestamp = System.currentTimeMillis()
        )
        whenever(audioService.calculateNightSubPhaseTransition(
            eq(gameId),
            eq(NightSubPhase.SEER_RESULT),
            eq(NightSubPhase.WITCH_ACT)
        )).thenReturn(audioSequence)

        nightOrchestrator.advanceToSubPhase(gameId, NightSubPhase.WITCH_ACT)

        // Verify AudioSequence event was broadcast
        val audioEventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), audioEventCaptor.capture())

        val events = audioEventCaptor.allValues
        val audioEvent = events.find { it is DomainEvent.AudioSequence }
        assertThat(audioEvent).isNotNull()
        assertThat((audioEvent as DomainEvent.AudioSequence).audioSequence.audioFiles)
            .containsExactly("seer_close_eyes.mp3", "witch_open_eyes.mp3")
    }

    // ── Action log recording ─────────────────────────────────────────────────

    @Test
    fun `resolveNightKills - records NIGHT_DEATH events for each killed player`() {
        val wolf = GamePlayer(gameId = gameId, userId = "wolf1", seatIndex = 1, role = PlayerRole.WEREWOLF)
        val villager = GamePlayer(gameId = gameId, userId = "vil1", seatIndex = 2, role = PlayerRole.VILLAGER)
        val game = Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = GamePhase.NIGHT
            it.dayNumber = 1
        }
        val room = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)
        // Use GUARD_PICK (not COMPLETE) — resolveNightKills is called on an in-progress phase
        val nightPhase = NightPhase(gameId = gameId, dayNumber = 1, subPhase = NightSubPhase.GUARD_PICK).also {
            it.wolfTargetUserId = "vil1"
        }
        val ctx = GameContext(game, room, listOf(wolf, villager), nightPhase)
        val updatedCtx = GameContext(game, room, listOf(wolf, villager.also { it.alive = false }), nightPhase)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "vil1"))
            .thenReturn(Optional.of(villager))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(contextLoader.load(gameId)).thenReturn(updatedCtx)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForDayTransition(room)

        nightOrchestrator.resolveNightKills(ctx, nightPhase)

        verify(actionLogService).recordNightDeaths(gameId, 1, listOf("vil1"))
    }

    @Test
    fun `resolveNightKills - is a no-op if night phase is already COMPLETE (prevents third-round automatic bug)`() {
        // Regression test: the coroutine path used to call resolveNightKills AFTER the event-driven
        // path had already called it (timeout-based auto-advance). The COMPLETE guard prevents
        // double-resolution which caused automatic day transitions on round 3+.
        val np = nightPhase().also { it.subPhase = NightSubPhase.COMPLETE }
        val ctx = ctx(player("u1", 1, PlayerRole.WEREWOLF))

        nightOrchestrator.resolveNightKills(ctx, np)

        // Nothing should happen: no kills applied, no game state saved, no events broadcast
        verify(gameRepository, never()).save(any())
        verify(gamePlayerRepository, never()).save(any())
        verify(stompPublisher, never()).broadcastGame(any(), any())
    }

    @Test
    fun `initNight - does NOT broadcast OpenEyes or CloseEyes events`() {
        // Regression test: the coroutine path (startNightPhase) used to broadcast OpenEyes/CloseEyes
        // domain events which the frontend mapped to audio, duplicating the AudioSequence events.
        // initNight must never broadcast these events.
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room().also { it.config = GameConfig.createDefault() }
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        nightOrchestrator.initNight(gameId, newDayNumber = 1)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).noneMatch { it is DomainEvent.OpenEyes }
        assertThat(captor.allValues).noneMatch { it is DomainEvent.CloseEyes }
    }

    // ── nightRoleLoop coroutine tests ────────────────────────────────────────

    /**
     * Verifies that a dead seer triggers exactly one seer_open_eyes and one seer_close_eyes broadcast,
     * regardless of how many clients are connected or how many times getGameState is called.
     * Before the coroutine-first refactor this test would fail because advance() was called N times
     * (once per client) for each NightSubPhaseChanged event received.
     */
    @Test
    fun `nightRoleLoop - dead seer plays seer_open_eyes once and seer_close_eyes once`() {
        val shortConfig = RoleDelayConfig(
            actionWindowMs = 100L,
            deadRoleDelayMs = 100L,
            audioWarmupMs = 30L,
            audioCooldownMs = 30L,
            interRoleGapMs = 30L,
        )
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, guardHandler))

        val r = room(hasSeer = true, hasGuard = true).also {
            it.config = GameConfig(mapOf(
                PlayerRole.WEREWOLF to shortConfig,
                PlayerRole.SEER to shortConfig,
                PlayerRole.GUARD to shortConfig,
            ))
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val deadSeer = player("u2", 2, PlayerRole.SEER, alive = false)
        val guard = player("u3", 3, PlayerRole.GUARD)
        val np = NightPhase(gameId = gameId, dayNumber = 1)
        val context = GameContext(game(), r, listOf(wolf, deadSeer, guard))

        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)
        mockAudioServiceForDayTransition(r)

        runBlocking {
            val job = orchestrator.startNightPhase(gameId, newDayNumber = 1)
            launch { while (job.isActive) { delay(50); orchestrator.submitAction(gameId) } }
            job.join()
        }

        // Collect all AudioSequence events broadcast to the game channel
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val audioEvents = captor.allValues.filterIsInstance<DomainEvent.AudioSequence>()

        // seer_open_eyes and seer_close_eyes must each appear exactly once
        val seerOpenCount = audioEvents.count { "seer_open_eyes.mp3" in it.audioSequence.audioFiles }
        val seerCloseCount = audioEvents.count { "seer_close_eyes.mp3" in it.audioSequence.audioFiles }
        assertThat(seerOpenCount).isEqualTo(1)
        assertThat(seerCloseCount).isEqualTo(1)
    }

    /**
     * Verifies that calling submitAction twice for the same gameId does not throw and does not
     * produce a second NightSubPhaseChanged broadcast (the second call is a no-op).
     */
    @Test
    fun `submitAction - second call on same gameId after first already completed is a no-op`() {
        // Call submitAction twice — first completes nothing (no pending deferred yet), second the same.
        // Neither call should throw an exception.
        nightOrchestrator.submitAction(gameId)
        nightOrchestrator.submitAction(gameId)

        // No sub-phase broadcasts should have happened (no coroutine was running)
        verify(stompPublisher, never()).broadcastGame(any(), any<DomainEvent.NightSubPhaseChanged>())
    }

    /**
     * Regression test for the stale JPA entity bug (Game 5, 2026-04-17).
     *
     * The seer has two sub-phases: SEER_PICK and SEER_RESULT. When the seer picks a target,
     * the SeerHandler saves seerCheckedUserId to the NightPhase record. Then the coroutine
     * advances to SEER_RESULT and saves the subPhase change. If the coroutine reuses a stale
     * entity loaded before the handler's save, the seerCheckedUserId is overwritten to null.
     *
     * This test simulates the handler's DB modification between sub-phases by making
     * findByGameIdAndDayNumber return a mutated entity (with seerCheckedUserId set) on
     * the second call. If the coroutine reloads from DB before each sub-phase save,
     * seerCheckedUserId is preserved. If it reuses a stale entity, it's overwritten.
     */
    @Test
    fun `nightRoleLoop - seer handler data preserved across SEER_PICK to SEER_RESULT transition`() {
        val shortConfig = RoleDelayConfig(
            actionWindowMs = 200L,
            deadRoleDelayMs = 100L,
            audioWarmupMs = 30L,
            audioCooldownMs = 30L,
            interRoleGapMs = 30L,
        )
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler))

        val r = room(hasSeer = true).also {
            it.config = GameConfig(mapOf(
                PlayerRole.WEREWOLF to shortConfig,
                PlayerRole.SEER to shortConfig,
            ))
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val seer = player("u2", 2, PlayerRole.SEER)
        val context = GameContext(game(), r, listOf(wolf, seer))

        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)
        mockAudioServiceForDayTransition(r)

        // Track NightPhase saves to simulate handler mutation between sub-phases.
        // The real NightPhase entity returned by findByGameIdAndDayNumber is shared across calls,
        // so mutating it simulates the handler's DB update being visible on reload.
        val sharedNightPhase = NightPhase(gameId = gameId, dayNumber = 1)
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1))
            .thenReturn(Optional.of(sharedNightPhase))

        // Track what gets saved: capture seerCheckedUserId from each save
        val savedSeerCheckedUserIds = mutableListOf<String?>()
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { invocation ->
            val saved = invocation.arguments[0] as NightPhase
            savedSeerCheckedUserIds.add(saved.seerCheckedUserId)

            // Simulate: after SEER_PICK is saved, the handler sets seerCheckedUserId.
            // Since we return a shared entity, the next findByGameIdAndDayNumber will see this.
            if (saved.subPhase == NightSubPhase.SEER_PICK) {
                sharedNightPhase.seerCheckedUserId = "target-user"
                sharedNightPhase.seerResultIsWerewolf = false
            }
            saved
        }

        runBlocking {
            val job = orchestrator.startNightPhase(gameId, newDayNumber = 1)
            launch { while (job.isActive) { delay(50); orchestrator.submitAction(gameId) } }
            job.join()
        }

        // The critical assertion: the shared NightPhase should still have seerCheckedUserId
        // after the coroutine completed. If the stale entity bug existed, the coroutine's save
        // of SEER_RESULT would have overwritten seerCheckedUserId to null.
        assertThat(sharedNightPhase.seerCheckedUserId)
            .describedAs("seerCheckedUserId must be preserved after SEER_RESULT sub-phase save (stale entity regression)")
            .isEqualTo("target-user")
        assertThat(sharedNightPhase.seerResultIsWerewolf)
            .describedAs("seerResultIsWerewolf must be preserved after SEER_RESULT sub-phase save")
            .isEqualTo(false)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Night flow validation — verify phase transitions + audio for 5 scenarios
    // ══════════════════════════════════════════════════════════════════════════

    private val allHandlers = listOf(
        stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK),
        stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT),
        stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT),
        stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK),
    )

    /**
     * Run a full night with the given players and collect all broadcast events in order.
     * A background coroutine polls submitAction() every 50ms to unblock alive roles
     * (since alive roles now wait indefinitely — no timeout).
     */
    private fun runNightAndCollect(players: List<GamePlayer>): List<DomainEvent> {
        val shortCfg = RoleDelayConfig(50L, 50L, 10L, 10L, 10L)
        val r = Room(
            roomCode = "ABCD", hostUserId = hostId, totalPlayers = 12,
            hasSeer = true, hasWitch = true, hasGuard = true,
        ).also {
            it.config = GameConfig(mapOf(
                PlayerRole.WEREWOLF to shortCfg, PlayerRole.SEER to shortCfg,
                PlayerRole.WITCH to shortCfg, PlayerRole.GUARD to shortCfg,
            ))
        }
        val orchestrator = makeOrchestrator(allHandlers)
        val context = GameContext(game(), r, players)
        val np = NightPhase(gameId = gameId, dayNumber = 1)

        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        whenever(audioService.calculatePhaseTransition(
            eq(gameId), anyOrNull(), eq(GamePhase.NIGHT), anyOrNull(), anyOrNull(), eq(r),
        )).thenReturn(AudioSequence(
            id = "night", phase = GamePhase.NIGHT, subPhase = NightSubPhase.WEREWOLF_PICK.name,
            audioFiles = listOf("goes_dark_close_eyes.mp3", "wolf_howl.mp3"),
            priority = 10, timestamp = System.currentTimeMillis(),
        ))
        whenever(audioService.calculatePhaseTransition(
            eq(gameId), anyOrNull(), eq(GamePhase.DAY_DISCUSSION), anyOrNull(), anyOrNull(), eq(r),
        )).thenReturn(AudioSequence(
            id = "day", phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_HIDDEN.name,
            audioFiles = listOf("rooster_crowing.mp3", "day_time.mp3"),
            priority = 10, timestamp = System.currentTimeMillis(),
        ))

        runBlocking {
            val job = orchestrator.startNightPhase(gameId, newDayNumber = 1)
            // Poll submitAction to unblock alive roles (no timeout means they wait forever)
            launch {
                while (job.isActive) {
                    delay(50)
                    orchestrator.submitAction(gameId)
                }
            }
            job.join()
        }

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        return captor.allValues
    }

    /** Flatten all AudioSequence events into an ordered list of file names. */
    private fun audioFiles(events: List<DomainEvent>): List<String> =
        events.filterIsInstance<DomainEvent.AudioSequence>().flatMap { it.audioSequence.audioFiles }

    /** Extract ordered NightSubPhaseChanged values. */
    private fun subPhases(events: List<DomainEvent>): List<NightSubPhase> =
        events.filterIsInstance<DomainEvent.NightSubPhaseChanged>().map { it.subPhase }

    // ── Scenario 1: every role alive ─────────────────────────────────────────

    @Test
    fun `night flow - all roles alive - correct audio and phases`() {
        val events = runNightAndCollect(listOf(
            player("w1", 1, PlayerRole.WEREWOLF),
            player("s1", 2, PlayerRole.SEER),
            player("x1", 3, PlayerRole.WITCH),
            player("g1", 4, PlayerRole.GUARD),
            player("v1", 5), player("v2", 6),
        ))

        // Audio: each role plays open + close exactly once
        assertThat(audioFiles(events)).containsExactly(
            "goes_dark_close_eyes.mp3", "wolf_howl.mp3",       // night init
            "wolf_open_eyes.mp3",                                // wolf open
            "wolf_close_eyes.mp3",                               // wolf close
            "seer_open_eyes.mp3",                                // seer open
            "seer_close_eyes.mp3",                               // seer close
            "witch_open_eyes.mp3",                               // witch open
            "witch_close_eyes.mp3",                              // witch close
            "guard_open_eyes.mp3",                               // guard open
            "guard_close_eyes.mp3",                              // guard close
            "rooster_crowing.mp3", "day_time.mp3",              // day transition
        )

        // Phases: alive seer gets both SEER_PICK and SEER_RESULT
        assertThat(subPhases(events)).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.SEER_RESULT,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    // ── Scenario 2: only seer dead ───────────────────────────────────────────

    @Test
    fun `night flow - seer dead - correct audio and phases`() {
        val events = runNightAndCollect(listOf(
            player("w1", 1, PlayerRole.WEREWOLF),
            player("s1", 2, PlayerRole.SEER, alive = false),
            player("x1", 3, PlayerRole.WITCH),
            player("g1", 4, PlayerRole.GUARD),
            player("v1", 5), player("v2", 6),
        ))

        // Audio: dead seer still plays open + close exactly once
        assertThat(audioFiles(events)).containsExactly(
            "goes_dark_close_eyes.mp3", "wolf_howl.mp3",
            "wolf_open_eyes.mp3", "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3", "seer_close_eyes.mp3",        // dead seer: still plays
            "witch_open_eyes.mp3", "witch_close_eyes.mp3",
            "guard_open_eyes.mp3", "guard_close_eyes.mp3",
            "rooster_crowing.mp3", "day_time.mp3",
        )

        // Phases: dead seer only broadcasts SEER_PICK (first sub-phase), NOT SEER_RESULT
        assertThat(subPhases(events)).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,                             // dead: first sub-phase only
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    // ── Scenario 3: seer + witch dead ────────────────────────────────────────

    @Test
    fun `night flow - seer and witch dead - correct audio and phases`() {
        val events = runNightAndCollect(listOf(
            player("w1", 1, PlayerRole.WEREWOLF),
            player("s1", 2, PlayerRole.SEER, alive = false),
            player("x1", 3, PlayerRole.WITCH, alive = false),
            player("g1", 4, PlayerRole.GUARD),
            player("v1", 5), player("v2", 6),
        ))

        assertThat(audioFiles(events)).containsExactly(
            "goes_dark_close_eyes.mp3", "wolf_howl.mp3",
            "wolf_open_eyes.mp3", "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3", "seer_close_eyes.mp3",
            "witch_open_eyes.mp3", "witch_close_eyes.mp3",      // dead witch: still plays
            "guard_open_eyes.mp3", "guard_close_eyes.mp3",
            "rooster_crowing.mp3", "day_time.mp3",
        )

        assertThat(subPhases(events)).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    // ── Scenario 4: seer + witch + guard dead ────────────────────────────────

    @Test
    fun `night flow - seer witch guard dead - correct audio and phases`() {
        val events = runNightAndCollect(listOf(
            player("w1", 1, PlayerRole.WEREWOLF),
            player("s1", 2, PlayerRole.SEER, alive = false),
            player("x1", 3, PlayerRole.WITCH, alive = false),
            player("g1", 4, PlayerRole.GUARD, alive = false),
            player("v1", 5), player("v2", 6),
        ))

        assertThat(audioFiles(events)).containsExactly(
            "goes_dark_close_eyes.mp3", "wolf_howl.mp3",
            "wolf_open_eyes.mp3", "wolf_close_eyes.mp3",
            "seer_open_eyes.mp3", "seer_close_eyes.mp3",
            "witch_open_eyes.mp3", "witch_close_eyes.mp3",
            "guard_open_eyes.mp3", "guard_close_eyes.mp3",      // dead guard: still plays
            "rooster_crowing.mp3", "day_time.mp3",
        )

        assertThat(subPhases(events)).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    // ── Scenario 5: only wolf + 2 villagers (all special roles dead) ─────────

    @Test
    fun `night flow - only wolf and villagers alive - correct audio and phases`() {
        val events = runNightAndCollect(listOf(
            player("w1", 1, PlayerRole.WEREWOLF),
            player("w2", 2, PlayerRole.WEREWOLF),
            player("s1", 3, PlayerRole.SEER, alive = false),
            player("x1", 4, PlayerRole.WITCH, alive = false),
            player("g1", 5, PlayerRole.GUARD, alive = false),
            player("v1", 6), player("v2", 7),
        ))

        // All special roles dead — still play full audio sequence
        assertThat(audioFiles(events)).containsExactly(
            "goes_dark_close_eyes.mp3", "wolf_howl.mp3",
            "wolf_open_eyes.mp3", "wolf_close_eyes.mp3",        // wolf alive
            "seer_open_eyes.mp3", "seer_close_eyes.mp3",        // seer dead
            "witch_open_eyes.mp3", "witch_close_eyes.mp3",      // witch dead
            "guard_open_eyes.mp3", "guard_close_eyes.mp3",      // guard dead
            "rooster_crowing.mp3", "day_time.mp3",
        )

        // All dead special roles broadcast only their first sub-phase
        assertThat(subPhases(events)).containsExactly(
            NightSubPhase.WEREWOLF_PICK,
            NightSubPhase.SEER_PICK,
            NightSubPhase.WITCH_ACT,
            NightSubPhase.GUARD_PICK,
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Regression tests — guard against the two P0 bugs
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * REGRESSION: Alive role must NEVER auto-advance.
     *
     * Before the fix, withTimeoutOrNull(actionWindowMs) would auto-advance after 20-30s.
     * Now alive roles call deferred.await() with no timeout — they block until
     * submitAction(gameId) is called.
     *
     * This test starts a night with one alive wolf, waits 2 seconds (which would be
     * well past any short timeout), and asserts the coroutine is STILL waiting.
     * Only after submitAction() does the night complete.
     */
    @Test
    fun `REGRESSION - alive role blocks forever until submitAction is called`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))
        val shortCfg = RoleDelayConfig(50L, 50L, 10L, 10L, 10L)
        val r = room().also { it.config = GameConfig(mapOf(PlayerRole.WEREWOLF to shortCfg)) }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), r, listOf(wolf))
        val np = NightPhase(gameId = gameId, dayNumber = 1)

        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)
        mockAudioServiceForDayTransition(r)

        val job = orchestrator.startNightPhase(gameId, newDayNumber = 1)

        // Wait 2 seconds — the coroutine must still be blocked at deferred.await()
        Thread.sleep(2_000)
        assertThat(job.isActive)
            .describedAs("Alive wolf must NOT auto-advance — coroutine should still be waiting for submitAction")
            .isTrue()

        // Now unblock it
        orchestrator.submitAction(gameId)
        runBlocking { job.join() }
        assertThat(job.isCompleted).isTrue()
    }

    /**
     * REGRESSION: goes_dark_close_eyes.mp3 must play before wolf_open_eyes.mp3.
     *
     * Before the fix, launchRoleLoop started the coroutine immediately after
     * broadcastNightInit. The coroutine's wolf_open_eyes AudioSequence arrived 69ms
     * later and replaced the init AudioSequence on the frontend — goes_dark never played.
     *
     * The fix adds NIGHT_INIT_AUDIO_DELAY_MS (4s) in launchRoleLoop before nightRoleLoop.
     * This test uses the production path (initNight → launchRoleLoop) and verifies that
     * 500ms after initNight, only init audio exists — no role audio yet.
     */
    @Test
    fun `REGRESSION - init audio plays before role audio in production path`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))
        val r = room().also { it.config = GameConfig.createDefault() }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val context = GameContext(game(), r, listOf(wolf))

        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1))
            .thenReturn(Optional.of(NightPhase(gameId = gameId, dayNumber = 1)))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(audioService.calculatePhaseTransition(
            eq(gameId), anyOrNull(), eq(GamePhase.NIGHT), anyOrNull(), anyOrNull(), eq(r),
        )).thenReturn(AudioSequence(
            id = "night-init", phase = GamePhase.NIGHT, subPhase = NightSubPhase.WEREWOLF_PICK.name,
            audioFiles = listOf("goes_dark_close_eyes.mp3", "wolf_howl.mp3"),
            priority = 10, timestamp = System.currentTimeMillis(),
        ))

        // Use initNight (production path). No active transaction → else branch →
        // broadcastNightInit runs synchronously, then launchRoleLoop starts coroutine
        // with NIGHT_INIT_AUDIO_DELAY_MS delay before nightRoleLoop.
        orchestrator.initNight(gameId, newDayNumber = 1)

        // After 500ms: init audio should be broadcast, role audio should NOT.
        // The 4-second delay in launchRoleLoop prevents role audio from starting.
        Thread.sleep(500)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val allAudio = captor.allValues.filterIsInstance<DomainEvent.AudioSequence>()
            .flatMap { it.audioSequence.audioFiles }

        assertThat(allAudio)
            .describedAs("Only init audio (goes_dark + wolf_howl) should exist — role audio must not start yet")
            .containsExactly("goes_dark_close_eyes.mp3", "wolf_howl.mp3")
        assertThat(allAudio)
            .describedAs("wolf_open_eyes must NOT be broadcast yet (4s delay in launchRoleLoop)")
            .doesNotContain("wolf_open_eyes.mp3")

        // Cleanup: cancel the night coroutine
        orchestrator.cancelNightPhase(gameId)
    }
}
