package com.werewolf.unit.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.night.NightWaitingScheduler
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.EliminationModifier
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class HookInvocationTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var nightPhaseRepository: NightPhaseRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var eliminationHistoryRepository: EliminationHistoryRepository
    @Mock lateinit var winConditionChecker: WinConditionChecker
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var contextLoader: GameContextLoader
    @Mock lateinit var nightWaitingScheduler: NightWaitingScheduler
    @Mock lateinit var audioService: com.werewolf.service.AudioService
    @Mock lateinit var nightOrchestrator: NightOrchestrator

    private val gameId = 1
    private val hostId = "host:001"

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun game(phase: GamePhase = GamePhase.NIGHT, subPhase: String? = null) =
        Game(roomId = 1, hostUserId = hostId).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
            it.phase = phase
            it.subPhase = subPhase
            it.dayNumber = 1
        }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 6)

    private fun player(userId: String, seat: Int, role: PlayerRole = PlayerRole.VILLAGER, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = role).also { it.alive = alive }

    private fun ctx(vararg players: GamePlayer, nightPhase: NightPhase? = null) =
        GameContext(game(), room(), players.toList(), nightPhase = nightPhase)

    private fun nightPhase() = NightPhase(gameId = gameId, dayNumber = 1)

    private fun vote(voter: String, target: String?) =
        Vote(gameId = gameId, voteContext = VoteContext.ELIMINATION, dayNumber = 1, voterUserId = voter, targetUserId = target)

    private fun req(actorId: String, actionType: ActionType, target: String? = null) =
        GameActionRequest(gameId = gameId, actorUserId = actorId, actionType = actionType, targetUserId = target)

    /** Stub handler whose onDayEnter produces a sentinel event. */
    private fun dayEnterHandler(role: PlayerRole) = object : RoleHandler {
        override val role = role
        override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
        override fun handle(action: GameActionRequest, context: GameContext) = GameActionResult.Success()
        override fun onDayEnter(context: GameContext) =
            listOf(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, "HOOK_FIRED_$role"))
    }

    /** Stub handler whose onEliminationPending cancels elimination with an extra event. */
    private fun cancellingEliminationHandler(role: PlayerRole) = object : RoleHandler {
        override val role = role
        override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
        override fun handle(action: GameActionRequest, context: GameContext) = GameActionResult.Success()
        override fun onEliminationPending(context: GameContext, targetId: String) =
            EliminationModifier(
                cancelled = true,
                extraEvents = listOf(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, "VETO_$role"))
            )
    }

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
    )

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
    )

    // ── onDayEnter ───────────────────────────────────────────────────────────

    @Test
    fun `resolveNightKills - onDayEnter called for each alive player's role handler on DAY transition`() {
        val wolf = player("w1", 1, PlayerRole.WEREWOLF)
        val villager = player("v1", 2, PlayerRole.VILLAGER)
        val np = nightPhase()
        val initialCtx = ctx(wolf, villager, nightPhase = np)

        val wolfHandler = dayEnterHandler(PlayerRole.WEREWOLF)
        val villagerHandler = dayEnterHandler(PlayerRole.VILLAGER)
        val orchestrator = makeOrchestrator(listOf(wolfHandler, villagerHandler))

        whenever(contextLoader.load(gameId)).thenReturn(initialCtx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        // Mock audioService to avoid NullPointerException
        val mockAudioSequence = AudioSequence(
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
            eq(initialCtx.room)
        )).thenReturn(mockAudioSequence)

        orchestrator.resolveNightKills(initialCtx, np)

        // Verify onDayEnter events were broadcast for both alive role handlers
        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeast(1)).broadcastGame(eq(gameId), eventCaptor.capture())
        val hookEvents = eventCaptor.allValues.filterIsInstance<DomainEvent.PhaseChanged>()
            .filter { it.subPhase?.startsWith("HOOK_FIRED_") == true }
        assertThat(hookEvents.map { it.subPhase }).containsExactlyInAnyOrder(
            "HOOK_FIRED_WEREWOLF", "HOOK_FIRED_VILLAGER"
        )
    }

    @Test
    fun `resolveNightKills - onDayEnter NOT called when game ends (win condition met)`() {
        val wolf = player("w1", 1, PlayerRole.WEREWOLF)
        val np = nightPhase()
        val initialCtx = ctx(wolf, nightPhase = np)

        val wolfHandler = dayEnterHandler(PlayerRole.WEREWOLF)
        val orchestrator = makeOrchestrator(listOf(wolfHandler))

        whenever(contextLoader.load(gameId)).thenReturn(initialCtx)
        whenever(winConditionChecker.check(any(), any())).thenReturn(WinnerSide.WEREWOLF)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        orchestrator.resolveNightKills(initialCtx, np)

        // No HOOK_FIRED events — game ended before DAY transition
        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeast(1)).broadcastGame(eq(gameId), eventCaptor.capture())
        val hookEvents = eventCaptor.allValues.filterIsInstance<DomainEvent.PhaseChanged>()
            .filter { it.subPhase?.startsWith("HOOK_FIRED_") == true }
        assertThat(hookEvents).isEmpty()
    }

    // ── onEliminationPending ──────────────────────────────────────────────────

    @Test
    fun `eliminateByVote - onEliminationPending called before player alive set to false`() {
        val host = player(hostId, 0)
        val target = player("u1", 1, PlayerRole.VILLAGER)
        val context = GameContext(
            game(GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name), room(), listOf(host, target)
        )

        val cancellingHandler = cancellingEliminationHandler(PlayerRole.VILLAGER)
        val pipeline = makeVotingPipeline(listOf(cancellingHandler))

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(target))
        // contextLoader needed for afterElimination after hook cancels
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        pipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Hook cancelled elimination: player should still be alive
        assertThat(target.alive).isTrue()
        // Veto event was broadcast
        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeast(1)).broadcastGame(eq(gameId), eventCaptor.capture())
        val vetoEvent = eventCaptor.allValues.filterIsInstance<DomainEvent.PhaseChanged>()
            .firstOrNull { it.subPhase?.startsWith("VETO_") == true }
        assertThat(vetoEvent).isNotNull()
    }

    @Test
    fun `eliminateByVote - onEliminationPending returning null does not cancel elimination`() {
        val host = player(hostId, 0)
        val target = player("u1", 1, PlayerRole.VILLAGER)
        val context = GameContext(
            game(GamePhase.DAY_VOTING, VotingSubPhase.VOTING.name), room(), listOf(host, target)
        )

        // A handler that does NOT cancel
        val passThroughHandler = object : RoleHandler {
            override val role = PlayerRole.VILLAGER
            override fun acceptedActions(phase: GamePhase, subPhase: String?) = emptySet<ActionType>()
            override fun handle(action: GameActionRequest, ctx: GameContext) = GameActionResult.Success()
            override fun onEliminationPending(ctx: GameContext, targetId: String): EliminationModifier? = null
        }
        val pipeline = makeVotingPipeline(listOf(passThroughHandler))

        val votes = listOf(vote("u2", "u1"), vote("u3", "u1"))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, 1))
            .thenReturn(votes)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, "u1")).thenReturn(Optional.of(target))
        whenever(contextLoader.load(gameId)).thenReturn(context)
        whenever(winConditionChecker.check(any(), any())).thenReturn(null)

        pipeline.revealTally(req(hostId, ActionType.VOTING_REVEAL_TALLY), context)

        // Handler returned null → elimination proceeds
        assertThat(target.alive).isFalse()
    }
}
