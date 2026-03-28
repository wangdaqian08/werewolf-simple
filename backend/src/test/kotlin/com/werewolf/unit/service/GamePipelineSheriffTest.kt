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

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.ROLE_REVEAL
    }

    private fun room(hasSheriff: Boolean = true) = Room(
        roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = hasSheriff
    )

    private fun confirmedPlayer(userId: String, seat: Int) = GamePlayer(
        gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER
    ).also { it.confirmedRole = true }

    private fun context(hasSheriff: Boolean = true): GameContext {
        val game = game()
        return GameContext(game, room(hasSheriff), emptyList())
    }

    // ── confirmRole with hasSheriff=false ──────────────────────────────────────

    @Test
    fun `confirmRole with hasSheriff=false-stays in ROLE_REVEAL when not all confirmed`() {
        val ctx = context(hasSheriff = false)
        val player = GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, guestId)).thenReturn(Optional.of(player))
        val allPlayers = listOf(
            confirmedPlayer(hostId, 0),
            player, // guestId not yet confirmed
        )
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allPlayers)

        val req = GameActionRequest(gameId, guestId, ActionType.CONFIRM_ROLE)
        val result = pipeline.confirmRole(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verifyNoInteractions(sheriffElectionRepository)
        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `confirmRole with hasSheriff=false-does NOT create SheriffElection when all confirmed`() {
        val ctx = context(hasSheriff = false)
        val player = GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, guestId)).thenReturn(Optional.of(player))
        val allPlayers = listOf(confirmedPlayer(hostId, 0), confirmedPlayer(guestId, 1))
        // After save, player is confirmed-simulate all confirmed
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allPlayers)

        val req = GameActionRequest(gameId, guestId, ActionType.CONFIRM_ROLE)
        val result = pipeline.confirmRole(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verifyNoInteractions(sheriffElectionRepository)
        verifyNoInteractions(nightOrchestrator)
        verifyNoInteractions(gameRepository)
        verify(stompPublisher).broadcastGame(eq(gameId), any())
    }

    @Test
    fun `confirmRole with hasSheriff=true-creates SheriffElection when all confirmed`() {
        val ctx = context(hasSheriff = true)
        val player = GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER)
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, guestId)).thenReturn(Optional.of(player))
        val allPlayers = listOf(confirmedPlayer(hostId, 0), confirmedPlayer(guestId, 1))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allPlayers)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.CONFIRM_ROLE)
        val result = pipeline.confirmRole(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(sheriffElectionRepository).save(any<SheriffElection>())
        verifyNoInteractions(nightOrchestrator)
    }

    // ── startNight ─────────────────────────────────────────────────────────────

    @Test
    fun `startNight-transitions to NIGHT when all confirmed and hasSheriff=false`() {
        val ctx = context(hasSheriff = false)
        val allConfirmed = listOf(confirmedPlayer(hostId, 0), confirmedPlayer(guestId, 1))
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(allConfirmed)

        val req = GameActionRequest(gameId, hostId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).initNight(gameId, 1, null, true)
    }

    @Test
    fun `startNight-rejected if hasSheriff=true`() {
        val ctx = context(hasSheriff = true)
        val req = GameActionRequest(gameId, hostId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Sheriff election is enabled")
        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `startNight-rejected if actor is not host`() {
        val ctx = context(hasSheriff = false)
        val req = GameActionRequest(gameId, guestId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("host")
        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `startNight-rejected if not all players have confirmed`() {
        val ctx = context(hasSheriff = false)
        val notAllConfirmed = listOf(
            confirmedPlayer(hostId, 0),
            GamePlayer(gameId = gameId, userId = guestId, seatIndex = 1, role = PlayerRole.VILLAGER),
        )
        whenever(gamePlayerRepository.findByGameId(gameId)).thenReturn(notAllConfirmed)

        val req = GameActionRequest(gameId, hostId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("confirmed")
        verifyNoInteractions(nightOrchestrator)
    }

    // ── startNight from SHERIFF_ELECTION RESULT ────────────────────────────────

    @Test
    fun `startNight - succeeds from SHERIFF_ELECTION when sub-phase is RESULT`() {
        // New path: after sheriff is elected, host clicks "Start Night" from the RESULT screen.
        // The game phase is SHERIFF_ELECTION and election.subPhase is RESULT.
        val sheriffGame = Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = GamePhase.SHERIFF_ELECTION
        }
        val resultElection = SheriffElection(gameId = gameId, subPhase = ElectionSubPhase.RESULT)
        val ctx = GameContext(sheriffGame, room(hasSheriff = true), emptyList(), election = resultElection)

        val req = GameActionRequest(gameId, hostId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).initNight(gameId, 1, null, true)
    }

    @Test
    fun `startNight - rejected from SHERIFF_ELECTION when sub-phase is not RESULT`() {
        // Guard: startNight must not be callable in mid-election (e.g., VOTING sub-phase).
        val sheriffGame = Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = GamePhase.SHERIFF_ELECTION
        }
        val votingElection = SheriffElection(gameId = gameId, subPhase = ElectionSubPhase.VOTING)
        val ctx = GameContext(sheriffGame, room(hasSheriff = true), emptyList(), election = votingElection)

        val req = GameActionRequest(gameId, hostId, ActionType.START_NIGHT)
        val result = pipeline.startNight(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verifyNoInteractions(nightOrchestrator)
    }
}
