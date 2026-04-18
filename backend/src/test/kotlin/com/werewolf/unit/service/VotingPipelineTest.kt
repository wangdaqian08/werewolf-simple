package com.werewolf.unit.service

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
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
class VotingPipelineTest {

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun game(subPhase: String = VotingSubPhase.VOTING.name, sheriff: String? = null) =
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
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

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

    // ── revealTally — tie handling ────────────────────────────────────────────

    @Test
    fun `revealTally - first-round tie triggers re-vote, clears votes, broadcasts RE_VOTING`() {
        val host = player(hostId, 0)
        val p1 = player("u1", 1)
        val p2 = player("u2", 2)
        val context = ctx(game(VotingSubPhase.VOTING.name), host, p1, p2)

        // Both u1 and u2 have 1 vote each → tie
        val tiedVotes = listOf(vote("u3", "u1"), vote("u4", "u2"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(tiedVotes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // Votes cleared for re-vote
        verify(voteRepository).deleteAll(tiedVotes)
        // Game sub-phase set to RE_VOTING
        val captor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(captor.capture())
        assertThat(captor.allValues).anyMatch { it.subPhase == VotingSubPhase.RE_VOTING.name }
        // PhaseChanged broadcast with RE_VOTING
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), any())
    }

    @Test
    fun `revealTally - re-vote round tie goes directly to night`() {
        val host = player(hostId, 0)
        val p1 = player("u1", 1)
        val p2 = player("u2", 2)
        val context = ctx(game(VotingSubPhase.RE_VOTING.name), host, p1, p2)

        // Second tie
        val tiedVotes = listOf(vote("u3", "u1"), vote("u4", "u2"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(tiedVotes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // No re-vote deletion should happen
        verify(voteRepository, never()).deleteAll(any<Collection<Vote>>())
        // initNight called (goToNight path)
        verify(nightOrchestrator).initNight(any(), any(), anyOrNull(), any())
    }

    // ── revealTally — elimination ─────────────────────────────────────────────

    @Test
    fun `revealTally - single winner villager eliminated, player alive set to false`() {
        val host = player(hostId, 0)
        val victim = player("u1", 1)
        val wolf = player("u2", 2, PlayerRole.WEREWOLF)
        val context = ctx(game(), host, victim, wolf)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"), vote("u4", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(victim))
        stubLoader(wolf) // after kill: only wolf alive
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(victim.alive).isFalse()
        verify(gamePlayerRepository).save(victim)
    }

    @Test
    fun `revealTally - hunter eliminated transitions to HUNTER_SHOOT sub-phase`() {
        val host = player(hostId, 0)
        val hunter = player("u1", 1, PlayerRole.HUNTER)
        val context = ctx(game(), host, hunter)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(hunter))

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        val captor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(captor.capture())
        assertThat(captor.allValues).anyMatch { it.subPhase == VotingSubPhase.HUNTER_SHOOT.name }
    }

    @Test
    fun `revealTally - sheriff eliminated transitions to BADGE_HANDOVER sub-phase`() {
        val host = player(hostId, 0)
        val sheriff = player("u1", 1)
        val context = ctx(game(sheriff = "u1"), host, sheriff)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(sheriff))

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        val captor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(captor.capture())
        assertThat(captor.allValues).anyMatch { it.subPhase == VotingSubPhase.BADGE_HANDOVER.name }
    }

    // ── handleHunterShoot ─────────────────────────────────────────────────────

    @Test
    fun `handleHunterShoot - hunter shoots non-sheriff target, win checked, goes to night`() {
        val hunter = player(hostId, 0, PlayerRole.HUNTER)
        val target = player("u2", 2)
        val context = ctx(game(VotingSubPhase.HUNTER_SHOOT.name), hunter, target)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(target))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())
        stubLoader(hunter) // after shot
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.handleHunterShoot(req(hostId, ActionType.HUNTER_SHOOT, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(target.alive).isFalse()
        verify(nightOrchestrator).initNight(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `handleHunterShoot - hunter shoots sheriff, transitions to BADGE_HANDOVER`() {
        val hunter = player(hostId, 0, PlayerRole.HUNTER)
        val sheriff = player("u2", 2).also { it.sheriff = true }
        val context = ctx(game(VotingSubPhase.HUNTER_SHOOT.name, sheriff = "u2"), hunter, sheriff)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(sheriff))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        votingPipeline.handleHunterShoot(req(hostId, ActionType.HUNTER_SHOOT, "u2"), context)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(VotingSubPhase.BADGE_HANDOVER.name)
        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `handleHunterShoot - hunter shoots last wolf, game ends with VILLAGER win`() {
        val hunter = player(hostId, 0, PlayerRole.HUNTER)
        val wolf = player("wolf", 1, PlayerRole.WEREWOLF)
        val context = ctx(game(VotingSubPhase.HUNTER_SHOOT.name), hunter, wolf)

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "wolf")).thenReturn(Optional.of(wolf))
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1)).thenReturn(Optional.empty())
        // Don't pre-kill wolf here; the handler sets wolf.alive = false before contextLoader.load is called.
        // Since alivePlayers is computed dynamically, afterCtx will correctly exclude wolf at that point.
        val afterCtx = ctx(game(), hunter, wolf)
        whenever(contextLoader.load(gameId)).thenReturn(afterCtx)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        votingPipeline.handleHunterShoot(req(hostId, ActionType.HUNTER_SHOOT, "wolf"), context)

        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(captor.firstValue.winner).isEqualTo(WinnerSide.VILLAGER)
    }

    @Test
    fun `revealTally - last wolf voted out, game ends with VILLAGER win`() {
        val host = player(hostId, 0)
        val lastWolf = player("wolf", 1, PlayerRole.WEREWOLF)
        val villager = player("v1", 2)
        val context = ctx(game(), host, lastWolf, villager)

        val votes = listOf(vote(hostId, "wolf"), vote("v1", "wolf"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "wolf")).thenReturn(Optional.of(lastWolf))
        // After wolf elimination: only host and villager alive
        stubLoader(host, villager)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
        val captor = argumentCaptor<Game>()
        verify(gameRepository, atLeastOnce()).save(captor.capture())
        assertThat(captor.allValues).anyMatch { it.phase == GamePhase.GAME_OVER }
        assertThat(captor.allValues).anyMatch { it.winner == WinnerSide.VILLAGER }
    }

    @Test
    fun `handleHunterShoot - hunter skips, not sheriff, goes to night`() {
        val hunter = player(hostId, 0, PlayerRole.HUNTER)
        val context = ctx(game(VotingSubPhase.HUNTER_SHOOT.name), hunter)

        stubLoader(hunter)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.handleHunterShoot(req(hostId, ActionType.HUNTER_PASS), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(nightOrchestrator).initNight(any(), any(), anyOrNull(), any())
    }

    @Test
    fun `handleHunterShoot - hunter skips and is sheriff, transitions to BADGE_HANDOVER`() {
        val hunter = player(hostId, 0, PlayerRole.HUNTER)
        val context = ctx(game(VotingSubPhase.HUNTER_SHOOT.name, sheriff = hostId), hunter)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        votingPipeline.handleHunterShoot(req(hostId, ActionType.HUNTER_PASS), context)

        val captor = argumentCaptor<Game>()
        verify(gameRepository).save(captor.capture())
        assertThat(captor.firstValue.subPhase).isEqualTo(VotingSubPhase.BADGE_HANDOVER.name)
        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
    }

    // ── handleBadge ───────────────────────────────────────────────────────────

    @Test
    fun `handleBadge - sheriff passes badge to living player, badge transferred and transitions to VOTE_RESULT`() {
        val sheriff = player(hostId, 0)
        val newSheriff = player("u2", 2)
        val context = ctx(game(VotingSubPhase.BADGE_HANDOVER.name, sheriff = hostId), sheriff, newSheriff)

        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, hostId)).thenReturn(Optional.of(sheriff))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(newSheriff))
        stubLoader(sheriff, newSheriff)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.handleBadge(req(hostId, ActionType.BADGE_PASS, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val gameCaptor = argumentCaptor<Game>()
        verify(gameRepository).save(gameCaptor.capture())
        assertThat(gameCaptor.firstValue.sheriffUserId).isEqualTo("u2")
        // Bug fix: after badge handover, phase should transition to VOTE_RESULT
        assertThat(gameCaptor.firstValue.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    @Test
    fun `handleBadge - sheriff destroys badge, sheriffUserId becomes null and transitions to VOTE_RESULT`() {
        val sheriff = player(hostId, 0)
        val context = ctx(game(VotingSubPhase.BADGE_HANDOVER.name, sheriff = hostId), sheriff)

        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, hostId)).thenReturn(Optional.of(sheriff))
        stubLoader(sheriff)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.handleBadge(req(hostId, ActionType.BADGE_DESTROY), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val gameCaptor = argumentCaptor<Game>()
        verify(gameRepository).save(gameCaptor.capture())
        assertThat(gameCaptor.firstValue.sheriffUserId).isNull()
        // Bug fix: after badge destruction, phase should transition to VOTE_RESULT
        assertThat(gameCaptor.firstValue.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
    }

    // ── Idiot interception ────────────────────────────────────────────────────

    @Test
    fun `revealTally - unrevealed Idiot voted out, survives with canVote=false and idiotRevealed=true`() {
        val host = player(hostId, 0)
        val idiot = player("u1", 1, PlayerRole.IDIOT)
        val wolf = player("u2", 2, PlayerRole.WEREWOLF)
        val context = ctx(game(), host, idiot, wolf)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))
        stubLoader(host, idiot, wolf) // everyone still alive (idiot survives)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        assertThat(idiot.alive).isTrue()          // survived
        assertThat(idiot.canVote).isFalse()        // lost vote
        assertThat(idiot.idiotRevealed).isTrue()   // revealed
        verify(gamePlayerRepository).save(idiot)
    }

    @Test
    fun `revealTally - already-revealed Idiot voted out again, eliminated normally`() {
        val host = player(hostId, 0)
        val idiot = player("u1", 1, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }
        val context = ctx(game(), host, idiot)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))
        stubLoader(host) // after kill: only host alive
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        assertThat(idiot.alive).isFalse() // eliminated this time
        verify(gamePlayerRepository).save(idiot)
    }

    @Test
    fun `submitVote - revealed Idiot with canVote=false cannot vote, gets Rejected`() {
        val idiot = player(hostId, 0, PlayerRole.IDIOT).also { it.canVote = false; it.idiotRevealed = true }
        val context = ctx(game(), idiot)

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        val rejected = result as GameActionResult.Rejected
        assertThat(rejected.reason).contains("voting right")
    }

    @Test
    fun `revealTally - unrevealed Idiot sheriff voted out, survives and keeps sheriff badge`() {
        val host = player(hostId, 0)
        val idiot = player("u1", 1, PlayerRole.IDIOT).also { it.sheriff = true }
        val wolf = player("u2", 2, PlayerRole.WEREWOLF)
        val context = ctx(game(), host, idiot, wolf)

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))
        stubLoader(host, idiot, wolf) // everyone still alive (idiot survives)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        votingPipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Idiot survives and keeps sheriff badge
        assertThat(idiot.alive).isTrue()
        assertThat(idiot.sheriff).isTrue()
        assertThat(idiot.canVote).isFalse()
        assertThat(idiot.idiotRevealed).isTrue()
        verify(gamePlayerRepository).save(idiot)
    }

    @Test
    fun `handleBadge - revealed Idiot sheriff can pass badge despite canVote=false`() {
        val host = player(hostId, 0)
        val idiot = player("u1", 1, PlayerRole.IDIOT).also {
            it.sheriff = true
            it.canVote = false
            it.idiotRevealed = true
            it.alive = true
        }
        val target = player("u2", 2, PlayerRole.VILLAGER)
        // Create game with idiot as sheriff
        val gameWithSheriff = game(VotingSubPhase.BADGE_HANDOVER.name, "u1")
        val context = ctx(gameWithSheriff, host, idiot, target)

        // Idiot sheriff is passing badge - use idiot's userId as actor
        val req = req("u1", ActionType.BADGE_PASS, "u2")

        // Mock game player repository to return players
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u2")).thenReturn(Optional.of(target))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        stubLoader(host, idiot, target)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = votingPipeline.handleBadge(req, context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // Idiot loses sheriff badge but stays alive
        assertThat(idiot.sheriff).isFalse()
        assertThat(idiot.alive).isTrue()
        assertThat(idiot.canVote).isFalse()  // still can't vote
        assertThat(idiot.idiotRevealed).isTrue()  // still revealed
        // Target gets sheriff badge
        assertThat(target.sheriff).isTrue()
    }

    @Test
    fun `submitVote - success, vote saved and broadcast`() {
        val voter = player(hostId, 0)
        val target = player("u2", 2)
        val context = ctx(game(), voter, target)

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(emptyList())
        whenever(voteRepository.save(any<Vote>())).thenAnswer { it.arguments[0] }

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(voteRepository).save(any<Vote>())
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), any())
    }

    @Test
    fun `submitVote - rejected when actor is dead`() {
        val deadVoter = player(hostId, 0).also { it.alive = false }
        val context = ctx(game(), deadVoter)

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Dead")
    }

    @Test
    fun `submitVote - rejected when actor already voted this round`() {
        val voter = player(hostId, 0)
        val context = ctx(game(), voter)

        // Voter already has a vote this round
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(listOf(vote(hostId, "u2")))

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("voted")
    }

    @Test
    fun `submitVote - rejected when not in VOTING sub-phase`() {
        val voter = player(hostId, 0)
        val context = ctx(game(VotingSubPhase.VOTE_RESULT.name), voter)

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `submitVote - rejected when target is dead`() {
        val voter = player(hostId, 0)
        val deadTarget = player("u2", 2).also { it.alive = false }
        val context = ctx(game(), voter, deadTarget)

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(emptyList())

        val result = votingPipeline.submitVote(req(hostId, ActionType.SUBMIT_VOTE, "u2"), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("not found or dead")
    }

    // ── unvote ────────────────────────────────────────────────────────────────

    @Test
    fun `unvote - rejected when not in VOTING phase`() {
        val voter = player(hostId, 0)
        val game = game().also { it.phase = GamePhase.DAY_DISCUSSION }
        val context = ctx(game, voter)

        val result = votingPipeline.unvote(req(hostId, ActionType.VOTING_UNVOTE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not in voting phase")
    }

    @Test
    fun `unvote - rejected when sub-phase is VOTE_RESULT`() {
        val voter = player(hostId, 0)
        val context = ctx(game(VotingSubPhase.VOTE_RESULT.name), voter)

        val result = votingPipeline.unvote(req(hostId, ActionType.VOTING_UNVOTE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Voting is not open")
    }

    @Test
    fun `unvote - rejected when player has no vote to retract`() {
        val voter = player(hostId, 0)
        val context = ctx(game(), voter)

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(emptyList())

        val result = votingPipeline.unvote(req(hostId, ActionType.VOTING_UNVOTE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("No vote to retract")
    }

    @Test
    fun `unvote - success, vote deleted from DB and broadcast sent`() {
        val voter = player(hostId, 0)
        val context = ctx(game(), voter)

        val existingVote = vote(hostId, "u2")
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(listOf(existingVote))

        val result = votingPipeline.unvote(req(hostId, ActionType.VOTING_UNVOTE), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(voteRepository).delete(existingVote)
        verify(stompPublisher).broadcastGame(eq(gameId), any())
    }

    @Test
    fun `unvote - works in RE_VOTING sub-phase`() {
        val voter = player(hostId, 0)
        val context = ctx(game(VotingSubPhase.RE_VOTING.name), voter)

        val existingVote = vote(hostId, "u2")
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(listOf(existingVote))

        val result = votingPipeline.unvote(req(hostId, ActionType.VOTING_UNVOTE), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        verify(voteRepository).delete(existingVote)
    }

    // ── continueToNight ──────────────────────────────────────────────────────

    @Test
    fun `continueToNight - rejected when actor is not host`() {
        val context = ctx(game(VotingSubPhase.VOTE_RESULT.name))

        val result = votingPipeline.continueToNight(req("guest:001", ActionType.VOTING_CONTINUE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only host")
    }

    @Test
    fun `continueToNight - rejected when not in VOTING phase`() {
        val game = game().also { it.phase = GamePhase.DAY_DISCUSSION }
        val context = ctx(game)

        val result = votingPipeline.continueToNight(req(hostId, ActionType.VOTING_CONTINUE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Not in voting phase")
    }

    @Test
    fun `continueToNight - rejected when sub-phase is not VOTE_RESULT`() {
        val context = ctx(game(VotingSubPhase.VOTING.name))

        val result = votingPipeline.continueToNight(req(hostId, ActionType.VOTING_CONTINUE), context)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("VOTE_RESULT")
    }

    @Test
    fun `continueToNight - success, initiates night with correct day number`() {
        val context = ctx(game(VotingSubPhase.VOTE_RESULT.name))

        val result = votingPipeline.continueToNight(req(hostId, ActionType.VOTING_CONTINUE), context)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // dayNumber is 1, so next night should be day 2
        verify(nightOrchestrator).initNight(eq(gameId), eq(2), anyOrNull(), any())
    }

    // ── WinConditionMode ──────────────────────────────────────────────────────

    @Test
    fun `WinConditionChecker CLASSIC - wolves win when wolves outnumber others`() {
        val checker = WinConditionChecker()
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF)
        val villager = player("v1", 3)

        val result = checker.check(
            alivePlayers = listOf(wolf1, wolf2, villager),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(false, false, false),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `WinConditionChecker CLASSIC - wolves do not win when outnumbered by others`() {
        val checker = WinConditionChecker()
        val wolf = player("w1", 1, PlayerRole.WEREWOLF)
        val v1 = player("v1", 2)
        val v2 = player("v2", 3)

        val result = checker.check(
            alivePlayers = listOf(wolf, v1, v2),
            mode = WinConditionMode.CLASSIC,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(false, false, false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `WinConditionChecker HARD_MODE POST_NIGHT - logical branch suppressed while humans remain`() {
        // Post-night the logical branch never fires, so a state that would win post-vote
        // stays at null post-night. This invariant protects mid-game night transitions.
        val checker = WinConditionChecker()
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF)
        val villager = player("v1", 3)

        val result = checker.check(
            alivePlayers = listOf(wolf1, wolf2, villager),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = HardModeCounterplay(false, false, false),
        )
        assertThat(result).isNull()
    }

    @Test
    fun `WinConditionChecker HARD_MODE POST_VOTE - logical branch fires at parity with no counterplay`() {
        // Same 2W+1V board as above but POST_VOTE — logical win should fire.
        val checker = WinConditionChecker()
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF)
        val villager = player("v1", 3)

        val result = checker.check(
            alivePlayers = listOf(wolf1, wolf2, villager),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(false, false, false),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    @Test
    fun `WinConditionChecker HARD_MODE - literal win when all non-wolves eliminated`() {
        val checker = WinConditionChecker()
        val wolf1 = player("w1", 1, PlayerRole.WEREWOLF)
        val wolf2 = player("w2", 2, PlayerRole.WEREWOLF)

        val result = checker.check(
            alivePlayers = listOf(wolf1, wolf2),
            mode = WinConditionMode.HARD_MODE,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay(false, false, false),
        )
        assertThat(result).isEqualTo(WinnerSide.WEREWOLF)
    }

    // ── Action log recording ─────────────────────────────────────────────────

    @Mock lateinit var actionLogService: com.werewolf.service.ActionLogService

    private fun makeVotingPipelineWithLog(handlers: List<RoleHandler> = emptyList()) = VotingPipeline(
        handlers = handlers,
        voteRepository = voteRepository,
        gameRepository = gameRepository,
        gamePlayerRepository = gamePlayerRepository,
        eliminationHistoryRepository = eliminationHistoryRepository,
        winConditionChecker = winConditionChecker,
        stompPublisher = stompPublisher,
        contextLoader = contextLoader,
        nightOrchestrator = nightOrchestrator,
        actionLogService = actionLogService,
    )

    @Test
    fun `revealTally - records VOTE_RESULT when a player is eliminated`() {
        val target = player("u1", 1, PlayerRole.VILLAGER)
        val context = GameContext(game(VotingSubPhase.VOTING.name), room(), listOf(player(hostId, 0), target))
        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(target))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(eliminationHistoryRepository.save(any<EliminationHistory>())).thenAnswer { it.arguments[0] }
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        makeVotingPipelineWithLog().revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        verify(actionLogService).recordVoteResult(
            eq(gameId), eq(1), any(), any(), isNull(), eq("u1"), eq(PlayerRole.VILLAGER)
        )
    }

    @Test
    fun `handleHunterShoot - records HUNTER_SHOT when hunter shoots`() {
        val hunter = player("hunter1", 1, PlayerRole.HUNTER).also { it.alive = false }
        val target = player("u1", 2, PlayerRole.VILLAGER)
        val context = GameContext(
            game(VotingSubPhase.HUNTER_SHOOT.name), room(), listOf(player(hostId, 0), hunter, target)
        )
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(target))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, 1))
            .thenReturn(java.util.Optional.empty())
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val req = GameActionRequest(gameId = gameId, actorUserId = "hunter1", actionType = ActionType.HUNTER_SHOOT, targetUserId = "u1")
        makeVotingPipelineWithLog().handleHunterShoot(req, context)

        verify(actionLogService).recordHunterShot(gameId, 1, "hunter1", "u1")
    }

    @Test
    fun `revealTally - records IDIOT_REVEAL when idiot survives first elimination`() {
        val idiot = player("u1", 1, PlayerRole.IDIOT)
        val context = GameContext(game(VotingSubPhase.VOTING.name), room(), listOf(player(hostId, 0), idiot))
        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(idiot))
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        makeVotingPipelineWithLog().revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        verify(actionLogService).recordIdiotReveal(gameId, 1, "u1")
    }
}
