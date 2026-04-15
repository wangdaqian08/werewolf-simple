package com.werewolf.unit.service

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.GamePhasePipeline
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class GamePhasePipelineDayTest {

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

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        subPhase: String? = DaySubPhase.RESULT_HIDDEN.name,
    ) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.subPhase = subPhase
        it.dayNumber = 2
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun ctx(game: Game = game()) = GameContext(game, room(), emptyList())

    private fun req(actorId: String, actionType: ActionType) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType)

    // ── revealNightResult ────────────────────────────────────────────────────

    @Test
    fun `revealNightResult - rejected when actor is not host`() {
        val result = pipeline.revealNightResult(req(guestId, ActionType.REVEAL_NIGHT_RESULT), ctx())

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only host")
    }

    @Test
    fun `revealNightResult - rejected when not in DAY phase`() {
        val game = game(phase = GamePhase.DAY_VOTING)
        val result = pipeline.revealNightResult(req(hostId, ActionType.REVEAL_NIGHT_RESULT), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not in DAY phase")
    }

    @Test
    fun `revealNightResult - rejected when result already revealed`() {
        val game = game(subPhase = DaySubPhase.RESULT_REVEALED.name)
        val result = pipeline.revealNightResult(req(hostId, ActionType.REVEAL_NIGHT_RESULT), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("already revealed")
    }

    @Test
    fun `revealNightResult - success, subPhase changes to RESULT_REVEALED and broadcast sent`() {
        val game = game()
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val result = pipeline.revealNightResult(req(hostId, ActionType.REVEAL_NIGHT_RESULT), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(game.subPhase).isEqualTo(DaySubPhase.RESULT_REVEALED.name)
        verify(gameRepository).save(game)
        verify(stompPublisher).broadcastGame(eq(gameId), any())
    }

    // ── dayAdvance ───────────────────────────────────────────────────────────

    @Test
    fun `dayAdvance - rejected when actor is not host`() {
        val game = game(subPhase = DaySubPhase.RESULT_REVEALED.name)
        val result = pipeline.dayAdvance(req(guestId, ActionType.DAY_ADVANCE), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only host")
    }

    @Test
    fun `dayAdvance - rejected when not in DAY phase`() {
        val game = game(phase = GamePhase.NIGHT)
        val result = pipeline.dayAdvance(req(hostId, ActionType.DAY_ADVANCE), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not in DAY phase")
    }

    @Test
    fun `dayAdvance - rejected when subPhase is not RESULT_REVEALED`() {
        val game = game(subPhase = DaySubPhase.RESULT_HIDDEN.name)
        val result = pipeline.dayAdvance(req(hostId, ActionType.DAY_ADVANCE), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Reveal the night result")
    }

    @Test
    fun `dayAdvance - success, transitions to VOTING phase and broadcasts`() {
        val game = game(subPhase = DaySubPhase.RESULT_REVEALED.name)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val result = pipeline.dayAdvance(req(hostId, ActionType.DAY_ADVANCE), ctx(game))

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(game.phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(game.subPhase).isEqualTo(VotingSubPhase.VOTING.name)
        verify(gameRepository).save(game)
        verify(stompPublisher).broadcastGame(eq(gameId), any())
    }
}
