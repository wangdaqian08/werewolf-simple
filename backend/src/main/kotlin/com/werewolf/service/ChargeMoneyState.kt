package com.werewolf.service

import com.werewolf.model.PaymentOrderStatus
import org.slf4j.LoggerFactory

enum class DisputePhase { NONE, OPEN, WON, LOST, INQUIRY_CLOSED }

/** What Stripe says about a charge's money, reduced to what decides its credits (R7). */
data class MoneyState(
    val refundedAmount: Long,        // sum of refunds that returned (or are returning) money
    val fullyRefunded: Boolean,
    val partialRefundSeen: Boolean,  // a refund for less than the charge: manual handling (R3)
    val dispute: DisputePhase,
) {
    /** Credits are present exactly when we keep the money; any open or lost dispute takes precedence. */
    val creditsShouldBePresent: Boolean
        get() = dispute != DisputePhase.OPEN && dispute != DisputePhase.LOST && !fullyRefunded

    val orderStatus: PaymentOrderStatus
        get() = when {
            dispute == DisputePhase.LOST -> PaymentOrderStatus.DISPUTE_LOST
            dispute == DisputePhase.OPEN -> PaymentOrderStatus.DISPUTED
            fullyRefunded -> PaymentOrderStatus.REFUNDED
            else -> PaymentOrderStatus.COMPLETED
        }
}

private val log = LoggerFactory.getLogger(MoneyState::class.java)

fun moneyState(c: ChargeSnapshot): MoneyState {
    // pending = Stripe is returning the money; requires_action = not yet (the customer must act), so it does not count.
    val returned = c.refunds.filter { it.status == "succeeded" || it.status == "pending" }
    val refunded = returned.sumOf { it.amount }
    return MoneyState(
        refundedAmount = refunded,
        fullyRefunded = refunded > 0 && refunded >= c.amount,
        partialRefundSeen = returned.any { it.amount < c.amount } || refunded in 1 until c.amount,
        dispute = disputePhase(c),
    )
}

private fun disputePhase(c: ChargeSnapshot): DisputePhase = when (c.disputeStatus) {
    null -> DisputePhase.NONE
    "warning_needs_response", "warning_under_review", "needs_response", "under_review" -> DisputePhase.OPEN
    "won" -> DisputePhase.WON
    "lost" -> DisputePhase.LOST
    "warning_closed" -> DisputePhase.INQUIRY_CLOSED
    else -> {
        log.warn("[payment] charge {} has unknown dispute status '{}', treated as open", c.chargeId, c.disputeStatus)
        DisputePhase.OPEN
    }
}
