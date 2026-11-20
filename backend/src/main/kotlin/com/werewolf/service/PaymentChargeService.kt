package com.werewolf.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/** NOT_OURS: a charge without our orderNo and no order; ignored, never an alert. */
enum class ChargeOutcome { REVERSED, REINSTATED, UNCHANGED, NEEDS_REVIEW, NOT_SETTLED, NOT_OURS }

class OrderNotFoundForChargeException(chargeId: String) :
    RuntimeException("No payment order for charge $chargeId")

/**
 * Refunds and disputes: reconcile, don't replay (R2–R4, R7). Whatever event asked, re-read the
 * charge from Stripe and make the order's credits match its money state.
 *
 * Deliberately not @Transactional: the Stripe call stays outside the DB transaction, and
 * [PaymentOrderWriter] does the writes.
 */
@Service
class PaymentChargeService(
    private val gateway: StripeGateway,
    private val writer: PaymentOrderWriter,
) {
    private val log = LoggerFactory.getLogger(PaymentChargeService::class.java)

    fun reconcileCharge(chargeId: String, source: String): ChargeOutcome {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Stripe calls must not run inside a DB transaction" }
        val charge = gateway.retrieveCharge(chargeId)
        val order = writer.findOrderForCharge(charge)
        if (order == null) {
            if (charge.orderNo == null) {
                log.warn("[payment] charge {} is not ours, ignored (source={})", chargeId, source)
                return ChargeOutcome.NOT_OURS
            }
            log.error("[payment-alert] charge {} of order {} has no order (source={})", chargeId, charge.orderNo, source)
            throw OrderNotFoundForChargeException(chargeId)
        }
        val state = moneyState(charge)
        val money = "refunded ${state.refundedAmount}/${charge.amount}, dispute ${state.dispute}"
        val outcome = writer.applyMoneyState(order.id ?: error("order not persisted"), state, "$money ($source)")
        when (outcome) {
            ChargeOutcome.NOT_SETTLED ->
                // Not paid yet in our DB (fulfillment still to come): fail so the webhook retries.
                throw IllegalStateException("charge $chargeId: order ${order.orderNo} is not settled (${order.status}), retry later")
            ChargeOutcome.UNCHANGED -> {}
            // NEEDS_REVIEW was already alerted (once) by the writer.
            else -> log.info("[payment] charge {} order {} {}: {} (source={})", chargeId, order.orderNo, outcome, money, source)
        }
        return outcome
    }
}
