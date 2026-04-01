package com.werewolf.game.night

import com.werewolf.model.NightSubPhase
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

    @Async
    fun scheduleAdvance(gameId: Int, delayMs: Long = 5_000, targetSubPhase: NightSubPhase? = null) {
        Thread.sleep(delayMs)
        if (targetSubPhase == NightSubPhase.WAITING) {
            nightOrchestrator.advanceFromWaiting(gameId)
        } else {
            nightOrchestrator.advanceToSubPhase(gameId, targetSubPhase)
        }
    }
}
