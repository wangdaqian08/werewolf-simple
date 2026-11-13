package com.werewolf.unit.service

import com.werewolf.game.GameActivityTracker
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.timer.HostTimerService
import com.werewolf.model.Game
import com.werewolf.repository.GameRepository
import com.werewolf.service.AbandonedGameSweep
import com.werewolf.service.GameCanceller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

/** Games everyone walked away from: cancelled after 2 hours without an accepted action. */
class AbandonedGameSweepTest {

    private val t0 = LocalDateTime.of(2026, 11, 14, 20, 0)
    private val gameRepository = mock<GameRepository>()
    private val canceller = mock<GameCanceller> { on { cancel(any(), any()) } doReturn true }
    private val night = mock<NightOrchestrator>()
    private val timer = mock<HostTimerService>()
    private val tracker = GameActivityTracker()
    private val sweep = AbandonedGameSweep(gameRepository, tracker, canceller, night, timer, abandonAfterMinutes = 120)

    private fun game(id: Int, startedAt: LocalDateTime = t0) =
        Game(roomId = id, hostUserId = "h", startedAt = startedAt).also {
            val f = Game::class.java.getDeclaredField("gameId"); f.isAccessible = true; f.set(it, id)
        }

    private fun running(vararg games: Game) = whenever(gameRepository.findByEndedAtIsNull()).thenReturn(games.toList())

    @Test
    fun `two hours with no action - the game is cancelled and its night and timer jobs stopped`() {
        running(game(1))
        tracker.touch(1, t0)

        assertThat(sweep.cancelAbandoned(t0.plusMinutes(120))).containsExactly(1)

        verify(night).cancelNightPhase(1)
        verify(timer).cancel(1)
        verify(canceller).cancel(1, "abandoned")
        assertThat(tracker.lastActivity(1)).isNull() // forgotten once cancelled
    }

    @Test
    fun `an action inside the two hours keeps the game`() {
        running(game(1))
        tracker.touch(1, t0.plusMinutes(1))

        assertThat(sweep.cancelAbandoned(t0.plusMinutes(120))).isEmpty()

        verify(canceller, never()).cancel(any(), any())
        verify(night, never()).cancelNightPhase(any())
    }

    @Test
    fun `the last action counts, not the start - a long game still being played is kept`() {
        running(game(1, startedAt = t0))
        tracker.touch(1, t0.plusMinutes(150))

        assertThat(sweep.cancelAbandoned(t0.plusMinutes(200))).isEmpty()
    }

    @Test
    fun `no action at all since the start - the start time counts`() {
        running(game(1, startedAt = t0))

        assertThat(sweep.cancelAbandoned(t0.plusMinutes(119))).isEmpty()
        assertThat(sweep.cancelAbandoned(t0.plusMinutes(121))).containsExactly(1)
    }

    @Test
    fun `one game failing to cancel does not stop the others`() {
        running(game(1), game(2))
        whenever(canceller.cancel(1, "abandoned")).doThrow(IllegalStateException("boom"))

        assertThat(sweep.cancelAbandoned(t0.plusMinutes(180))).containsExactly(2)
        verify(canceller).cancel(2, "abandoned")
    }
}
