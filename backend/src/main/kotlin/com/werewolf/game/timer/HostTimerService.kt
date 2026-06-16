package com.werewolf.game.timer

import com.werewolf.game.DomainEvent
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.*
import com.werewolf.repository.GameRepository
import com.werewolf.repository.SheriffElectionRepository
import com.werewolf.service.StompPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

data class TimerSnapshot(
    val remainingMs: Long,
    val durationMs: Long,
    val running: Boolean,
)

@Service
class HostTimerService(
    private val gameRepository: GameRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val stompPublisher: StompPublisher,
    private val coroutineScope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(HostTimerService::class.java)

    companion object {
        const val COUNTDOWN_WARNING_FILE = "countdown_warning.mp3"
        private val ALLOWED_DURATIONS_S = setOf(60L, 120L)
    }

    private val expirationJobs = ConcurrentHashMap<Int, Job>()
    private val warningJobs    = ConcurrentHashMap<Int, Job>()

    @Transactional
    fun start(hostUserId: String, gameId: Int, durationSeconds: Long): GameActionResult {
        if (durationSeconds !in ALLOWED_DURATIONS_S)
            return GameActionResult.Rejected("durationSeconds must be 60 or 120")

        val game = gameRepository.findById(gameId).orElse(null)
            ?: return GameActionResult.Rejected("Game not found")
        if (game.hostUserId != hostUserId)
            return GameActionResult.Rejected("Only the host can control the timer")

        if (!isPhaseAllowed(game))
            return GameActionResult.Rejected("Timer not allowed in current phase/sub-phase")

        val durationMs = durationSeconds * 1_000L
        val startedAt  = System.currentTimeMillis()

        game.timerStartedAt  = startedAt
        game.timerDurationMs = durationMs
        game.timerRunning    = true
        gameRepository.save(game)

        log.info("[HostTimerService] start game=$gameId duration=${durationMs}ms")

        // Cancel any prior jobs
        cancelJobs(gameId)

        // Broadcast: remaining = full duration at the moment of start
        stompPublisher.broadcastGame(gameId, DomainEvent.TimerUpdated(gameId, durationMs, durationMs, true))

        // Schedule warning at T-10s (skip if duration ≤ 10s)
        if (durationMs > 10_000L) {
            val warningDelayMs = durationMs - 10_000L
            warningJobs[gameId] = coroutineScope.launch {
                delay(warningDelayMs.milliseconds)
                broadcastWarningCue(gameId, durationMs)
            }
        }

        // Schedule expiration
        expirationJobs[gameId] = coroutineScope.launch {
            delay(durationMs.milliseconds)
            commitStop(gameId, durationMs)
        }

        return GameActionResult.Success()
    }

    @Transactional
    fun stop(hostUserId: String, gameId: Int): GameActionResult {
        val game = gameRepository.findById(gameId).orElse(null)
            ?: return GameActionResult.Rejected("Game not found")
        if (game.hostUserId != hostUserId)
            return GameActionResult.Rejected("Only the host can control the timer")
        if (!isPhaseAllowed(game))
            return GameActionResult.Rejected("Timer not allowed in current phase/sub-phase")

        val durationMs = game.timerDurationMs
        log.info("[HostTimerService] stop game=$gameId")

        cancelJobs(gameId)
        game.timerRunning    = false
        game.timerStartedAt  = null
        gameRepository.save(game)

        stompPublisher.broadcastGame(gameId, DomainEvent.TimerUpdated(gameId, 0L, durationMs, false))
        return GameActionResult.Success()
    }

    /** Cancel any active timer for this game. No auth check — called on phase exit. */
    fun cancel(gameId: Int) {
        val hadJobs = expirationJobs.containsKey(gameId) || warningJobs.containsKey(gameId)
        cancelJobs(gameId)

        val game = gameRepository.findById(gameId).orElse(null) ?: return
        val durationMs = game.timerDurationMs
        if (game.timerRunning) {
            game.timerRunning   = false
            game.timerStartedAt = null
            gameRepository.save(game)
            log.info("[HostTimerService] cancel game=$gameId (was running)")
            stompPublisher.broadcastGame(gameId, DomainEvent.TimerUpdated(gameId, 0L, durationMs, false))
        } else if (hadJobs) {
            log.info("[HostTimerService] cancel game=$gameId (jobs cleared, was not running)")
        }
    }

    fun snapshot(gameId: Int): TimerSnapshot {
        val game = gameRepository.findById(gameId).orElse(null)
            ?: return TimerSnapshot(0L, 0L, false)
        if (!game.timerRunning) return TimerSnapshot(0L, game.timerDurationMs, false)
        val startedAt = game.timerStartedAt ?: return TimerSnapshot(0L, game.timerDurationMs, false)
        val remaining = maxOf(0L, (startedAt + game.timerDurationMs) - System.currentTimeMillis())
        return TimerSnapshot(remaining, game.timerDurationMs, true)
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun isPhaseAllowed(game: Game): Boolean {
        if (game.phase == GamePhase.DAY_DISCUSSION) return true
        if (game.phase == GamePhase.SHERIFF_ELECTION) {
            val election = sheriffElectionRepository.findByGameId(game.gameId ?: return false).orElse(null)
                ?: return false
            return election.subPhase == ElectionSubPhase.SPEECH
        }
        return false
    }

    private fun cancelJobs(gameId: Int) {
        warningJobs.remove(gameId)?.cancel()
        expirationJobs.remove(gameId)?.cancel()
    }

    private fun broadcastWarningCue(gameId: Int, durationMs: Long) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        if (!game.timerRunning) return  // stopped before warning fired
        val remaining = maxOf(0L, (game.timerStartedAt ?: 0L) + durationMs - System.currentTimeMillis())
        val seq = AudioSequence(
            id          = "timer-warning-$gameId-${game.timerStartedAt}",
            phase       = game.phase,
            subPhase    = game.subPhase,
            audioFiles  = listOf(COUNTDOWN_WARNING_FILE),
            priority    = 0,
            timestamp   = System.currentTimeMillis(),
        )
        log.info("[HostTimerService] broadcasting warning cue game=$gameId remaining=${remaining}ms")
        stompPublisher.broadcastGame(gameId, DomainEvent.AudioSequence(gameId, seq))
    }

    private fun commitStop(gameId: Int, durationMs: Long) {
        expirationJobs.remove(gameId)
        warningJobs.remove(gameId)?.cancel()
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        if (!game.timerRunning) return   // already stopped externally
        game.timerRunning   = false
        game.timerStartedAt = null
        gameRepository.save(game)
        log.info("[HostTimerService] expired game=$gameId")
        stompPublisher.broadcastGame(gameId, DomainEvent.TimerUpdated(gameId, 0L, durationMs, false))
    }
}
