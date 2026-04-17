package com.werewolf.game.night

import com.werewolf.model.AudioSequence
import com.werewolf.model.NightSubPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Schedules delayed night phase transitions.
 * Used for the WAITING → WEREWOLF_PICK advance (5 seconds) and audio delay broadcasts.
 * Lives in a separate bean so it can call NightOrchestrator without a circular dependency.
 */
@Component
class NightWaitingScheduler(
    private val nightOrchestrator: NightOrchestrator,
    private val stompPublisher: com.werewolf.service.StompPublisher,
    private val contextLoader: com.werewolf.service.GameContextLoader,
    private val nightPhaseRepository: com.werewolf.repository.NightPhaseRepository,
    private val coroutineScope: CoroutineScope,
) {

    val log: Logger = LoggerFactory.getLogger(NightWaitingScheduler::class.java)

    /**
     * Schedule a delayed advance.
     *
     * When [targetSubPhase] is null (default) → calls [NightOrchestrator.advanceFromWaiting].
     * When [targetSubPhase] is set           → calls [NightOrchestrator.advanceToSubPhase].
     */
    fun scheduleAdvance(gameId: Int, delayMs: Long = 5_000, targetSubPhase: NightSubPhase? = null): Job {
        log.info("[NightWaitingScheduler] Scheduling advance for game $gameId with delay ${delayMs}ms to ${targetSubPhase ?: "WEREWOLF_PICK"}")

        return coroutineScope.launch {
            try {
                delay(delayMs)
                log.info("[NightWaitingScheduler] Delay completed for game $gameId, advancing...")

                if (targetSubPhase == null || targetSubPhase == NightSubPhase.WAITING) {
                    nightOrchestrator.advanceFromWaiting(gameId)
                } else {
                    nightOrchestrator.advanceToSubPhase(gameId, targetSubPhase)
                }

                log.info("[NightWaitingScheduler] Advance completed for game $gameId")
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during advance for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Broadcast an [AudioSequence] after [delayMs] milliseconds.
     */
    fun scheduleAudioDelay(
        gameId: Int,
        audioSequence: AudioSequence,
        delayMs: Long,
    ): Job {
        log.info("[NightWaitingScheduler] Scheduling audio delay for game $gameId with delay ${delayMs}ms")

        return coroutineScope.launch {
            try {
                delay(delayMs)
                log.info("[NightWaitingScheduler] Audio delay completed for game $gameId, broadcasting audio sequence...")
                stompPublisher.broadcastGame(gameId, com.werewolf.game.DomainEvent.AudioSequence(gameId, audioSequence))
                log.info("[NightWaitingScheduler] Audio sequence broadcast completed for game $gameId")
            } catch (e: Exception) {
                log.error("[NightWaitingScheduler] ERROR during audio delay for game $gameId: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
