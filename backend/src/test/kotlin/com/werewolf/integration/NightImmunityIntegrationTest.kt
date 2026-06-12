package com.werewolf.integration

import com.werewolf.game.GameContext
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.PerkService
import com.werewolf.service.StompPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness

/**
 * Verifies the night-1 immunity WIRING inside NightOrchestrator.resolveNightKills
 * (the pure kill maths is covered by Night1ImmunityTest): the resolver folds the
 * perk holders into the kill computation and records the decisive-save trigger on
 * night 1 only — status stays ACTIVE; CONSUMED/REFUNDED is decided at game end
 * by PerkSettlementService.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NightImmunityIntegrationTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var winConditionChecker: com.werewolf.game.phase.WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var audioService: com.werewolf.service.AudioService
    @Mock lateinit var perkService: PerkService

    private lateinit var nightOrchestrator: NightOrchestrator

    private val gameId = 1
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val immuneId = "immune:001"

    @BeforeEach
    fun setUp() {
        nightOrchestrator = NightOrchestrator(
            handlers = emptyList(),
            gameRepository = gameRepository,
            gamePlayerRepository = gamePlayerRepository,
            nightPhaseRepository = nightPhaseRepository,
            sheriffElectionRepository = mock(),
            eliminationHistoryRepository = mock(),
            winConditionChecker = winConditionChecker,
            stompPublisher = stompPublisher,
            contextLoader = contextLoader,
            audioService = audioService,
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            actionLogService = mock(),
            timing = com.werewolf.config.GameTimingProperties(),
            rewardSettlementService = mock(),
            perkService = perkService,
        )
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)
        whenever(nightPhaseRepository.save(any<NightPhase>())).thenAnswer { it.arguments[0] }
        whenever(
            audioService.calculatePhaseTransition(eq(gameId), eq(GamePhase.NIGHT), eq(GamePhase.DAY_DISCUSSION), isNull(), any(), any()),
        ).thenReturn(
            AudioSequence(
                id = "$gameId-day", phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_HIDDEN.name,
                audioFiles = listOf("day_time.mp3"), priority = 10, timestamp = 0L,
            ),
        )
    }

    private fun game(dayNumber: Int = 1) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.NIGHT
        it.subPhase = NightSubPhase.WEREWOLF_PICK.name
        it.dayNumber = dayNumber
    }

    // hasSheriff=false keeps resolveNightKills on the DAY_DISCUSSION end-of-night branch.
    private fun room() = Room(roomCode = "ABC", hostUserId = hostId, totalPlayers = 6, hasSheriff = false)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role)

    private fun nightPhase(dayNumber: Int, wolfTarget: String) =
        NightPhase(gameId = gameId, dayNumber = dayNumber).also {
            it.subPhase = NightSubPhase.WEREWOLF_PICK
            it.wolfTargetUserId = wolfTarget
        }

    @Test
    fun `night 1 - wolf target holding immunity is excluded from kills and the trigger is recorded`() {
        whenever(perkService.night1ImmuneUserIds(gameId)).thenReturn(setOf(immuneId))
        val np = nightPhase(dayNumber = 1, wolfTarget = immuneId)
        val ctx = GameContext(
            game(1), room(),
            listOf(player(wolfId, 1, PlayerRole.WEREWOLF), player(immuneId, 2), player("v2", 3)),
            nightPhase = np,
        )

        nightOrchestrator.resolveNightKills(ctx, np)

        // Wiring proof: the resolver fed the perk holders into its kill
        // computation (computeKills' use of that set to spare the target is
        // unit-tested in Night1ImmunityTest) — if resolveNightKills reverted to
        // the perk-blind overload, night1ImmuneUserIds would never be called.
        verify(perkService, atLeastOnce()).night1ImmuneUserIds(gameId)
        // The decisive save is recorded as triggered — status flips happen only
        // at game-end settlement, never mid-game.
        verify(perkService).markNight1Triggered(gameId, setOf(immuneId))
    }

    @Test
    fun `night 1 - non-immune wolf target records no trigger`() {
        whenever(perkService.night1ImmuneUserIds(gameId)).thenReturn(setOf(immuneId))
        val np = nightPhase(dayNumber = 1, wolfTarget = "v2")
        val ctx = GameContext(
            game(1), room(),
            listOf(player(wolfId, 1, PlayerRole.WEREWOLF), player(immuneId, 2), player("v2", 3)),
            nightPhase = np,
        )

        nightOrchestrator.resolveNightKills(ctx, np)

        // Holder was never attacked → no trigger; settlement will refund.
        verify(perkService, never()).markNight1Triggered(any(), any())
    }

    @Test
    fun `night 2 - immunity no longer applies and no trigger is recorded`() {
        // Even though the user is still reported immune, day 2 ignores it.
        whenever(perkService.night1ImmuneUserIds(gameId)).thenReturn(setOf(immuneId))
        val np = nightPhase(dayNumber = 2, wolfTarget = immuneId)
        val ctx = GameContext(
            game(2), room(),
            listOf(player(wolfId, 1, PlayerRole.WEREWOLF), player(immuneId, 2), player("v2", 3)),
            nightPhase = np,
        )

        nightOrchestrator.resolveNightKills(ctx, np)

        // The night-1 trigger guard (if dayNumber == 1) must not fire on a
        // later night — an immunity bought for night 1 never covers night 2+,
        // and a spurious trigger would make settlement consume instead of refund.
        verify(perkService, never()).markNight1Triggered(any(), any())
    }
}
