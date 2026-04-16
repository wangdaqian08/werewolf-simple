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
import kotlinx.coroutines.Job
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
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var nightWaitingScheduler: NightWaitingScheduler
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
        winConditionChecker = winConditionChecker,
        stompPublisher = stompPublisher,
        contextLoader = contextLoader,
        nightWaitingScheduler = nightWaitingScheduler,
        audioService = audioService,
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        actionLogService = actionLogService,
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

    private fun mockAudioServiceForDeadRoleTransition(skippedRoles: List<NightSubPhase>, targetSubPhase: NightSubPhase) {
        val audioFiles = mutableListOf<String>()
        
        // Add complete audio sequence for skipped (dead) roles
        for (skippedRole in skippedRoles) {
            if (skippedRole != NightSubPhase.WAITING) {
                val openEyesAudio = when (skippedRole) {
                    NightSubPhase.WEREWOLF_PICK -> "wolf_open_eyes.mp3"
                    NightSubPhase.SEER_PICK -> "seer_open_eyes.mp3"
                    NightSubPhase.SEER_RESULT -> null // SEER_RESULT doesn't have open eyes audio
                    NightSubPhase.WITCH_ACT -> "witch_open_eyes.mp3"
                    NightSubPhase.GUARD_PICK -> "guard_open_eyes.mp3"
                    else -> null
                }
                val closeEyesAudio = when (skippedRole) {
                    NightSubPhase.WEREWOLF_PICK -> "wolf_close_eyes.mp3"
                    NightSubPhase.SEER_PICK -> null // SEER_PICK does NOT play close eyes - happens at SEER_RESULT
                    NightSubPhase.SEER_RESULT -> "seer_close_eyes.mp3"
                    NightSubPhase.WITCH_ACT -> "witch_close_eyes.mp3"
                    NightSubPhase.GUARD_PICK -> "guard_close_eyes.mp3"
                    else -> null
                }
                if (openEyesAudio != null) audioFiles.add(openEyesAudio)
                if (closeEyesAudio != null) audioFiles.add(closeEyesAudio)
            }
        }

        // Add "open eyes" audio for the target (alive) role
        if (targetSubPhase != NightSubPhase.WAITING && targetSubPhase != NightSubPhase.COMPLETE) {
            val openEyesAudio = when (targetSubPhase) {
                NightSubPhase.WEREWOLF_PICK -> "wolf_open_eyes.mp3"
                NightSubPhase.SEER_PICK -> "seer_open_eyes.mp3"
                NightSubPhase.SEER_RESULT -> null // SEER_RESULT doesn't have open eyes audio
                NightSubPhase.WITCH_ACT -> "witch_open_eyes.mp3"
                NightSubPhase.GUARD_PICK -> "guard_open_eyes.mp3"
                else -> null
            }
            if (openEyesAudio != null) audioFiles.add(openEyesAudio)
        }

        val audioSequence = AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-DEAD-ROLE-$targetSubPhase",
            phase = GamePhase.NIGHT,
            subPhase = targetSubPhase.name,
            audioFiles = audioFiles,
            priority = 3,
            timestamp = System.currentTimeMillis()
        )
        
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            eq(skippedRoles),
            eq(targetSubPhase)
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

    /** Minimal stub handler for advance() sequence testing. */
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

    @Test
    fun `startNightPhase with withWaiting=true - schedules automatic advance via scheduler`() {
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

        verify(nightWaitingScheduler).scheduleAdvance(gameId)
    }

    @Test
    fun `startNightPhase with withWaiting=false - does NOT schedule waiting advance`() {
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val r = room()
        val context = GameContext(game(), r, listOf(wolf))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightTransition(r, NightSubPhase.WEREWOLF_PICK)

        nightOrchestrator.startNightPhase(gameId, newDayNumber = 1, withWaiting = false)

        verify(nightWaitingScheduler, never()).scheduleAdvance(gameId)
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
        val r = room()
        val initialCtx = GameContext(game(), r, listOf(villager, deadWolf))

        val afterCtx = GameContext(game(), r, listOf(villager, deadWolf))
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
        // Two-phase sequence: WEREWOLF_PICK → SEER_PICK → SEER_RESULT
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.SEER_PICK
        }
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = true)
        val seer = player("u1", 1, PlayerRole.SEER)
        val ctx = GameContext(game(), r, listOf(seer), nightPhase = np)
        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        mockAudioServiceForNightSubPhaseTransition(NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)

        orchestrator.advance(gameId, NightSubPhase.SEER_PICK)

        assertThat(np.subPhase).isEqualTo(NightSubPhase.SEER_RESULT)
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
        val r = room()
        val ctx = GameContext(game(), r, emptyList(), nightPhase = np)
        // advance() calls contextLoader.load once; resolveNightKills calls it again
        whenever(contextLoader.load(gameId)).thenReturn(ctx, ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Should schedule last special role close eyes audio and advance to day
        verify(nightWaitingScheduler).scheduleLastSpecialRoleCloseEyesAndAdvanceToDay(
            eq(gameId),
            eq(1), // dayNumber
            eq("wolf_close_eyes.mp3")
        )
    }

    // ── advance - skip sub-phases when all role players are dead ─────────────

    @Test
    fun `advance - enters WITCH_ACT when all witches are dead, plays dead role audio`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, witchHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.SEER_RESULT
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val deadWitch = player("u2", 2, PlayerRole.WITCH, alive = false)
        val seer = player("u3", 3, PlayerRole.SEER)
        val guard = player("u4", 4, PlayerRole.GUARD)
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = true, hasWitch = true, hasGuard = true)
        val ctx = GameContext(game(), r, listOf(wolf, deadWitch, seer, guard), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        
        // Capture audioSequence for verification
        val audioSequenceCaptor = mutableListOf<AudioSequence>()
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            eq(NightSubPhase.WITCH_ACT)
        )).thenAnswer {
            val result = AudioSequence(
                id = "$gameId-${System.currentTimeMillis()}-DEAD-WITCH",
                phase = GamePhase.NIGHT,
                subPhase = NightSubPhase.WITCH_ACT.name,
                audioFiles = listOf("witch_open_eyes.mp3", "witch_close_eyes.mp3"),
                priority = 3,
                timestamp = System.currentTimeMillis()
            )
            audioSequenceCaptor.add(result)
            result
        }

        orchestrator.advance(gameId, NightSubPhase.SEER_RESULT)

        // Should enter WITCH_ACT phase and play dead role audio
        verify(nightPhaseRepository).save(argThat<NightPhase> { np -> np.subPhase == NightSubPhase.WITCH_ACT })
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            eq(audioSequenceCaptor[0]),
            eq(5000L),
            eq(NightSubPhase.GUARD_PICK),
            eq(false) // Not last role
        )
    }

    @Test
    fun `advance - enters SEER_RESULT when all seers are dead, plays dead role audio`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, witchHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val witch = player("u2", 2, PlayerRole.WITCH)
        val deadSeer = player("u3", 3, PlayerRole.SEER, alive = false)
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = true, hasWitch = true)
        val ctx = GameContext(game(), r, listOf(wolf, witch, deadSeer), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        
        // Capture audioSequence for verification
        val audioSequenceCaptor = mutableListOf<AudioSequence>()
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            eq(NightSubPhase.SEER_RESULT)
        )).thenAnswer {
            val result = AudioSequence(
                id = "$gameId-${System.currentTimeMillis()}-DEAD-SEER",
                phase = GamePhase.NIGHT,
                subPhase = NightSubPhase.SEER_RESULT.name,
                audioFiles = listOf("seer_open_eyes.mp3", "seer_close_eyes.mp3"),
                priority = 3,
                timestamp = System.currentTimeMillis()
            )
            audioSequenceCaptor.add(result)
            result
        }

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Verify: UI should update to SEER_RESULT (seer is dead, skip SEER_PICK)
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { event ->
            event is DomainEvent.NightSubPhaseChanged && event.subPhase == NightSubPhase.SEER_RESULT
        })

        // Verify: Dead role audio should be scheduled
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            eq(audioSequenceCaptor[0]),
            eq(5000L),
            eq(NightSubPhase.WITCH_ACT),
            eq(false) // Not last role
        )
    }

    @Test
    fun `advance - enters GUARD_PICK when all guards are dead, plays dead role audio`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val deadGuard = player("u2", 2, PlayerRole.GUARD, alive = false)
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasGuard = true)
        val ctx = GameContext(game(), r, listOf(wolf, deadGuard), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)

        // Capture audioSequence for verification
        val audioSequenceCaptor = mutableListOf<AudioSequence>()
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            eq(NightSubPhase.GUARD_PICK)
        )).thenAnswer {
            val result = AudioSequence(
                id = "$gameId-${System.currentTimeMillis()}-DEAD-GUARD",
                phase = GamePhase.NIGHT,
                subPhase = NightSubPhase.GUARD_PICK.name,
                audioFiles = listOf("guard_open_eyes.mp3", "guard_close_eyes.mp3"),
                priority = 3,
                timestamp = System.currentTimeMillis()
            )
            audioSequenceCaptor.add(result)
            result
        }

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Should enter GUARD_PICK phase and play dead role audio
        verify(nightPhaseRepository).save(argThat<NightPhase> { np -> np.subPhase == NightSubPhase.GUARD_PICK })
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            eq(audioSequenceCaptor[0]),
            eq(5000L),
            eq(null),
            eq(true) // Auto advance to day
        )
    }

    @Test
    fun `advance - skips multiple dead roles, continues until next alive role or DAY`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, witchHandler, seerHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val deadWitch = player("u2", 2, PlayerRole.WITCH, alive = false)
        val deadSeer = player("u3", 3, PlayerRole.SEER, alive = false)
        val deadGuard = player("u4", 4, PlayerRole.GUARD, alive = false)
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 12,
            hasSeer = true, hasWitch = true, hasGuard = true)
        val ctx = GameContext(
            game(),
            r,
            listOf(wolf, deadWitch, deadSeer, deadGuard),
            nightPhase = np
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)
        
        // Mock audio service for dead role transition
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            any()
        )).thenReturn(AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-DEAD-SEER",
            phase = GamePhase.NIGHT,
            subPhase = NightSubPhase.SEER_PICK.name,
            audioFiles = listOf("seer_open_eyes.mp3", "seer_close_eyes.mp3"),
            priority = 3,
            timestamp = System.currentTimeMillis()
        ))
        
        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Verify: Should enter SEER_PICK phase (seer is dead) and schedule dead role audio
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            any(),
            eq(5000L),
            eq(NightSubPhase.SEER_PICK),
            eq(false) // Not last role
        )
    }

    @Test
    fun `advance - when only werewolves and villagers survive, enters dead role phases sequentially`() {
        // Scenario: Only wolves and villagers survive (seer, witch, guard all dead)
        // Test that night phase enters first dead role phase and schedules audio
        
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, witchHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
            it.wolfTargetUserId = "v1" // Wolf targets a villager
        }
        
        // Only wolves alive, all special roles dead
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF, alive = true)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF, alive = true)
        val deadSeer = player("s1", 3, PlayerRole.SEER, alive = false)
        val deadWitch = player("w1", 4, PlayerRole.WITCH, alive = false)
        val deadGuard = player("g1", 5, PlayerRole.GUARD, alive = false)
        val villager1 = player("v1", 6, PlayerRole.VILLAGER, alive = true) // Wolf target
        val villager2 = player("v2", 7, PlayerRole.VILLAGER, alive = true)
        
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 7,
            hasSeer = true, hasWitch = true, hasGuard = true)
        val ctx = GameContext(
            game(),
            r,
            listOf(wolf1, wolf2, deadSeer, deadWitch, deadGuard, villager1, villager2),
            nightPhase = np
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null) // No winner yet
        mockAudioServiceForDayTransition(r)
        
        // Capture audioSequence for verification
        val audioSequenceCaptor = mutableListOf<AudioSequence>()
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            eq(NightSubPhase.SEER_RESULT)
        )).thenAnswer {
            val result = AudioSequence(
                id = "$gameId-${System.currentTimeMillis()}-DEAD-SEER",
                phase = GamePhase.NIGHT,
                subPhase = NightSubPhase.SEER_RESULT.name,
                audioFiles = listOf("seer_open_eyes.mp3", "seer_close_eyes.mp3"),
                priority = 3,
                timestamp = System.currentTimeMillis()
            )
            audioSequenceCaptor.add(result)
            result
        }
        
        // Mock gamePlayerRepository to simulate villager being killed
        whenever(gamePlayerRepository.findByGameIdAndUserId(eq(gameId), eq("v1")))
            .thenReturn(Optional.of(villager1))

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Verify: Should enter SEER_RESULT phase (seer is dead, skip SEER_PICK)
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { event ->
            event is DomainEvent.NightSubPhaseChanged && event.subPhase == NightSubPhase.SEER_RESULT
        })
        
        // Verify: Should schedule dead role audio
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            eq(audioSequenceCaptor[0]),
            eq(5000L),
            eq(NightSubPhase.WITCH_ACT),
            eq(false) // Not last role
        )
    }

    @Test
    fun `advance - when only werewolves and villagers survive, dead role audio sequence is correct`() {
        // Test that dead role audio sequence is correct: seer_open_eyes, seer_close_eyes, witch_open_eyes, witch_close_eyes, guard_open_eyes, guard_close_eyes
        
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val witchHandler = stubHandler(PlayerRole.WITCH, NightSubPhase.WITCH_ACT)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler, witchHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
            it.wolfTargetUserId = "v1"
        }
        
        // Only wolves alive, all special roles dead
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF, alive = true)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF, alive = true)
        val deadSeer = player("s1", 3, PlayerRole.SEER, alive = false)
        val deadWitch = player("w1", 4, PlayerRole.WITCH, alive = false)
        val deadGuard = player("g1", 5, PlayerRole.GUARD, alive = false)
        val villager1 = player("v1", 6, PlayerRole.VILLAGER, alive = true)
        val villager2 = player("v2", 7, PlayerRole.VILLAGER, alive = true)
        
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 7,
            hasSeer = true, hasWitch = true, hasGuard = true)
        val ctx = GameContext(
            game(),
            r,
            listOf(wolf1, wolf2, deadSeer, deadWitch, deadGuard, villager1, villager2),
            nightPhase = np
        )

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        mockAudioServiceForDayTransition(r)
        
        // Mock audio service to capture the audio sequence
        val audioSequenceCaptor = mutableListOf<AudioSequence>()
        whenever(audioService.calculateDeadRoleAudioSequence(
            eq(gameId),
            any(),
            eq(NightSubPhase.SEER_RESULT)
        )).thenAnswer {
            AudioSequence(
                id = "$gameId-${System.currentTimeMillis()}-DEAD-SEER",
                phase = GamePhase.NIGHT,
                subPhase = NightSubPhase.SEER_RESULT.name,
                audioFiles = listOf(
                    "seer_open_eyes.mp3",
                    "seer_close_eyes.mp3"
                ),
                priority = 3,
                timestamp = System.currentTimeMillis()
            ).also { audioSequenceCaptor.add(it) }
        }
        
        whenever(gamePlayerRepository.findByGameIdAndUserId(eq(gameId), eq("v1")))
            .thenReturn(Optional.of(villager1))

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Verify: Audio sequence should contain seer dead role audio files
        assertThat(audioSequenceCaptor).hasSize(1)
        val audioSequence = audioSequenceCaptor[0]
        assertThat(audioSequence.audioFiles).containsExactly(
            "seer_open_eyes.mp3",
            "seer_close_eyes.mp3"
        )
        
        // Verify: Should enter SEER_RESULT phase (seer is dead, skip SEER_PICK)
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { event ->
            event is DomainEvent.NightSubPhaseChanged && event.subPhase == NightSubPhase.SEER_RESULT
        })
        
        // Verify: Should schedule dead role audio for seer
        verify(nightWaitingScheduler).scheduleDeadRoleAudio(
            eq(gameId),
            eq(1), // dayNumber
            eq(audioSequenceCaptor[0]),
            eq(5000L),
            eq(NightSubPhase.WITCH_ACT),
            eq(false) // Not last role
        )
    }

    // ── recoverStuckNightPhase ───────────────────────────────────────────────

    @Test
    fun `recoverStuckNightPhase - does nothing when game is not in NIGHT phase`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val game = game().also { it.phase = GamePhase.DAY_DISCUSSION }
        val ctx = GameContext(game, room(), listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        orchestrator.recoverStuckNightPhase(gameId)

        // Should NOT advance
        verify(nightWaitingScheduler, never()).scheduleAdvance(any(), any(), any())
    }

    @Test
    fun `recoverStuckNightPhase - does nothing when night phase is COMPLETE`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val ctx = GameContext(game(), room(), listOf(wolf), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)

        orchestrator.recoverStuckNightPhase(gameId)

        // Should NOT advance
        verify(nightWaitingScheduler, never()).scheduleAdvance(any(), any(), any())
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
    fun `advance - schedules audio transition when transitioning between night sub-phases`() {
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val seerHandler = stubHandler(PlayerRole.SEER, NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, seerHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.SEER_PICK
        }
        val room = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasSeer = true)
        val seer = player("u1", 1, PlayerRole.SEER)
        val ctx = GameContext(game(), room, listOf(seer), nightPhase = np)
        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }

        // Setup audio calculations for SEER_PICK to SEER_RESULT transition
        // SEER_PICK -> SEER_RESULT: NO close eyes (seer is still awake viewing result); no open eyes for SEER_RESULT
        whenever(audioService.calculateOpenEyesAudio(NightSubPhase.SEER_RESULT))
            .thenReturn(null) // SEER_RESULT has no open eyes audio

        orchestrator.advance(gameId, NightSubPhase.SEER_PICK)

        // Verify NightSubPhaseChanged event was broadcast
        verify(stompPublisher).broadcastGame(eq(gameId), any<DomainEvent.NightSubPhaseChanged>())

        // Verify scheduler was called with null close-eyes: seer stays awake until SEER_RESULT is done
        verify(nightWaitingScheduler).scheduleAliveRoleTransition(
            eq(gameId),
            eq(null),
            eq(null),
            any()
        )
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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

    @Test
    fun `advance - guard as last special role should NOT be skipped when alive`() {
        // Test case: Guard is the last special role and is alive
        // This should NOT skip to DAY, but enter GUARD_PICK phase
        val wolfHandler = stubHandler(PlayerRole.WEREWOLF, NightSubPhase.WEREWOLF_PICK)
        val guardHandler = stubHandler(PlayerRole.GUARD, NightSubPhase.GUARD_PICK)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, guardHandler))

        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
        }
        val wolf = player("u1", 1, PlayerRole.WEREWOLF)
        val guard = player("u2", 2, PlayerRole.GUARD, alive = true) // Guard is alive
        val r = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6, hasGuard = true)
        val ctx = GameContext(game(), r, listOf(wolf, guard), nightPhase = np)

        whenever(contextLoader.load(gameId)).thenReturn(ctx)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        
        // Mock audio service for normal transition
        whenever(audioService.calculateNightSubPhaseTransition(
            eq(gameId),
            eq(NightSubPhase.WEREWOLF_PICK),
            eq(NightSubPhase.GUARD_PICK)
        )).thenReturn(AudioSequence(
            id = "$gameId-${System.currentTimeMillis()}-NIGHT-TRANSITION",
            phase = GamePhase.NIGHT,
            subPhase = NightSubPhase.GUARD_PICK.name,
            audioFiles = listOf("wolf_close_eyes.mp3", "guard_open_eyes.mp3"),
            priority = 3,
            timestamp = System.currentTimeMillis()
        ))

        orchestrator.advance(gameId, NightSubPhase.WEREWOLF_PICK)

        // Verify: Should enter GUARD_PICK phase (guard is alive)
        verify(nightPhaseRepository).save(argThat<NightPhase> { np -> np.subPhase == NightSubPhase.GUARD_PICK })
        
        // Verify: Should NOT resolve night kills
        verify(stompPublisher, never()).broadcastGame(eq(gameId), argThat { event -> 
            event is DomainEvent.NightResult || event is DomainEvent.PhaseChanged && (event as DomainEvent.PhaseChanged).phase == GamePhase.DAY_DISCUSSION 
        })
        
        // Verify: Should broadcast GUARD_PICK phase change
        verify(stompPublisher).broadcastGame(eq(gameId), argThat { event ->
            event is DomainEvent.NightSubPhaseChanged && event.subPhase == NightSubPhase.GUARD_PICK
        })
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
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
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
}
