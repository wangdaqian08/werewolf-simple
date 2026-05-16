package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.config.GameTimingProperties
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.timer.HostTimerService
import com.werewolf.model.*
import com.werewolf.repository.*
import com.werewolf.service.ActionLogService
import com.werewolf.service.SheriffService
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SheriffServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var sheriffCandidateRepository: SheriffCandidateRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var actionLogService: ActionLogService
    @Mock lateinit var hostTimerService: HostTimerService
    private lateinit var sheriffService: SheriffService

    @BeforeEach
    fun setUp() {
        sheriffService = SheriffService(
            gameRepository, gamePlayerRepository, sheriffElectionRepository,
            sheriffCandidateRepository, voteRepository, userRepository,
            stompPublisher, CoroutineScope(Dispatchers.Default),
            GameTimingProperties(),
            actionLogService,
            hostTimerService,
        )
    }

    private val gameId = 10
    private val electionId = 20
    private val hostId = "host:001"
    private val guestId = "guest:001"

    private fun game() = Game(roomId = 1, hostUserId = hostId).also {
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, gameId)
        it.phase = GamePhase.SHERIFF_ELECTION
    }

    private fun room() = Room(roomCode = "ABCD", hostUserId = hostId, totalPlayers = 4, hasSheriff = true)

    private fun player(userId: String, seat: Int = 0) =
        GamePlayer(gameId = gameId, userId = userId, seatIndex = seat, role = PlayerRole.VILLAGER)

    private fun election(
        subPhase: ElectionSubPhase = ElectionSubPhase.SIGNUP,
        speakingOrder: String? = null,
        currentSpeakerIdx: Int = 0,
    ) = SheriffElection(gameId = gameId, subPhase = subPhase, speakingOrder = speakingOrder, currentSpeakerIdx = currentSpeakerIdx).also {
        val f = SheriffElection::class.java.getDeclaredField("id"); f.isAccessible = true; f.set(it, electionId)
    }

    private fun context(
        election: SheriffElection? = election(),
        players: List<GamePlayer> = listOf(player(hostId, 0), player(guestId, 1)),
    ): GameContext {
        val game = game()
        return GameContext(game, room(), players, election = election)
    }

    // ── Group 1: pass() action ─────────────────────────────────────────────────

    @Test
    fun `pass - inserts QUIT candidate when player has no prior record`() {
        val ctx = context()
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(emptyList())
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val savedCaptor = argumentCaptor<SheriffCandidate>()
        verify(sheriffCandidateRepository).save(savedCaptor.capture())
        assertThat(savedCaptor.firstValue.status).isEqualTo(CandidateStatus.QUIT)
        assertThat(savedCaptor.firstValue.userId).isEqualTo(guestId)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), eventCaptor.capture())
        val event = eventCaptor.firstValue as DomainEvent.PhaseChanged
        assertThat(event.phase).isEqualTo(GamePhase.SHERIFF_ELECTION)
        assertThat(event.subPhase).isEqualTo(ElectionSubPhase.SIGNUP.name)
    }

    @Test
    fun `pass - idempotent when player already QUIT`() {
        val ctx = context()
        val existingCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(existingCandidate))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // No new save for existing QUIT record
        verify(sheriffCandidateRepository, never()).save(any<SheriffCandidate>())
    }

    @Test
    fun `pass - rejected when not in SIGNUP sub-phase`() {
        val ctx = context(election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = guestId))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Sign-up period is over")
        verify(sheriffCandidateRepository, never()).save(any<SheriffCandidate>())
    }

    // ── Group 2: buildState() hasPassed and canVote ────────────────────────────

    @Test
    fun `buildState - hasPassed true when player has QUIT record not in speaking order`() {
        val myPlayer = player(guestId, 1)
        val election = election(subPhase = ElectionSubPhase.SIGNUP, speakingOrder = "other:001,other:002")
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)

        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(election))
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, 1, guestId
        )).thenReturn(Optional.empty())

        val game = game()
        val players = listOf(player(hostId, 0), myPlayer)
        val state = sheriffService.buildState(gameId, game, myPlayer, players)

        assertThat(state["hasPassed"]).isEqualTo(true)
        assertThat(state["canVote"]).isEqualTo(true)
    }

    @Test
    fun `buildState - hasPassed false and canVote false when player QUIT in speaking order`() {
        val myPlayer = player(guestId, 1)
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = "$guestId,other:001")
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)

        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(election))
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, 1, guestId
        )).thenReturn(Optional.empty())

        val game = game()
        val players = listOf(player(hostId, 0), myPlayer)
        val state = sheriffService.buildState(gameId, game, myPlayer, players)

        assertThat(state["hasPassed"]).isEqualTo(false)
        assertThat(state["canVote"]).isEqualTo(false)
    }

    @Test
    fun `buildState - canVote true when player is not a candidate`() {
        val myPlayer = player(guestId, 1)
        val election = election(subPhase = ElectionSubPhase.SIGNUP)

        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(election))
        // No candidate record for guestId
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(emptyList())
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, 1, guestId
        )).thenReturn(Optional.empty())

        val game = game()
        val players = listOf(player(hostId, 0), myPlayer)
        val state = sheriffService.buildState(gameId, game, myPlayer, players)

        assertThat(state["hasPassed"]).isEqualTo(false)
        assertThat(state["canVote"]).isEqualTo(true)
    }

    // ── Group 2b: signUp() action ────────────────────────────────────────────

    @Test
    fun `signUp - success when alive player signs up for first time`() {
        val ctx = context()
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(emptyList())
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val captor = argumentCaptor<SheriffCandidate>()
        verify(sheriffCandidateRepository).save(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo(guestId)
        assertThat(captor.firstValue.status).isEqualTo(CandidateStatus.RUNNING)
    }

    @Test
    fun `signUp - rejected when player is dead`() {
        val deadPlayer = player(guestId, 1).also { it.alive = false }
        val ctx = context(players = listOf(player(hostId, 0), deadPlayer))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Dead")
    }

    @Test
    fun `signUp - re-signup after quit resets status to RUNNING`() {
        val ctx = context()
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(quitCandidate.status).isEqualTo(CandidateStatus.RUNNING)
    }

    @Test
    fun `signUp - rejected when not in SIGNUP sub-phase`() {
        val ctx = context(election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = guestId))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Sign-up period is over")
    }

    // ── Group 2c: advanceSpeech() action ──────────────────────────────────────

    @Test
    fun `advanceSpeech - advances to next RUNNING candidate, skipping QUIT`() {
        // Speaking order: guest (idx 0), quit:001 (idx 1, QUIT), other:001 (idx 2, RUNNING)
        val quitId = "quit:001"
        val nextId = "other:001"
        val speakingOrder = "$guestId,$quitId,$nextId"
        val election = election(
            subPhase = ElectionSubPhase.SPEECH,
            speakingOrder = speakingOrder,
            currentSpeakerIdx = 0, // currently on guestId
        )
        val ctx = context(election = election)

        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = quitId, status = CandidateStatus.QUIT),
            SheriffCandidate(electionId = electionId, userId = nextId, status = CandidateStatus.RUNNING),
        )
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(candidates)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_ADVANCE_SPEECH)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(election.currentSpeakerIdx).isEqualTo(2) // skipped idx 1 (QUIT)
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
    }

    @Test
    fun `advanceSpeech - transitions to VOTING when all candidates have spoken`() {
        val speakingOrder = guestId
        val election = election(
            subPhase = ElectionSubPhase.SPEECH,
            speakingOrder = speakingOrder,
            currentSpeakerIdx = 0, // currently on guestId (last one)
        )
        val ctx = context(election = election)

        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING),
        )
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(candidates)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_ADVANCE_SPEECH)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.VOTING)
    }

    @Test
    fun `advanceSpeech - transitions to VOTING when remaining candidates are all QUIT`() {
        val quitId = "quit:001"
        val speakingOrder = "$guestId,$quitId"
        val election = election(
            subPhase = ElectionSubPhase.SPEECH,
            speakingOrder = speakingOrder,
            currentSpeakerIdx = 0,
        )
        val ctx = context(election = election)

        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = quitId, status = CandidateStatus.QUIT),
        )
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(candidates)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_ADVANCE_SPEECH)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(election.subPhase).isEqualTo(ElectionSubPhase.VOTING)
    }

    @Test
    fun `advanceSpeech - rejected when actor is not host`() {
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = guestId)
        val ctx = context(election = election)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_ADVANCE_SPEECH)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("host")
    }

    // ── Group 3: quitCampaign() auto-night ────────────────────────────────────

    @Test
    fun `quitCampaign - broadcasts SPEECH update when running candidates remain`() {
        val otherCandidateId = "other:001"
        val speakingOrder = "$guestId,$otherCandidateId"
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = speakingOrder)
        val ctx = context(election = election)

        val guestCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        val otherCandidate = SheriffCandidate(electionId = electionId, userId = otherCandidateId, status = CandidateStatus.RUNNING)

        // First call returns the candidate list; second call (after save) returns updated list
        whenever(sheriffCandidateRepository.findByElectionId(electionId))
            .thenReturn(listOf(guestCandidate, otherCandidate))
            .thenReturn(listOf(
                SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT),
                otherCandidate,
            ))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_QUIT_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), eventCaptor.capture())
        val event = eventCaptor.firstValue as DomainEvent.PhaseChanged
        assertThat(event.subPhase).isEqualTo(ElectionSubPhase.SPEECH.name)

    }

    @Test
    fun `quitCampaign - auto-advances to DAY_DISCUSSION when last running candidate quits`() {
        // Updated: old behavior was SHERIFF_ELECTION/RESULT (a dead-end — nothing
        // for the host to dismiss on the RESULT screen when there's no winner).
        // Fixed behavior mirrors startSpeech's empty-candidates branch: advance
        // directly to DAY_DISCUSSION/RESULT_HIDDEN. See quitCampaign_lastCandidateInSpeech
        // test for the canonical regression test covering game 18 / room 22.
        val speakingOrder = guestId
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = speakingOrder)
        val ctx = context(election = election)

        val guestCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)

        whenever(sheriffCandidateRepository.findByElectionId(electionId))
            .thenReturn(listOf(guestCandidate))
            .thenReturn(listOf(
                SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT),
            ))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_QUIT_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `quitCampaign_lastCandidateInSpeech_advancesToDayDiscussionRESULT_HIDDEN`() {
        // Regression: game 18 / room 22 (2026-05-09) got stuck 14+ hours in
        // SHERIFF_ELECTION/RESULT because all 8 candidates quit during SPEECH.
        // The quitCampaign path landed on ELECTION/RESULT (waiting for host to
        // click 显示结果), but the host screen showed an empty winner card and
        // empty tally — a dead-end with nothing to dismiss.
        //
        // Fix: mirror startSpeech's empty-candidates branch — when the last
        // running candidate quits during SPEECH, auto-advance directly to
        // DAY_DISCUSSION/RESULT_HIDDEN (same as SHERIFF_END_RESULT would do).
        val speakingOrder = guestId
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = speakingOrder)
        val ctx = context(election = election)

        val guestCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)

        whenever(sheriffCandidateRepository.findByElectionId(electionId))
            .thenReturn(listOf(guestCandidate))
            .thenReturn(listOf(
                SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT),
            ))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_QUIT_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // Game phase must advance — NOT stay in SHERIFF_ELECTION/RESULT
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val phaseChanged = captor.firstValue as DomainEvent.PhaseChanged
        assertThat(phaseChanged.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(phaseChanged.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    // ── Group 4: revealResult() empty votes / all-abstain ────────────────────────

    @Test
    fun `revealResult - all abstain with running candidates transitions to TIED`() {
        val candA = "cand:001"
        val candB = "cand:002"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = electionObj)
        // All voters abstained (targetUserId = null)
        val votes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = null),
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = hostId, targetUserId = null),
        )
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(votes)
        // Running candidates exist
        val runningA = SheriffCandidate(electionId = electionId, userId = candA, status = CandidateStatus.RUNNING)
        val runningB = SheriffCandidate(electionId = electionId, userId = candB, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningA, runningB))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.TIED)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(ElectionSubPhase.TIED.name)
    }

    @Test
    fun `revealResult - no votes and no running candidates transitions to RESULT with auto-advance`() {
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "cand:001")
        val ctx = context(election = electionObj)
        // No votes cast at all
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(emptyList())
        // No running candidates (all quit)
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = "cand:001", status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.RESULT)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(ElectionSubPhase.RESULT.name)
        // Should NOT call startNightPhase directly — auto-advance is scheduled asynchronously
    }

    @Test
    fun `revealResult - no votes with running candidates transitions to TIED`() {
        val candA = "cand:001"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = candA)
        val ctx = context(election = electionObj)
        // No votes cast
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(emptyList())
        // Running candidates exist
        val runningA = SheriffCandidate(electionId = electionId, userId = candA, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningA))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.TIED)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(ElectionSubPhase.TIED.name)
    }

    // ── Group 5: vote() — self-vote and other vote validations ────────────────

    @Test
    fun `vote - rejected when player votes for themselves`() {
        // Voters cannot vote for themselves. hostId (not a candidate) votes for hostId.
        // Self-vote guard fires before the "target not running" guard.
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = guestId)
        val ctx = context(election = election)
        val runningGuest = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningGuest))

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_VOTE, targetUserId = hostId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Cannot vote for yourself")
        verify(voteRepository, never()).save(any<Vote>())
    }

    @Test
    fun `vote - rejected when actor quit during speech (forfeited vote)`() {
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "$guestId,other:001")
        val ctx = context(election = election)
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_VOTE, targetUserId = "other:001")
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("quit")
        verify(voteRepository, never()).save(any<Vote>())
    }

    @Test
    fun `vote - rejected when target is not a running candidate`() {
        val targetId = "other:001"
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = targetId)
        val ctx = context(election = election)
        val quitTarget = SheriffCandidate(electionId = electionId, userId = targetId, status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitTarget))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_VOTE, targetUserId = targetId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("not a running candidate")
        verify(voteRepository, never()).save(any<Vote>())
    }

    @Test
    fun `vote - succeeds and broadcasts VOTING update`() {
        val targetId = "other:001"
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = targetId)
        val ctx = context(election = election)
        val runningTarget = SheriffCandidate(electionId = electionId, userId = targetId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningTarget))
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, 1, guestId
        )).thenReturn(Optional.empty())
        whenever(voteRepository.save(any<Vote>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_VOTE, targetUserId = targetId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(ElectionSubPhase.VOTING.name)
    }

    // ── Group 6: buildState() — allVoted and voteProgress ────────────────────

    // Shared setup helper to reduce boilerplate across buildState tests.
    private fun setupBuildState(
        electionObj: SheriffElection,
        candidates: List<SheriffCandidate>,
        allVotes: List<Vote>,
        myPlayer: GamePlayer,
    ) {
        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(electionObj))
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(candidates)
        whenever(userRepository.findAllById(any())).thenReturn(emptyList())
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(allVotes)
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, 1, myPlayer.userId
        )).thenReturn(Optional.empty())
    }

    @Test
    fun `buildState - allVoted false when submitted votes are fewer than eligible voters`() {
        // 4 alive players: hostId (no record), guestId (no record), candId (RUNNING), thirdId (no record).
        // RUNNING candidate (candId) cannot vote → eligible voters = 4 - 1 = 3.
        // Submitted = 1 (guestId voted for candId). allVoted = false (1 < 3).
        // voteProgress.voted = 1 submitted + 1 running (auto-counted) = 2.
        val myPlayer = player(guestId, 1)
        val candId = "cand:001"
        val thirdId = "other:002"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = candId)
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = candId, status = CandidateStatus.RUNNING)
        val submittedVotes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = candId),
        )
        setupBuildState(electionObj, listOf(runningCandidate), submittedVotes, myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player(candId, 2), player(thirdId, 3))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        assertThat(state["allVoted"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val vp = state["voteProgress"] as Map<String, Int>
        assertThat(vp["voted"]).isEqualTo(2)   // 1 submitted + 1 running candidate auto-counted
        assertThat(vp["total"]).isEqualTo(4)   // 4 alive players
    }

    @Test
    fun `buildState - allVoted true when all eligible voters have submitted a vote`() {
        val myPlayer = player(guestId, 1)
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "cand:001")
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = "cand:001", status = CandidateStatus.RUNNING)
        val submittedVotes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = hostId, targetUserId = "cand:001"),
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = "cand:001"),
        )
        setupBuildState(electionObj, listOf(runningCandidate), submittedVotes, myPlayer)

        val players = listOf(player(hostId, 0), myPlayer)  // 2 players, 2 votes
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        assertThat(state["allVoted"]).isEqualTo(true)
    }

    @Test
    fun `buildState - allVoted true when eligibleVoterCount is zero (all players are speech quitters)`() {
        // Bug: `submittedVoteCount >= eligibleVoterCount` is always false when eligibleVoterCount == 0
        // because 0 >= 0 is true but the logic was written as `eligibleVoterCount > 0 && ...`.
        // This caused the game to get stuck permanently when every candidate quit during speech.
        // Fix: allVoted = (eligibleVoterCount == 0) || (submittedVoteCount >= eligibleVoterCount)
        val myPlayer = player(guestId, 1)
        // Both players are in the speaking order and both QUIT → 0 eligible voters
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "$hostId,$guestId")
        val hostQuit = SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.QUIT)
        val guestQuit = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT)
        setupBuildState(electionObj, listOf(hostQuit, guestQuit), emptyList(), myPlayer)

        val players = listOf(player(hostId, 0), myPlayer)
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        assertThat(state["allVoted"]).isEqualTo(true)
        @Suppress("UNCHECKED_CAST")
        val vp = state["voteProgress"] as Map<String, Int>
        assertThat(vp["voted"]).isEqualTo(2)   // 0 submitted + 2 speech quitters
        assertThat(vp["total"]).isEqualTo(2)
    }

    @Test
    fun `buildState - speech quitters are counted as already voted in voteProgress`() {
        // Bug: voteProgress.voted only counted actual DB submissions, not speech quitters.
        // Displayed count was lower than expected (e.g., "0/8" instead of "1/8" with 1 quitter).
        // Fix: voteProgress.voted = submittedVoteCount + speechQuitters.size
        val myPlayer = player(guestId, 1)
        val quitterId = "quitter:001"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING,
            speakingOrder = "cand:001,$quitterId")
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = "cand:001", status = CandidateStatus.RUNNING)
        val quitterCandidate = SheriffCandidate(electionId = electionId, userId = quitterId, status = CandidateStatus.QUIT)
        val submittedVotes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = "cand:001"),
        )
        setupBuildState(electionObj, listOf(runningCandidate, quitterCandidate), submittedVotes, myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player(quitterId, 2))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        @Suppress("UNCHECKED_CAST")
        val vp = state["voteProgress"] as Map<String, Int>
        assertThat(vp["voted"]).isEqualTo(2)   // 1 submitted + 1 quitter auto-counted
        assertThat(vp["total"]).isEqualTo(3)
    }

    // ── Group 7: buildState() / buildResult() — QUIT candidates excluded ───────

    @Test
    fun `buildState - QUIT candidates are excluded from tally in RESULT sub-phase`() {
        // Bug: buildResult() iterated ALL candidates regardless of status,
        // so QUIT candidates appeared as vote columns with 0 votes in the result screen.
        // Fix: filter to status == RUNNING before building the tally array.
        val runningId = "cand:running"
        val quitId = "cand:quit"
        val myPlayer = player(guestId, 1)

        val electionObj = election(subPhase = ElectionSubPhase.RESULT,
            speakingOrder = "$runningId,$quitId")
        val running = SheriffCandidate(electionId = electionId, userId = runningId, status = CandidateStatus.RUNNING)
        val quit = SheriffCandidate(electionId = electionId, userId = quitId, status = CandidateStatus.QUIT)
        // One vote for running candidate; one stray vote that targeted the quit candidate
        val votes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = runningId),
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = hostId, targetUserId = quitId),
        )
        setupBuildState(electionObj, listOf(running, quit), votes, myPlayer)

        val game = game().also { it.sheriffUserId = runningId }
        val state = sheriffService.buildState(gameId, game, myPlayer, listOf(player(hostId, 0), myPlayer))

        @Suppress("UNCHECKED_CAST")
        val result = state["result"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val tally = result["tally"] as List<Map<String, Any?>>

        assertThat(tally.map { it["candidateId"] }).containsExactly(runningId)
        assertThat(tally.map { it["candidateId"] }).doesNotContain(quitId)
    }

    // ── Group 8: revealResult() — winner path and tie path ───────────────────

    @Test
    fun `revealResult - transitions to RESULT and broadcasts SheriffElected when single top candidate`() {
        val winnerId = "cand:winner"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = electionObj)
        val votes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = winnerId),
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = hostId, targetUserId = winnerId),
        )
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(votes)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, winnerId)).thenReturn(Optional.empty())

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(electionObj.electedSheriffUserId).isEqualTo(winnerId)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).anyMatch { it is DomainEvent.SheriffElected && it.sheriffUserId == winnerId }
        assertThat(captor.allValues).anyMatch { it is DomainEvent.PhaseChanged && it.subPhase == ElectionSubPhase.RESULT.name }
    }

    @Test
    fun `revealResult - transitions to TIED when multiple candidates share the top vote count`() {
        val candA = "cand:001"
        val candB = "cand:002"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = electionObj)
        // Each candidate receives exactly 1 vote → tied
        val votes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = candA),
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = hostId, targetUserId = candB),
        )
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(votes)
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.TIED)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        assertThat((captor.firstValue as DomainEvent.PhaseChanged).subPhase).isEqualTo(ElectionSubPhase.TIED.name)
    }

    // ── Group 9: appoint() action ─────────────────────────────────────────────

    @Test
    fun `appoint - rejected when actor is not host`() {
        val electionObj = election(subPhase = ElectionSubPhase.TIED)
        val ctx = context(election = electionObj)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_APPOINT, targetUserId = "cand:001")
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("host")
    }

    @Test
    fun `appoint - rejected when election is not in TIED sub-phase`() {
        val electionObj = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = electionObj)

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_APPOINT, targetUserId = "cand:001")
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("TIED")
    }

    @Test
    fun `appoint - rejected when target is not a running candidate`() {
        val targetId = "cand:001"
        val electionObj = election(subPhase = ElectionSubPhase.TIED)
        val ctx = context(election = electionObj)
        val quitCandidate = SheriffCandidate(electionId = electionId, userId = targetId, status = CandidateStatus.QUIT)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(quitCandidate))

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_APPOINT, targetUserId = targetId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("not a running candidate")
    }

    @Test
    fun `appoint - succeeds, sets RESULT sub-phase, broadcasts SheriffElected and RESULT`() {
        val targetId = "cand:001"
        val electionObj = election(subPhase = ElectionSubPhase.TIED)
        val ctx = context(election = electionObj)
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = targetId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningCandidate))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        whenever(gamePlayerRepository.findByGameIdAndUserId(gameId, targetId)).thenReturn(Optional.empty())

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_APPOINT, targetUserId = targetId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(electionObj.subPhase).isEqualTo(ElectionSubPhase.RESULT)
        assertThat(electionObj.electedSheriffUserId).isEqualTo(targetId)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, times(2)).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).anyMatch { it is DomainEvent.SheriffElected && it.sheriffUserId == targetId }
        assertThat(captor.allValues).anyMatch { it is DomainEvent.PhaseChanged && it.subPhase == ElectionSubPhase.RESULT.name }
    }

    // ── Group 10: SHERIFF_END_RESULT — host dismisses RESULT screen ──────────

    @Test
    fun `endResult - host advances SHERIFF_ELECTION-RESULT to DAY_DISCUSSION-RESULT_HIDDEN`() {
        // Replaces the old 60s auto-timer: host clicks 显示结果 to dismiss the
        // sheriff RESULT screen and move the game forward. Lands on
        // RESULT_HIDDEN — host still needs to click REVEAL_NIGHT_RESULT next
        // to apply the deferred N1 kills (preserves 国标 reveal cadence).
        val electionObj = election(subPhase = ElectionSubPhase.RESULT)
        val ctx = context(election = electionObj)
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_END_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val phaseChanged = captor.firstValue as DomainEvent.PhaseChanged
        assertThat(phaseChanged.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(phaseChanged.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    @Test
    fun `endResult - rejected when actor is not host`() {
        val electionObj = election(subPhase = ElectionSubPhase.RESULT)
        val ctx = context(election = electionObj)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_END_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Only host")
    }

    @Test
    fun `endResult - rejected when election is not in RESULT sub-phase`() {
        // Don't let the host short-circuit past VOTING by clicking 显示结果
        // before the result has actually been computed.
        val electionObj = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = electionObj)

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_END_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("RESULT")
    }

    // ── Group 11: SIGNUP — hide identities + auto-transition when all decide ────
    //
    // Behavioural change (2026-05-11): during SIGNUP, players must not see WHO
    // joined the campaign — only how many have decided. The campaign then
    // auto-advances to SPEECH (or DAY_DISCUSSION if nobody ran) once every
    // alive player has either signed up or passed. Removes the host's manual
    // 开始演讲 button as a way to start the campaign before everyone has
    // decided.

    private fun fourPlayerCtx(
        signupCandidates: List<SheriffCandidate>,
        election: SheriffElection = election(),
    ): GameContext {
        val players = listOf(
            player(hostId, 0),
            player(guestId, 1),
            player("p3", 2),
            player("p4", 3),
        )
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(signupCandidates)
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }
        return GameContext(game(), room(), players, election = election)
    }

    @Test
    fun `signUp - auto-transitions to SPEECH when last alive player signs up`() {
        // 3 of 4 alive players have decided (2 RUNNING + 1 QUIT); guestId is
        // the last to decide and chooses to RUN. After signUp, all 4 are
        // decided AND there's at least one RUNNING candidate → SPEECH starts.
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p3", status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p4", status = CandidateStatus.QUIT),
        )
        val ctx = fourPlayerCtx(priorDecisions)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.election!!.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).anyMatch {
            it is DomainEvent.PhaseChanged && it.subPhase == ElectionSubPhase.SPEECH.name
        }
        // SIGNUP update should NOT also fire — the only broadcast is the SPEECH
        // transition. Defends against double-emit that would make the client
        // bounce SIGNUP → SPEECH → SIGNUP.
        assertThat(captor.allValues).noneMatch {
            it is DomainEvent.PhaseChanged && it.subPhase == ElectionSubPhase.SIGNUP.name
        }
    }

    @Test
    fun `pass - auto-transitions to SPEECH when last alive player passes and running candidates exist`() {
        // 3 of 4 decided (1 RUNNING + 2 QUIT). 4th player passes → all decided,
        // 1 running candidate → SPEECH.
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p3", status = CandidateStatus.QUIT),
            SheriffCandidate(electionId = electionId, userId = "p4", status = CandidateStatus.QUIT),
        )
        val ctx = fourPlayerCtx(priorDecisions)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.election!!.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).anyMatch {
            it is DomainEvent.PhaseChanged && it.subPhase == ElectionSubPhase.SPEECH.name
        }
    }

    @Test
    fun `pass - auto-advances to DAY_DISCUSSION when all decided and nobody ran`() {
        // 3 of 4 already passed. 4th passes → all decided, 0 RUNNING → skip
        // straight to DAY_DISCUSSION/RESULT_HIDDEN. Mirrors startSpeech's
        // empty-candidates branch — there's no point landing on a RESULT screen
        // with no sheriff and no votes.
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.QUIT),
            SheriffCandidate(electionId = electionId, userId = "p3", status = CandidateStatus.QUIT),
            SheriffCandidate(electionId = electionId, userId = "p4", status = CandidateStatus.QUIT),
        )
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
        val ctx = fourPlayerCtx(priorDecisions)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), captor.capture())
        assertThat(captor.allValues).anyMatch {
            it is DomainEvent.PhaseChanged &&
                it.phase == GamePhase.DAY_DISCUSSION &&
                it.subPhase == DaySubPhase.RESULT_HIDDEN.name
        }
    }

    @Test
    fun `signUp - stays in SIGNUP and broadcasts signup update when some players still undecided`() {
        // Only 1 of 4 has decided so far. After guestId signs up, 2 are decided,
        // 2 are still undecided → no transition.
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
        )
        val ctx = fourPlayerCtx(priorDecisions)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.election!!.subPhase).isEqualTo(ElectionSubPhase.SIGNUP)
        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val event = captor.firstValue as DomainEvent.PhaseChanged
        assertThat(event.subPhase).isEqualTo(ElectionSubPhase.SIGNUP.name)
    }

    @Test
    fun `pass - stays in SIGNUP when not all players have decided yet`() {
        // 1 already RUNNING, guestId passes → 2 decided of 4 → keep SIGNUP.
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
        )
        val ctx = fourPlayerCtx(priorDecisions)

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_PASS)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.election!!.subPhase).isEqualTo(ElectionSubPhase.SIGNUP)
    }

    @Test
    fun `signUp - dead players are not blocking the all-decided check`() {
        // A dead player can't decide (signUp rejects them; pass is irrelevant
        // since they were eliminated). The all-decided gate must only count
        // alive players, otherwise dead players would freeze the campaign.
        val players = listOf(
            player(hostId, 0),
            player(guestId, 1),
            player("p3", 2),
            player("p4", 3).also { it.alive = false }, // dead
        )
        val priorDecisions = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p3", status = CandidateStatus.QUIT),
        )
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(priorDecisions)
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }
        val ctx = GameContext(game(), room(), players, election = election())

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        // 3 alive players + 3 decisions (host RUNNING, p3 QUIT, guest now RUNNING) → SPEECH.
        assertThat(ctx.election!!.subPhase).isEqualTo(ElectionSubPhase.SPEECH)
    }

    // ── Group 12: buildState during SIGNUP — hidden identities + progress ──────

    @Test
    fun `buildState - SIGNUP hides other candidates' identities and exposes only my own row`() {
        // Players must not see WHO joined the campaign — only the count + their
        // own status. The frontend uses the (self-only) candidates row to flip
        // between Run / Withdraw / Pass buttons.
        val myPlayer = player(guestId, 1)
        val otherRunningId = "other:run"
        val otherQuitId = "other:quit"
        val electionObj = election(subPhase = ElectionSubPhase.SIGNUP)
        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = otherRunningId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = otherQuitId, status = CandidateStatus.QUIT),
        )
        setupBuildState(electionObj, candidates, emptyList(), myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player(otherRunningId, 2), player(otherQuitId, 3))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        @Suppress("UNCHECKED_CAST")
        val candidatesOut = state["candidates"] as List<Map<String, Any?>>
        // Only my row is included
        assertThat(candidatesOut).hasSize(1)
        assertThat(candidatesOut[0]["userId"]).isEqualTo(guestId)
    }

    @Test
    fun `buildState - SIGNUP includes decisionProgress`() {
        // 3 of 4 alive players have decided. UI uses this to show "3/4 已选择".
        val myPlayer = player(guestId, 1)
        val electionObj = election(subPhase = ElectionSubPhase.SIGNUP)
        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p3", status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = "p4", status = CandidateStatus.QUIT),
        )
        setupBuildState(electionObj, candidates, emptyList(), myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player("p3", 2), player("p4", 3))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        @Suppress("UNCHECKED_CAST")
        val progress = state["decisionProgress"] as Map<String, Int>
        assertThat(progress["decided"]).isEqualTo(3)
        assertThat(progress["total"]).isEqualTo(4)
    }

    @Test
    fun `buildState - SPEECH still exposes all candidate identities (revealed by speaking order)`() {
        // After SPEECH starts, hiding identities is meaningless — the speaking
        // order already discloses who ran. Make sure we don't strip identities
        // outside of SIGNUP.
        val myPlayer = player(guestId, 1)
        val otherRunningId = "other:run"
        val electionObj = election(
            subPhase = ElectionSubPhase.SPEECH,
            speakingOrder = "$guestId,$otherRunningId",
        )
        val candidates = listOf(
            SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING),
            SheriffCandidate(electionId = electionId, userId = otherRunningId, status = CandidateStatus.RUNNING),
        )
        setupBuildState(electionObj, candidates, emptyList(), myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player(otherRunningId, 2))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        @Suppress("UNCHECKED_CAST")
        val candidatesOut = state["candidates"] as List<Map<String, Any?>>
        assertThat(candidatesOut).hasSize(2)
        assertThat(candidatesOut.map { it["userId"] }).contains(guestId, otherRunningId)
    }

    // ── Group 13: Feature 1 — candidates cannot vote ──────────────────────────

    @Test
    fun `vote - rejected when actor is a RUNNING candidate`() {
        val targetId = "other:001"
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "$guestId,$targetId")
        val ctx = context(election = election)
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        val runningTarget = SheriffCandidate(electionId = electionId, userId = targetId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningCandidate, runningTarget))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_VOTE, targetUserId = targetId)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Candidates cannot vote")
        verify(voteRepository, never()).save(any<Vote>())
    }

    @Test
    fun `abstain - rejected when actor is a RUNNING candidate`() {
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "$guestId,other:001")
        val ctx = context(election = election)
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningCandidate))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_ABSTAIN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("Candidates cannot vote")
        verify(voteRepository, never()).save(any<Vote>())
    }

    @Test
    fun `revealResult - all alive players are running candidates and no votes cast transitions to DAY_DISCUSSION`() {
        // Every alive player ran → nobody was eligible to vote → no votes possible.
        // revealResult should bypass TIED (host-appoint) and go straight to DAY_DISCUSSION/RESULT_HIDDEN.
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "$hostId,$guestId")
        val ctx = context(election = electionObj)

        // No votes cast at all
        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(emptyList())

        // Both alive players are running candidates
        val runningHost = SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING)
        val runningGuest = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(runningHost, runningGuest))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
        assertThat(ctx.game.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(ctx.game.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)

        val captor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), captor.capture())
        val phaseChanged = captor.firstValue as DomainEvent.PhaseChanged
        assertThat(phaseChanged.phase).isEqualTo(GamePhase.DAY_DISCUSSION)
        assertThat(phaseChanged.subPhase).isEqualTo(DaySubPhase.RESULT_HIDDEN.name)
    }

    // ── Timer cancel hooks ─────────────────────────────────────────────────────

    @Test
    fun `advanceSpeech - cancels timer before advancing to next candidate`() {
        val order = "$hostId,$guestId"
        val ctx = context(
            election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = order, currentSpeakerIdx = 0),
            players = listOf(player(hostId), player(guestId)),
        )
        val candidateHost = SheriffCandidate(electionId = electionId, userId = hostId, status = CandidateStatus.RUNNING)
        val candidateGuest = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(candidateHost, candidateGuest))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_ADVANCE_SPEECH)
        sheriffService.handle(req, ctx)

        // hostTimerService.cancel must have been called
        verify(hostTimerService).cancel(gameId)
    }

    @Test
    fun `advanceSpeech - cancels timer when transitioning to VOTING (all spoke)`() {
        val order = "$guestId"
        val ctx = context(
            election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = order, currentSpeakerIdx = 0),
            players = listOf(player(hostId), player(guestId)),
        )
        val candidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(candidate))
        whenever(sheriffElectionRepository.save(any<SheriffElection>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_ADVANCE_SPEECH)
        sheriffService.handle(req, ctx)

        verify(hostTimerService).cancel(gameId)

        val eventCaptor = argumentCaptor<DomainEvent>()
        verify(stompPublisher).broadcastGame(eq(gameId), eventCaptor.capture())
        assertThat((eventCaptor.firstValue as DomainEvent.PhaseChanged).subPhase)
            .isEqualTo(ElectionSubPhase.VOTING.name)
    }

    @Test
    fun `quitCampaign - cancels timer when last candidate quits (SPEECH exits to DAY_DISCUSSION)`() {
        val order = "$guestId"
        val ctx = context(
            election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = order, currentSpeakerIdx = 0),
            players = listOf(player(hostId), player(guestId)),
        )
        val candidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(candidate))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer {
            (it.arguments[0] as SheriffCandidate).also { c -> c.status = CandidateStatus.QUIT }
        }
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_QUIT_CAMPAIGN)
        sheriffService.handle(req, ctx)

        verify(hostTimerService).cancel(gameId)
    }
}
