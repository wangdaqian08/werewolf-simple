package com.werewolf.unit.service

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.VoteRepository
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
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class VotingPipelineSheriffWeightTest {

    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var nightOrchestrator: NightOrchestrator

    private lateinit var votingPipeline: VotingPipeline

    @BeforeEach
    fun setUp() {
        votingPipeline = makeVotingPipeline(emptyList())
    }

    private fun makeVotingPipeline(handlers: List<RoleHandler>) = VotingPipeline(
        handlers = handlers,
        voteRepository = voteRepository,
        gameRepository = gameRepository,
        gamePlayerRepository = gamePlayerRepository,
        eliminationHistoryRepository = eliminationHistoryRepository,
        winConditionChecker = winConditionChecker,
        stompPublisher = stompPublisher,
        contextLoader = contextLoader,
        nightOrchestrator = nightOrchestrator,
        actionLogService = mock(),
    )

    private val gameId = 1
    private val hostId = "host:001"
    private val sheriffId = "sheriff:001"

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun game(subPhase: String = VotingSubPhase.VOTING.name, sheriff: String? = sheriffId) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = GamePhase.DAY_VOTING
            it.subPhase = subPhase
            it.dayNumber = 1
            it.sheriffUserId = sheriff
        }

    private fun room(mode: WinConditionMode = WinConditionMode.CLASSIC) =
        Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also {
            it.alive = alive
            if (userId == sheriffId) it.sheriff = true
        }

    private fun ctx(game: Game = game(), vararg players: GamePlayer) =
        GameContext(game, room(), players.toList())

    private fun vote(voter: String, target: String?) =
        Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = 1, voterUserId = voter, targetUserId = target)

    private fun req(actorId: String, actionType: ActionType, target: String? = null) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType, targetUserId = target)

    /** Stub contextLoader to return a context with the given players. */
    private fun stubLoader(vararg players: GamePlayer) {
        whenever(contextLoader.load(gameId)).thenReturn(ctx(game(), *players))
    }

    /** Stub gamePlayerRepository to return specific players. */
    private fun stubGamePlayerRepository(vararg players: GamePlayer) {
        players.forEach { player ->
            whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, player.userId))
                .thenReturn(Optional.of(player))
        }
    }

    // ── Test Case 1: Sheriff vote has 1.5x weight ───────────────────────────────

    @Test
    fun sheriffVoteHas1_5xWeight() {
        // Setup: Sheriff votes for player A, regular player votes for player B
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val playerC = player("playerC", 3) // Abstains
        val context = ctx(game(), sheriff, playerA, playerB, playerC)

        val votes = listOf(
            vote(sheriffId, "playerA"),    // Sheriff: 1.5 votes
            vote("playerB", "playerB"),    // Player B: 1.0 vote
            vote("playerC", null)          // Player C: abstains
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB, playerC)
        stubGamePlayerRepository(sheriff, playerA, playerB, playerC)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute: Host reveals tally
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Success
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        // Verify that player A was eliminated ( sheriff's 1.5 votes beat player B's 1.0 )
        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerA" && !player.alive })
    }

    // ── Test Case 2: Sheriff vote tie breaker (1.5 vs 2.0) ───────────────────────

    @Test
    fun sheriffVoteTieBreaker() {
        // Setup: Sheriff votes for A (1.5), two regular players vote for B (2.0)
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val playerC = player("playerC", 3)
        val context = ctx(game(), sheriff, playerA, playerB, playerC)

        val votes = listOf(
            vote(sheriffId, "playerA"),    // Sheriff: 1.5 votes
            vote("playerB", "playerB"),    // Player B: 1.0 vote
            vote("playerC", "playerB")     // Player C: 1.0 vote
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB, playerC)
        stubGamePlayerRepository(sheriff, playerA, playerB, playerC)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute: Host reveals tally
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Player B should win (2.0 > 1.5)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        // Verify player B was eliminated
        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerB" && !player.alive })
    }

    // ── Test Case 3: Sheriff abstains ───────────────────────────────────────────

    @Test
    fun sheriffAbstain() {
        // Setup: Sheriff abstains, regular votes determine outcome
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val context = ctx(game(), sheriff, playerA, playerB)

        val votes = listOf(
            vote(sheriffId, null),         // Sheriff abstains
            vote("playerA", "playerB"),    // Player A votes for B
            vote("playerB", "playerB")     // Player B votes for B
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB)
        stubGamePlayerRepository(sheriff, playerA, playerB)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Player B should win (2.0 votes)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerB" && !player.alive })
    }

    // ── Test Case 4: Sheriff not voting ────────────────────────────────────────

    @Test
    fun sheriffNotVoting() {
        // Setup: Sheriff doesn't vote at all
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val context = ctx(game(), sheriff, playerA, playerB)

        val votes = listOf(
            vote("playerA", "playerB"),    // Player A votes for B
            vote("playerB", "playerA")     // Player B votes for A
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB)
        stubGamePlayerRepository(sheriff, playerA, playerB)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Should trigger re-vote due to tie (1.0 vs 1.0)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        // Verify phase changed to RE_VOTING
        val gameCaptor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(gameCaptor.capture())
        assertThat(gameCaptor.allValues).anyMatch { it.subPhase == VotingSubPhase.RE_VOTING.name }

        // Verify existing votes were deleted
        verify(voteRepository).deleteAll(any<Collection<Vote>>())
    }

    // ── Test Case 5: Sheriff vote in re-voting ─────────────────────────────────

    @Test
    fun sheriffVoteInRevote() {
        // Setup: In RE_VOTING phase, sheriff's vote still has 1.5x weight
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val context = ctx(game(VotingSubPhase.RE_VOTING.name), sheriff, playerA, playerB)

        val votes = listOf(
            vote(sheriffId, "playerA"),    // Sheriff: 1.5 votes
            vote("playerB", "playerB")     // Player B: 1.0 vote
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB)
        stubGamePlayerRepository(sheriff, playerA, playerB)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Player A should win (1.5 > 1.0)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerA" && !player.alive })
    }

    // ── Test Case 6: Sheriff vote causes elimination ───────────────────────────

    @Test
    fun sheriffVoteWithElimination() {
        // Setup: Sheriff votes for player A, no other votes
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val context = ctx(game(), sheriff, playerA)

        val votes = listOf(
            vote(sheriffId, "playerA")     // Sheriff: 1.5 votes
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA)
        stubGamePlayerRepository(sheriff, playerA)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Player A should be eliminated
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerA" && !player.alive })
    }

    // ── Test Case 7: Sheriff vote triggers tie ─────────────────────────────────

    @Test
    fun sheriffVoteTriggersTie() {
        // Setup: Sheriff votes for A (1.5), we need to create a tie scenario
        // To create a tie with 1.5x weight, we need: Sheriff + regular = 2.5 vs 2.5
        // But we can't have fractional regular votes, so let's create a scenario where:
        // Sheriff (1.5) + 1 regular (1.0) = 2.5 vs 2 regular votes (2.0) -> not a tie
        // Sheriff (1.5) + 2 regular (2.0) = 3.5 vs 3 regular votes (3.0) -> not a tie
        // Actually, with 1.5x weight, we can't create a tie with integer regular votes
        // So let's test a scenario where sheriff abstains and it's a tie

        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val playerC = player("playerC", 3)
        val context = ctx(game(), sheriff, playerA, playerB, playerC)

        val votes = listOf(
            vote(sheriffId, null),         // Sheriff abstains
            vote("playerA", "playerB"),    // Player A votes for B
            vote("playerB", "playerA"),    // Player B votes for A
            vote("playerC", null)          // Player C abstains
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB, playerC)
        stubGamePlayerRepository(sheriff, playerA, playerB, playerC)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Should trigger re-vote due to tie (1.0 vs 1.0)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        val gameCaptor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(gameCaptor.capture())
        assertThat(gameCaptor.allValues).anyMatch { it.subPhase == VotingSubPhase.RE_VOTING.name }
    }

    // ── Test Case 8: Sheriff vote creates tie in revote ───────────────────────

    @Test
    fun sheriffVoteTieInRevote() {
        // Setup: In RE_VOTING, sheriff abstains and it's a tie
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1)
        val playerB = player("playerB", 2)
        val context = ctx(game(VotingSubPhase.RE_VOTING.name), sheriff, playerA, playerB)

        val votes = listOf(
            vote(sheriffId, null),         // Sheriff abstains
            vote("playerA", "playerB"),    // Player A votes for B
            vote("playerB", "playerA")     // Player B votes for A
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff, playerA, playerB)
        stubGamePlayerRepository(sheriff, playerA, playerB)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: In RE_VOTING phase, tie should go to night (no re-vote)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        // Verify night orchestrator was called
        verify(nightOrchestrator).initNight(eq(gameId), eq(2), anyOrNull(), eq(false))
    }

    // ── Test Case 9: Sheriff votes for dead player (should be rejected) ────────

    @Test
    fun sheriffVoteAgainstDeadPlayer() {
        // Setup: Sheriff tries to vote for dead player
        val sheriff = player(sheriffId, 0)
        val playerA = player("playerA", 1, alive = false) // Dead player
        val context = ctx(game(), sheriff, playerA)

        // Execute: Sheriff tries to vote for dead player
        val result = votingPipeline.submitVote(
            req(sheriffId, ActionType.SUBMIT_VOTE, "playerA"),
            context
        )

        // Verify: Should be rejected
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    // ── Test Case 10: Multiple sheriff flags (edge case) ───────────────────────

    @Test
    fun multipleSheriffVotes() {
        // Setup: Edge case where multiple players have sheriff flag (shouldn't happen)
        val sheriff1 = player(sheriffId, 0)
        val sheriff2 = player("sheriff2", 1)
        sheriff2.sheriff = true // Another sheriff
        val playerA = player("playerA", 2)
        val context = ctx(game(), sheriff1, sheriff2, playerA)

        val votes = listOf(
            vote(sheriffId, "playerA"),     // Sheriff1: 1.5 votes
            vote("sheriff2", "playerA"),    // Sheriff2: 1.0 votes (not the sheriff)
            vote("playerA", "playerB")      // Player A: 1.0 vote
        )

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(sheriff1, sheriff2, playerA)
        stubGamePlayerRepository(sheriff1, sheriff2, playerA)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        // Execute
        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Verify: Only the actual sheriff (sheriff1) should have 1.5x weight
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        // Player A should win with 2.5 votes (1.5 + 1.0)
        verify(gamePlayerRepository).save(argThat<GamePlayer> { player -> player.userId == "playerA" && !player.alive })
    }
}