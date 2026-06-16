package com.werewolf.unit.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.timer.HostTimerService
import com.werewolf.model.*
import com.werewolf.repository.GameRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.service.StompPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class HostTimerServiceTest {

    @Mock lateinit var gameRepository: GameRepository
    @Mock lateinit var sheriffElectionRepository: SheriffElectionRepository
    @Mock lateinit var stompPublisher: StompPublisher

    private lateinit var service: HostTimerService

    private val gameId = 1
    private val hostId = "host:001"
    private val guestId = "guest:001"

    @BeforeEach
    fun setUp() {
        service = HostTimerService(
            gameRepository,
            sheriffElectionRepository,
            stompPublisher,
            CoroutineScope(Dispatchers.Default),
        )
    }

    private fun game(
        phase: GamePhase = GamePhase.DAY_DISCUSSION,
        running: Boolean = false,
        durationMs: Long = 0,
    ): Game {
        val g = Game(roomId = 1, hostUserId = hostId)
        val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(g, gameId)
        g.phase = phase
        g.timerRunning = running
        g.timerDurationMs = durationMs
        return g
    }

    private fun mockGame(g: Game) {
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))
        whenever(gameRepository.save(any<Game>())).thenAnswer { it.arguments[0] }
    }

    private fun mockSheriffElection(subPhase: ElectionSubPhase) {
        val election = SheriffElection(gameId = gameId, subPhase = subPhase)
        whenever(sheriffElectionRepository.findByGameId(gameId)).thenReturn(Optional.of(election))
    }

    // ── Phase guard ────────────────────────────────────────────────────────────

    @Test
    fun `start - rejected when phase is DAY_VOTING`() {
        val g = game(phase = GamePhase.DAY_VOTING)
        mockGame(g)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        verify(stompPublisher, never()).broadcastGame(any(), any())
    }

    @Test
    fun `start - rejected when phase is NIGHT`() {
        val g = game(phase = GamePhase.NIGHT)
        mockGame(g)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `start - rejected during SHERIFF_ELECTION SIGNUP sub-phase`() {
        val g = game(phase = GamePhase.SHERIFF_ELECTION)
        mockGame(g)
        mockSheriffElection(ElectionSubPhase.SIGNUP)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `start - rejected during SHERIFF_ELECTION VOTING sub-phase`() {
        val g = game(phase = GamePhase.SHERIFF_ELECTION)
        mockGame(g)
        mockSheriffElection(ElectionSubPhase.VOTING)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `start - rejected during SHERIFF_ELECTION RESULT sub-phase`() {
        val g = game(phase = GamePhase.SHERIFF_ELECTION)
        mockGame(g)
        mockSheriffElection(ElectionSubPhase.RESULT)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `start - accepted during DAY_DISCUSSION`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
    }

    @Test
    fun `start - accepted during SHERIFF_ELECTION SPEECH sub-phase`() {
        val g = game(phase = GamePhase.SHERIFF_ELECTION)
        mockGame(g)
        mockSheriffElection(ElectionSubPhase.SPEECH)
        val result = service.start(hostId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)
    }

    // ── Auth guard ─────────────────────────────────────────────────────────────

    @Test
    fun `start - rejected when caller is not host`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)
        val result = service.start(guestId, gameId, 60L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
        assertThat((result as GameActionResult.Rejected).reason).contains("host")
    }

    // ── Duration validation ────────────────────────────────────────────────────

    @Test
    fun `start - rejected for invalid duration 30s`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)
        val result = service.start(hostId, gameId, 30L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    @Test
    fun `start - rejected for invalid duration 0s`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)
        val result = service.start(hostId, gameId, 0L)
        assertThat(result).isInstanceOf(GameActionResult.Rejected::class.java)
    }

    // ── State write + broadcast ────────────────────────────────────────────────

    @Test
    fun `start - writes timerRunning=true and broadcasts TimerUpdated(running=true)`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)
        service.start(hostId, gameId, 60L)

        val savedCaptor = argumentCaptor<Game>()
        verify(gameRepository).save(savedCaptor.capture())
        assertThat(savedCaptor.firstValue.timerRunning).isTrue()
        assertThat(savedCaptor.firstValue.timerDurationMs).isEqualTo(60_000L)
        assertThat(savedCaptor.firstValue.timerStartedAt).isNotNull()

        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher, atLeastOnce()).broadcastGame(eq(gameId), eventCaptor.capture())
        val timerEvent = eventCaptor.allValues.filterIsInstance<DomainEvent.TimerUpdated>().first()
        assertThat(timerEvent.running).isTrue()
        assertThat(timerEvent.durationMs).isEqualTo(60_000L)
        assertThat(timerEvent.remainingMs).isEqualTo(60_000L)
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    @Test
    fun `stop - broadcasts TimerUpdated(running=false) and no AudioSequence`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION, running = true, durationMs = 60_000L)
        g.timerStartedAt = System.currentTimeMillis()
        mockGame(g)
        val result = service.stop(hostId, gameId)

        assertThat(result).isInstanceOf(GameActionResult.Success::class.java)

        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher).broadcastGame(eq(gameId), eventCaptor.capture())
        val timerEvent = eventCaptor.firstValue as DomainEvent.TimerUpdated
        assertThat(timerEvent.running).isFalse()

        // No AudioSequence must be broadcast on stop
        assertThat(eventCaptor.allValues.none { it is DomainEvent.AudioSequence }).isTrue()
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Test
    fun `cancel - broadcasts TimerUpdated(running=false) when timer was running`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION, running = true, durationMs = 60_000L)
        g.timerStartedAt = System.currentTimeMillis()
        mockGame(g)

        service.cancel(gameId)

        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher).broadcastGame(eq(gameId), eventCaptor.capture())
        val timerEvent = eventCaptor.firstValue as DomainEvent.TimerUpdated
        assertThat(timerEvent.running).isFalse()
        // No AudioSequence on cancel
        assertThat(eventCaptor.allValues.none { it is DomainEvent.AudioSequence }).isTrue()
    }

    @Test
    fun `cancel - no broadcast when timer was not running`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION, running = false, durationMs = 0L)
        mockGame(g)
        service.cancel(gameId)
        verify(stompPublisher, never()).broadcastGame(any(), any())
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    @Test
    fun `snapshot - returns running=true and decremented remainingMs when timer active`() {
        val durationMs = 60_000L
        val g = game(phase = GamePhase.DAY_DISCUSSION, running = true, durationMs = durationMs)
        g.timerStartedAt = System.currentTimeMillis() - 5_000L  // 5 seconds elapsed
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))

        val snap = service.snapshot(gameId)

        assertThat(snap.running).isTrue()
        assertThat(snap.remainingMs).isBetween(54_000L, 56_000L)
        assertThat(snap.durationMs).isEqualTo(durationMs)
    }

    @Test
    fun `snapshot - returns running=false and remainingMs=0 when not running`() {
        val g = game(phase = GamePhase.DAY_DISCUSSION, running = false, durationMs = 60_000L)
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(g))

        val snap = service.snapshot(gameId)

        assertThat(snap.running).isFalse()
        assertThat(snap.remainingMs).isEqualTo(0L)
    }

    // ── Concurrent re-start cancels prior jobs ─────────────────────────────────

    @Test
    fun `start - re-starting does not leave orphan jobs`() = runBlocking {
        val g = game(phase = GamePhase.DAY_DISCUSSION)
        mockGame(g)

        // Start a 120s timer
        service.start(hostId, gameId, 120L)
        // Immediately start a 60s timer — must cancel the 120s jobs
        service.start(hostId, gameId, 60L)

        // Stop it immediately so no jobs linger past test teardown
        val runningGame = game(phase = GamePhase.DAY_DISCUSSION, running = true, durationMs = 60_000L)
        runningGame.timerStartedAt = System.currentTimeMillis()
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(runningGame))
        service.stop(hostId, gameId)

        val eventCaptor = argumentCaptor<Any>()
        verify(stompPublisher, atLeast(3)).broadcastGame(eq(gameId), eventCaptor.capture())
        // The last TimerUpdated event must be running=false (from stop)
        val lastTimer = eventCaptor.allValues
            .filterIsInstance<DomainEvent.TimerUpdated>()
            .last()
        assertThat(lastTimer.running).isFalse()
    }
}
