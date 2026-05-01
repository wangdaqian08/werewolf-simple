package com.werewolf.unit.service

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.ActionLogService
import com.werewolf.service.GameContextLoader
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

/**
 * Tests for the GamePhasePipeline contract under the corrected Variant B
 * sheriff-election ordering:
 *
 *  - ROLE_REVEAL → host calls START_NIGHT → NIGHT 1 (regardless of hasSheriff)
 *  - End of night → SHERIFF_ELECTION (Day 1 + hasSheriff) auto-opens, kills
 *    are NOT applied yet (NightOrchestrator.resolveNightKills's job).
 *  - revealNightResult is the moment kills are *applied* (alive=false flipped)
 *    and the NightResult event is broadcast — it no longer triggers the
 *    sheriff election (that already opened at end of night, hours of
 *    gameplay ago).
 */
@ExtendWith(MockitoExtension::class)
class GamePipelineSheriffTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var actionLogService: ActionLogService
    @InjectMocks lateinit var pipeline: GamePhasePipeline

    private val gameId = 10
    private val hostId = "host:001"
    private val guestId = "guest:001"

    private fun game(phase: GamePhase = GamePhase.ROLE_REVEAL, dayNumber: Int = 1, subPhase: String? = null) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.dayNumber = dayNumber
            it.subPhase = subPhase
        }

    private fun room(hasSheriff: Boolean = true) = Room(
        roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = hasSheriff
    )

    private fun confirmedPlayer(userId: String, seat: Int) = GamePlayer(
        gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER
    ).also { it.confirmedRole = true }

    private fun nightPhase(dayNumber: Int = 1) = NightPhase(gameId = gameId, dayNumber = dayNumber).also {
        it.subPhase = NightSubPhase.COMPLETE
    }

    // ── confirmRole always stays in ROLE_REVEAL ───────────────────────────────

    @Test
    fun `confirmRole always stays in ROLE_REVEAL (hasSheriff=true)`() {
        // Variant B: confirmRole only updates the player's confirmedRole flag.
        // It never creates a SheriffElection or transitions the game phase —
        // the host drives the next step (START_NIGHT). The actual sheriff
        // election triggers from end-of-night.
        val ctx = GameContext(game(), room(hasSheriff = true), emptyList())
        val player = GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, guestId)).thenReturn(Optional.of(player))

        val req = GameActionRequest(gameId, guestId, ActionType.CONFIRM_ROLE)
        val result = pipeline.confirmRole(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verifyNoInteractions(sheriffElectionRepository)
        verifyNoInteractions(nightOrchestrator)
        verifyNoInteractions(gameRepository)
    }

    @Test
    fun `confirmRole always stays in ROLE_REVEAL (hasSheriff=false)`() {
        val ctx = GameContext(game(), room(hasSheriff = false), emptyList())
        val player = GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, guestId)).thenReturn(Optional.of(player))

        val req = GameActionRequest(gameId, guestId, ActionType.CONFIRM_ROLE)
        val result = pipeline.confirmRole(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verifyNoInteractions(sheriffElectionRepository)
        verifyNoInteractions(nightOrchestrator)
        verifyNoInteractions(gameRepository)
    }

    // ── startNight is allowed regardless of hasSheriff ─────────────────────────

    @Test
    fun `startNight transitions to NIGHT when all confirmed and hasSheriff=false`() {
        val ctx = GameContext(game(), room(hasSheriff = false), emptyList())
        val allConfirmed = listOf(confirmedPlayer(hostId, 0), confirmedPlayer(guestId, 1))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allConfirmed)

        val result = pipeline.startNight(GameActionRequest(gameId, hostId, ActionType.START_NIGHT), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).initNight(gameId, 1, null, true)
    }

    @Test
    fun `startNight transitions to NIGHT when all confirmed and hasSheriff=true (Variant B)`() {
        // Variant B: sheriff election no longer gates start-of-night. Host
        // starts Night 1, then sheriff election runs on Day 1 morning (after
        // end-of-night, before death announcement).
        val ctx = GameContext(game(), room(hasSheriff = true), emptyList())
        val allConfirmed = listOf(confirmedPlayer(hostId, 0), confirmedPlayer(guestId, 1))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allConfirmed)

        val result = pipeline.startNight(GameActionRequest(gameId, hostId, ActionType.START_NIGHT), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).initNight(gameId, 1, null, true)
    }

    @Test
    fun `startNight rejected if actor is not host`() {
        val ctx = GameContext(game(), room(hasSheriff = false), emptyList())
        val result = pipeline.startNight(GameActionRequest(gameId, guestId, ActionType.START_NIGHT), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("host")
        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `startNight rejected if not all players have confirmed`() {
        val ctx = GameContext(game(), room(hasSheriff = false), emptyList())
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(
            confirmedPlayer(hostId, 0),
            GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER),
        ))

        val result = pipeline.startNight(GameActionRequest(gameId, hostId, ActionType.START_NIGHT), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("confirmed")
        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `startNight rejected if not in ROLE_REVEAL phase`() {
        val ctx = GameContext(game(phase = GamePhase.NIGHT), room(hasSheriff = true), emptyList())
        val result = pipeline.startNight(GameActionRequest(gameId, hostId, ActionType.START_NIGHT), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verifyNoInteractions(nightOrchestrator)
    }

    // ── revealNightResult applies deferred kills ──────────────────────────────

    @Test
    fun `revealNightResult applies pending wolf-kill via NightOrchestrator and broadcasts NightResult`() {
        // The deferred kill is wolf_target without antidote/guard save —
        // computePendingKills returns ['victim'].
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = "victim"
        }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(listOf("victim"))

        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = true),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // Kills applied via the orchestrator helper (pure function for testing).
        verify(nightOrchestrator).applyNightKills(gameId, listOf("victim"))
        verify(actionLogService).recordNightDeaths(gameId, 1, listOf("victim"))
        // Transitions to RESULT_REVEALED.
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `revealNightResult with no pending kills does NOT call applyNightKills`() {
        val np = NightPhase(gameId = gameId, dayNumber = 1).also { it.subPhase = NightSubPhase.COMPLETE }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(emptyList())

        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = false),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator, never()).applyNightKills(any(), any())
        verifyNoInteractions(actionLogService)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }

    @Test
    fun `revealNightResult does NOT trigger sheriff election (that happens at end of night now)`() {
        // The old Variant-B-incorrect implementation triggered SHERIFF_ELECTION
        // from revealNightResult on Day 1. The corrected flow opens the
        // election at end-of-night via NightOrchestrator.resolveNightKills,
        // so by the time revealNightResult runs the sheriff election is
        // already over.
        val np = NightPhase(gameId = gameId, dayNumber = 1).also { it.subPhase = NightSubPhase.COMPLETE }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(emptyList())

        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = true),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        pipeline.revealNightResult(req, ctx)

        verifyNoInteractions(sheriffElectionRepository)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
    }

    // ── Phase B: night-kill HUNTER_SHOOT and BADGE_HANDOVER routing ───────────

    @Test
    fun `revealNightResult routes to HUNTER_SHOOT when killed player is HUNTER (Phase B)`() {
        // Wolf killed player u3 who happens to be a hunter. Hunter should get
        // a chance to shoot before the night result is fully revealed.
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = "u3"
        }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(listOf("u3"))

        val hunter = GamePlayer(gameId = gameId, userId = "u3", seatIndex = 3, role = PlayerRole.HUNTER)
        val villager = GamePlayer(gameId = gameId, userId = "u4", seatIndex = 4, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(hunter, villager))

        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = false),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).applyNightKills(gameId, listOf("u3"))
        // Routes to HUNTER_SHOOT, not RESULT_REVEALED.
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.HUNTER_SHOOT.name)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
    }

    @Test
    fun `revealNightResult routes to BADGE_HANDOVER when killed player is sheriff (Phase B)`() {
        // Wolf killed the sheriff. Sheriff should choose pass-or-destroy
        // before the day continues.
        val sheriffId = "sheriff:001"
        val np = NightPhase(gameId = gameId, dayNumber = 2).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = sheriffId
        }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 2)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(listOf(sheriffId))

        val sheriff = GamePlayer(gameId = gameId, userId = sheriffId, seatIndex = 5, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(sheriff))

        val gameWithSheriff = game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 2, subPhase = DaySubPhase.RESULT_HIDDEN.name)
            .also { it.sheriffUserId = sheriffId }
        val ctx = GameContext(gameWithSheriff, room(hasSheriff = true), emptyList())

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).applyNightKills(gameId, listOf(sheriffId))
        // Routes to BADGE_HANDOVER under DAY_DISCUSSION.
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.BADGE_HANDOVER.name)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
    }

    @Test
    fun `revealNightResult prioritises HUNTER_SHOOT when both hunter and sheriff are killed`() {
        // Wolf killed hunter, witch poisoned sheriff. HUNTER_SHOOT fires first;
        // the hunter handler chains to BADGE_HANDOVER if its shot target
        // happens to be the (now-also-dead) sheriff. Otherwise badge handover
        // can be triggered by the test logic afterwards. This test only
        // asserts the immediate routing decision: HUNTER_SHOOT first.
        val sheriffId = "sheriff:002"
        val hunterId = "hunter:001"
        val np = NightPhase(gameId = gameId, dayNumber = 3).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = hunterId
            it.witchPoisonTargetUserId = sheriffId
        }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 3)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(listOf(hunterId, sheriffId))

        val hunter = GamePlayer(gameId = gameId, userId = hunterId, seatIndex = 1, role = PlayerRole.HUNTER)
        val sheriff = GamePlayer(gameId = gameId, userId = sheriffId, seatIndex = 2, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(hunter, sheriff))

        val gameWithSheriff = game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 3, subPhase = DaySubPhase.RESULT_HIDDEN.name)
            .also { it.sheriffUserId = sheriffId }
        val ctx = GameContext(gameWithSheriff, room(hasSheriff = true), emptyList())

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        pipeline.revealNightResult(req, ctx)

        // Hunter shoots first; badge handover follows in the hunter handler
        // (DAY_DISCUSSION/HUNTER_SHOOT → … → BADGE_HANDOVER if applicable).
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.HUNTER_SHOOT.name)
    }

    @Test
    fun `revealNightResult routes to RESULT_REVEALED when no special role killed`() {
        val np = NightPhase(gameId = gameId, dayNumber = 1).also {
            it.subPhase = NightSubPhase.COMPLETE
            it.wolfTargetUserId = "u3"
        }
        whenever(nightPhaseRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.of(np))
        whenever(nightOrchestrator.computePendingKills(np)).thenReturn(listOf("u3"))

        // Killed player is just a villager (no special role).
        val villager = GamePlayer(gameId = gameId, userId = "u3", seatIndex = 3, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(listOf(villager))

        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = true),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        pipeline.revealNightResult(req, ctx)

        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
    }
}
