package com.werewolf.service

import com.werewolf.game.GameActivityTracker
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.timer.HostTimerService
import com.werewolf.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

/**
 * Cancels in-flight games nobody has acted in for [abandonAfterMinutes] (default 2 hours,
 * owner decision 2026-11-14). Before this, a game the players walked away from hung until
 * the next restart — on production that was 4–6 days, half of all games — holding its
 * room and its players' perk coins. Activity = the last accepted action
 * ([GameActivityTracker]), or the game's start when there has been none.
 */
@Component
class AbandonedGameSweep(
    private val gameRepository: GameRepository,
    private val activityTracker: GameActivityTracker,
    private val gameCanceller: GameCanceller,
    private val nightOrchestrator: NightOrchestrator,
    private val hostTimerService: HostTimerService,
    @Value("\${werewolf.games.abandon-after-minutes:120}") private val abandonAfterMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(AbandonedGameSweep::class.java)

    @Scheduled(
        fixedDelayString = "\${werewolf.games.abandon-sweep-interval-ms:600000}",
        initialDelayString = "\${werewolf.games.abandon-sweep-interval-ms:600000}",
    )
    fun sweep() {
        cancelAbandoned(LocalDateTime.now())
    }

    /** Cancels every in-flight game idle for the limit at [now]; returns their ids. */
    fun cancelAbandoned(now: LocalDateTime): List<Int> {
        val limit = Duration.ofMinutes(abandonAfterMinutes)
        return gameRepository.findByEndedAtIsNull().mapNotNull { game ->
            val gameId = game.gameId ?: return@mapNotNull null
            val last = activityTracker.lastActivity(gameId) ?: game.startedAt ?: return@mapNotNull null
            if (Duration.between(last, now) < limit) return@mapNotNull null
            try {
                // Stop the in-memory jobs first so nothing advances the game while it is ended.
                nightOrchestrator.cancelNightPhase(gameId)
                hostTimerService.cancel(gameId)
                if (!gameCanceller.cancel(gameId, "abandoned")) return@mapNotNull null
                activityTracker.forget(gameId)
                log.warn("[abandoned-games] game={} cancelled: no action since {}", gameId, last)
                gameId
            } catch (e: Exception) {
                log.error("[abandoned-games] failed to cancel game $gameId", e)
                null
            }
        }
    }
}
