package com.werewolf.game.night

import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Schedules the delayed transition from NightSubPhase.WAITING → WEREWOLF_PICK.
 * Lives in a separate bean to avoid a circular dependency with NightOrchestrator.
 */
@Component
class NightWaitingScheduler(@Lazy private val nightOrchestrator: NightOrchestrator) {

    @Async
    fun scheduleAdvance(gameId: Int, delayMs: Long = 5_000) {
        Thread.sleep(delayMs)
        nightOrchestrator.advanceFromWaiting(gameId)
    }
}
