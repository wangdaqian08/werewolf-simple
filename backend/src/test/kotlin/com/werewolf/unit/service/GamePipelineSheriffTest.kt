package com.werewolf.unit.service

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.model.*
import com.werewolf.repository.*
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
 * Tests for the Variant B sheriff-election flow:
 *  - ROLE_REVEAL → host calls START_NIGHT → NIGHT 1 (regardless of hasSheriff)
 *  - NIGHT 1 result revealed on Day 1 → if hasSheriff, auto-trigger
 *    SHERIFF_ELECTION/SIGNUP. Otherwise stay in DAY_DISCUSSION/RESULT_REVEALED.
 */
@ExtendWith(MockitoExtension::class)
class GamePipelineSheriffTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var sheriffService: SheriffService
    @Mock lateinit var nightOrchestrator: NightOrchestrator
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

    // ── confirmRole always stays in ROLE_REVEAL ───────────────────────────────

    @Test
    fun `confirmRole always stays in ROLE_REVEAL (hasSheriff=true)`() {
        // Variant B: confirmRole only updates the player's confirmedRole flag.
        // It never creates a SheriffElection or transitions the game phase —
        // the host drives the next step (START_NIGHT).
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
        // starts Night 1, then sheriff election runs on Day 1 morning.
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

    // ── revealNightResult auto-triggers SHERIFF_ELECTION on Day 1 ──────────────

    @Test
    fun `revealNightResult on Day 1 with hasSheriff=true creates SheriffElection and transitions to SHERIFF_ELECTION`() {
        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = true),
            emptyList(),
        )
        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.empty())
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
        verify(sheriffElectionRepository).save(any<SheriffElection>())
    }

    @Test
    fun `revealNightResult on Day 1 with hasSheriff=false stays in DAY_DISCUSSION`() {
        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = false),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        verifyNoInteractions(sheriffElectionRepository)
    }

    @Test
    fun `revealNightResult on Day 2 with hasSheriff=true does NOT create another SheriffElection`() {
        // Sheriff election runs at most once per game (Day 1 only).
        val ctx = GameContext(
            game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 2, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            room(hasSheriff = true),
            emptyList(),
        )

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        verifyNoInteractions(sheriffElectionRepository)
    }

    @Test
    fun `revealNightResult on Day 1 with sheriff already elected does NOT recreate SheriffElection`() {
        // Defensive: should not happen on Day 1, but ensure idempotence.
        val gameWithSheriff = game(phase = GamePhase.DAY_DISCUSSION, dayNumber = 1, subPhase = DaySubPhase.RESULT_HIDDEN.name)
            .also { it.sheriffUserId = guestId }
        val ctx = GameContext(gameWithSheriff, room(hasSheriff = true), emptyList())

        val req = GameActionRequest(gameId, hostId, ActionType.REVEAL_NIGHT_RESULT)
        val result = pipeline.revealNightResult(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        verifyNoInteractions(sheriffElectionRepository)
    }
}
