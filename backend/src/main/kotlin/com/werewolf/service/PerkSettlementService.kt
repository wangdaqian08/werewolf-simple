package com.werewolf.service

import com.werewolf.model.CreditTxType
import com.werewolf.model.PerkActivation
import com.werewolf.model.PerkActivationStatus
import com.werewolf.repository.PerkActivationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Settles every perk activation bound to a finished game, exactly once each:
 * took effect → CONSUMED (credits kept); did not take effect (untriggered
 * ACTIVE, wolf-VOID, cancelled game, or unknown perk) → REFUNDED + wallet
 * credit + REFUND ledger row. settleIfLive's conditional UPDATE is the
 * idempotency gate (same pattern as PaymentOrderRepository.markCompletedIfCreated):
 * 0 rows updated = already settled, so no wallet write. Safe to call
 * repeatedly and concurrently.
 */
@Service
class PerkSettlementService(
    private val perkActivationRepository: PerkActivationRepository,
    private val walletService: WalletService,
) {
    private val log = LoggerFactory.getLogger(PerkSettlementService::class.java)

    @Transactional
    fun settleForGame(gameId: Int, cancelled: Boolean) {
        perkActivationRepository.findByGameId(gameId)
            .filter { it.status == PerkActivationStatus.ACTIVE || it.status == PerkActivationStatus.VOID }
            .forEach { settleOne(it, cancelled) }
    }

    private fun settleOne(activation: PerkActivation, cancelled: Boolean) {
        val id = activation.id ?: error("activation has no id")
        val consumed = !cancelled && tookEffect(activation)
        val newStatus = if (consumed) PerkActivationStatus.CONSUMED else PerkActivationStatus.REFUNDED
        // Exactly-once gate: settleIfLive only flips rows still ACTIVE/VOID.
        // NOTE: it clears the persistence context — `activation` is detached
        // after this line; only plain fields may be read from it.
        if (perkActivationRepository.settleIfLive(id, newStatus) == 0) return
        if (!consumed) {
            walletService.credit(
                activation.userId, activation.pricePaid, CreditTxType.REFUND,
                perkActivationId = id, note = activation.perkCode,
            )
        }
        log.info(
            "[perk-settle] game={} user={} perk={} -> {} cancelled={} refund={}",
            activation.gameId, activation.userId, activation.perkCode, newStatus,
            cancelled, if (consumed) 0 else activation.pricePaid,
        )
    }

    /** Generic verdict per perk type; unknown perks fail safe to refund. */
    private fun tookEffect(a: PerkActivation): Boolean = when (a.perkCode) {
        PERK_NIGHT1_IMMUNITY -> a.status == PerkActivationStatus.ACTIVE && a.triggeredAt != null
        else -> false
    }
}
