package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.ActionLogService
import com.werewolf.service.SelfDestructService
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
class SelfDestructServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var sheriffCandidateRepository: SheriffCandidateRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @Mock lateinit var winConditionChecker: WinConditionChecker

    private lateinit var selfDestructService: SelfDestructService

    @BeforeEach
    fun setUp() {
        selfDestructService = SelfDestructService(
            gameRepository,
            gamePlayerRepository,
            sheriffCandidateRepository,
            sheriffElectionRepository,
            userRepository,
            stompPublisher,
            actionLogService,
            nightOrchestrator,
            winConditionChecker,
        )
    }

    private val gameId = 10
    private val electionId = 20
    private val hostId = "host:001"
    private val wolfId = "wolf:001"
    private val villageId = "village:001"

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        subPhase: String = DaySubPhase.RESULT_REVEALED.name,
        sheriffUserId: String? = null,
    ) = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = phase
        it.subPhase = subPhase
        it.sheriffUserId = sheriffUserId
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = true)

    private fun wolfPlayer(userId: String = wolfId, seat: Int = 1, alive: Boolean = true) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.WEREWOLF, alive = alive)

    private fun villagePlayer(userId: String = villageId, seat: Int = 2) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)

    private fun election(
        subPhase: ElectionSubPhase = ElectionSubPhase.SIGNUP,
    ) = SheriffElection(gameId = gameId, subPhase = subPhase).also {
        val f = SheriffElection::class.java.getDeclaredField("id"); f.isAccessible = true; f.set(it, electionId)
    }

    private fun context(
        game: Game = game(),
        players: List<GamePlayer> = listOf(wolfPlayer(), villagePlayer()),
        election: SheriffElection? = null,
    ) = GameContext(game, room(), players, election = election)

    private fun req(actorUserId: String = wolfId) =
        GameActionRequest(gameId, actorUserId, ActionType.WOLF_SELF_DESTRUCT)

    // ── Case 1: Wolf during SHERIFF_ELECTION/SIGNUP → election aborts ──────────

    @Test
    fun `wolf during SHERIFF_ELECTION SIGNUP aborts election and transitions to DAY_DISCUSSION RESULT_HIDDEN`() {
        val elec = election(ElectionSubPhase.SIGNUP)
        val ctx = context(
            game = game(phase = GamePhase.SHERIFF_ELECTION, subPhase = null.toString()),
            players = listOf(wolfPlayer(), villagePlayer()),
            election = elec,
        )
        ctx.game.phase = GamePhase.SHERIFF_ELECTION
        ctx.game.subPhase = null

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }

    // ── Case 2: Wolf during SHERIFF_ELECTION/VOTING aborts election ─────────────

    @Test
    fun `wolf during SHERIFF_ELECTION VOTING aborts election and clears candidates`() {
        val elec = election(ElectionSubPhase.VOTING)
        val wolfCandidate = SheriffCandidate(electionId = electionId, userId = wolfId, status = CandidateStatus.RUNNING)
        val ctx = context(
            game = game(phase = GamePhase.SHERIFF_ELECTION, subPhase = null.toString()),
            players = listOf(wolfPlayer(), villagePlayer()),
            election = elec,
        )
        ctx.game.phase = GamePhase.SHERIFF_ELECTION
        ctx.game.subPhase = null

        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(wolfCandidate))
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }

    // ── Case 3: Wolf-sheriff self-destructs → badge destroyed ───────────────────

    @Test
    fun `wolf-sheriff in DAY_DISCUSSION RESULT_REVEALED destroys badge and broadcasts BadgeHandover`() {
        val sheriffWolf = wolfPlayer().also { it.sheriff = true }
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name, sheriffUserId = wolfId),
            players = listOf(sheriffWolf, villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(sheriffWolf))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.sheriffUserId).isNull()
        // Badge burned on the wolf's GamePlayer row too — otherwise the ⭐ stays on
        // every player slot because PlayerSlot reads GamePlayer.sheriff, not game.sheriffUserId.
        assertThat(sheriffWolf.sheriff).isFalse()

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val handover = captor.allValues.filterIsInstance<DomainEvent.BadgeHandover>()
        assertThat(handover).isNotEmpty()
        assertThat(handover.first().fromUserId).isEqualTo(wolfId)
        assertThat(handover.first().toUserId).isNull()
    }

    // ── Case 4: Wolf during DAY_VOTING/VOTING → transitions to VOTE_RESULT ─────

    @Test
    fun `wolf during DAY_VOTING transitions to DAY_VOTING VOTE_RESULT`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_VOTING, subPhase = VotingSubPhase.VOTING.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_VOTING)
        assertThat(ctx.game.subPhase).isEqualTo(VotingSubPhase.VOTE_RESULT.name)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }

    // ── Case 5: Non-wolf attempts → Rejected ───────────────────────────────────

    @Test
    fun `non-wolf gets rejected with message`() {
        val ctx = context(
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(villageId), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only werewolves")
        verify(gameRepository, never()).save(any<Game>())
    }

    // ── Case 6: Dead wolf attempts → Rejected ──────────────────────────────────

    @Test
    fun `dead wolf gets rejected`() {
        val ctx = context(
            players = listOf(wolfPlayer(alive = false), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Dead players")
        verify(gameRepository, never()).save(any<Game>())
    }

    // ── Case 7: NIGHT phase rejects ─────────────────────────────────────────────

    @Test
    fun `NIGHT phase gets rejected with phase-not-allowed message`() {
        val ctx = context(
            game = game(phase = GamePhase.NIGHT, subPhase = NightSubPhase.WEREWOLF_PICK.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("not allowed")
        verify(gameRepository, never()).save(any<Game>())
    }

    // ── Case 8: Last wolf self-destructs → GameOver villager win ───────────────

    @Test
    fun `last wolf self-destruct triggers villager win`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_REVEALED.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(WinnerSide.VILLAGER)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.GAME_OVER)
        assertThat(ctx.game.winner).isEqualTo(WinnerSide.VILLAGER)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        val gameOver = captor.allValues.filterIsInstance<DomainEvent.GameOver>()
        assertThat(gameOver).isNotEmpty()
        assertThat(gameOver.first().winner).isEqualTo(WinnerSide.VILLAGER)
    }

    // ── Case 9: Wolf during DAY_DISCUSSION RESULT_HIDDEN → pending kills applied ─

    @Test
    fun `wolf during DAY_DISCUSSION RESULT_HIDDEN sets daySkipVoting true`() {
        val ctx = context(
            game = game(phase = GamePhase.DAY_DISCUSSION, subPhase = DaySubPhase.RESULT_HIDDEN.name),
            players = listOf(wolfPlayer(), villagePlayer()),
        )

        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, wolfId))
            .thenReturn(Optional.of(wolfPlayer()))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(gameRepository.save(any<Game>())).thenReturn(ctx.game)
        whenever(gamePlayerRepository.save(any<GamePlayer>())).thenAnswer { it.arguments[0] }
        whenever(winConditionChecker.check(any(), any(), any(), any())).thenReturn(null)

        val result = selfDestructService.selfDestruct(req(), ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.daySkipVoting).isTrue()
    }
}
