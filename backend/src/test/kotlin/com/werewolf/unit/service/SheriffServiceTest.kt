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
        val ctx = context(election = election(subPhase = ElectionSubPhase.SPEECH, speakingOrder = "$guestId"))

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
}
