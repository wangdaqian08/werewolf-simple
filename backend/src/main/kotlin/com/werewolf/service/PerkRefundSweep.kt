package com.werewolf.service

import com.werewolf.model.PerkActivationStatus
import com.werewolf.repository.PerkActivationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Safety net for perk activations whose room never started a game: there is
 * no /api/room/leave or room-disband endpoint, so an abandoned WAITING room
 * would otherwise strand the player's credits forever. Any activation still
 * ACTIVE with no bound game after [STALE_AFTER_HOURS] is refunded.
 *
 * Game-bound activations are never touched here — they are resolved by
 * PerkService (CONSUMED at night-1 resolution, VOID at wolf assignment).
 */
@Component
class PerkRefundSweep(
    private val perkActivationRepository: PerkActivationRepository,
    private val perkService: PerkService,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(PerkRefundSweep::class.java)

    // One transaction per refund so a poisoned row can't block the sweep
    // (same pattern as OrphanedGameRecovery).
    private val txTemplate = TransactionTemplate(txManager)

    @Scheduled(fixedDelayString = "\${werewolf.perks.sweep-interval-ms:3600000}")
    fun refundStaleActivations() {
        val cutoff = LocalDateTime.now().minusHours(STALE_AFTER_HOURS)
        val stale = perkActivationRepository
            .findByStatusAndGameIdIsNullAndCreatedAtBefore(PerkActivationStatus.ACTIVE, cutoff)
        if (stale.isEmpty()) return

        log.info("[perk-sweep] refunding {} stale activations (never-started rooms)", stale.size)
        stale.forEach { activation ->
            try {
                txTemplate.execute {
                    perkService.refundActiveForUser(activation.roomId, activation.userId)
                }
            } catch (e: Exception) {
                log.error("[perk-sweep] failed to refund activation id={}", activation.id, e)
            }
        }
    }

    companion object {
        const val STALE_AFTER_HOURS = 24L
    }
}
