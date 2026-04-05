package com.werewolf.game.night

import com.werewolf.model.NightSubPhase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Schedules delayed night phase transitions:
 * - WAITING → WEREWOLF_PICK (5 seconds)
 * - Dead role transitions (20 seconds)
 * Lives in a separate bean to avoid a circular dependency with NightOrchestrator.
 */
@Component
class NightWaitingScheduler(@Lazy private val nightOrchestrator: NightOrchestrator) {

    val log: Logger = LoggerFactory.getLogger(NightWaitingScheduler::class.java)

    @Async
    fun scheduleAdvance(gameId: Int, delayMs: Long = 5_000, targetSubPhase: NightSubPhase? = null) {
        log.info("[NightWaitingScheduler] Scheduling advance for game $gameId with delay ${delayMs}ms to ${targetSubPhase ?: "WEREWOLF_PICK"}")
        try {
            Thread.sleep(delayMs)
            log.info("[NightWaitingScheduler] Delay completed for game $gameId, advancing...")

            // When targetSubPhase is null (default), it means WAITING → first real phase
            if (targetSubPhase == null || targetSubPhase == NightSubPhase.WAITING) {
                log.info("[NightWaitingScheduler] Calling advanceFromWaiting for game $gameId")
                nightOrchestrator.advanceFromWaiting(gameId)
            } else {
                log.info("[NightWaitingScheduler] Calling advanceToSubPhase for game $gameId to $targetSubPhase")
                nightOrchestrator.advanceToSubPhase(gameId, targetSubPhase)
            }

            log.info("[NightWaitingScheduler] Advance completed for game $gameId")
        } catch (e: Exception) {
            log.error("[NightWaitingScheduler] ERROR during advance for game $gameId: ${e.message}")
            e.printStackTrace()
        }
    }
}