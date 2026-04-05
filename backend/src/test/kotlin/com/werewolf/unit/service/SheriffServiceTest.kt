package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
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
class SheriffServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var gamePlayerRepository: GamePlayerRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var sheriffCandidateRepository: SheriffCandidateRepository
    @Mock lateinit var voteRepository: VoteRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var stompPublisher: StompPublisher
    @Mock lateinit var nightOrchestrator: NightOrchestrator
    @InjectMocks lateinit var sheriffService: SheriffService

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

        verifyNoInteractions(nightOrchestrator)
    }

    @Test
    fun `quitCampaign - triggers initNight when last running candidate quits`() {
        val speakingOrder = guestId
        val election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = speakingOrder)
        val ctx = context(election = election)

        val guestCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)

        // After save, the candidate is QUIT and there are no more RUNNING candidates in the speaking order
        whenever(sheriffCandidateRepository.findByElectionId(electionId))
            .thenReturn(listOf(guestCandidate))
            .thenReturn(listOf(
                SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.QUIT),
            ))
        whenever(sheriffCandidateRepository.save(any<SheriffCandidate>())).thenAnswer { it.arguments[0] }

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_QUIT_CAMPAIGN)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        verify(nightOrchestrator).initNight(gameId, 1)
        verify(stompPublisher, never()).broadcastGame(eq(gameId), argThat { this is DomainEvent.PhaseChanged && subPhase == ElectionSubPhase.SPEECH.name })
    }

    // ── Group 4: revealResult() empty votes → night ────────────────────────────

    @Test
    fun `revealResult - triggers initNight when no votes cast`() {
        val election = election(subPhase = ElectionSubPhase.VOTING)
        val ctx = context(election = election)

        whenever(voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, 1
        )).thenReturn(emptyList())

        val req = GameActionRequest(gameId, hostId, ActionType.SHERIFF_REVEAL_RESULT)
        val result = sheriffService.handle(req, ctx)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        verify(nightOrchestrator).initNight(gameId, 1)
        verify(stompPublisher, never()).broadcastGame(eq(gameId), argThat {
            this is DomainEvent.PhaseChanged &&
                (subPhase == ElectionSubPhase.RESULT.name || subPhase == ElectionSubPhase.TIED.name)
        })
    }

    // ── Group 5: vote() — self-vote and other vote validations ────────────────

    @Test
    fun `vote - rejected when player votes for themselves`() {
        // Bug: backend had no guard against actorUserId == targetUserId.
        // A candidate could cast their own vote for themselves.
        // Fix: explicit self-vote rejection before any DB write.
        val election = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "other:001")
        val ctx = context(election = election)
        val selfCandidate = SheriffCandidate(electionId = electionId, userId = guestId, status = CandidateStatus.RUNNING)
        whenever(sheriffCandidateRepository.findByElectionId(electionId)).thenReturn(listOf(selfCandidate))

        val req = GameActionRequest(gameId, guestId, ActionType.SHERIFF_VOTE, targetUserId = guestId)
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
        val myPlayer = player(guestId, 1)
        val thirdId = "other:002"
        val electionObj = election(subPhase = ElectionSubPhase.VOTING, speakingOrder = "cand:001")
        val runningCandidate = SheriffCandidate(electionId = electionId, userId = "cand:001", status = CandidateStatus.RUNNING)
        val submittedVotes = listOf(
            Vote(gameId = gameId, voteContext = VoteContext.SHERIFF_ELECTION, dayNumber = 1,
                voterUserId = guestId, targetUserId = "cand:001"),
        )
        setupBuildState(electionObj, listOf(runningCandidate), submittedVotes, myPlayer)

        val players = listOf(player(hostId, 0), myPlayer, player(thirdId, 2))
        val state = sheriffService.buildState(gameId, game(), myPlayer, players)

        assertThat(state["allVoted"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val vp = state["voteProgress"] as Map<String, Int>
        assertThat(vp["voted"]).isEqualTo(1)   // 1 submitted + 0 quitters
        assertThat(vp["total"]).isEqualTo(3)   // 3 alive players
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
        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
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
        verify(nightOrchestrator, never()).initNight(any(), any(), anyOrNull(), any())
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
}
