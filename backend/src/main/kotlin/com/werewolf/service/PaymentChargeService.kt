package com.werewolf.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class ChargeOutcome { REVERSED, REINSTATED, UNCHANGED, NEEDS_REVIEW, NOT_SETTLED, NOT_OURS }

data class ChargeResult(val outcome: ChargeOutcome, val orderId: Int?)

class ChargeNotSettledException(message: String) : IllegalStateException(message)

class OrderNotFoundForChargeException(chargeId: String) :
    RuntimeException("No payment order for charge $chargeId")

@Service
class PaymentChargeService(
    private val gateway: StripeGateway,
    private val writer: PaymentOrderWriter,
) {
    private val log = LoggerFactory.getLogger(PaymentChargeService::class.java)

    fun reconcile(chargeId: String, source: String): ChargeResult {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Stripe calls must not run inside a DB transaction" }
        val charge = gateway.retrieveCharge(chargeId)
        val order = writer.findOrderForCharge(charge)
        if (order == null) {
            if (charge.orderNo == null) {
                if (source == "webhook") log.warn("[payment] charge {} is not ours, ignored (source={})", chargeId, source)
                else log.debug("[payment] charge {} is not ours, ignored (source={})", chargeId, source)
                return ChargeResult(ChargeOutcome.NOT_OURS, null)
            }
            log.error("[payment-alert] charge {} of order {} has no order (source={})", chargeId, charge.orderNo, source)
            throw OrderNotFoundForChargeException(chargeId)
        }
        val state = moneyState(charge)
        val money = "refunded ${state.refundedAmount}/${charge.amount}, dispute ${state.dispute}"
        val orderId = order.id ?: error("order not persisted")
        val outcome = writer.applyMoneyState(orderId, chargeId, state, "$money ($source)")
        when (outcome) {
            ChargeOutcome.NOT_SETTLED ->
                throw ChargeNotSettledException("charge $chargeId: order ${order.orderNo} is not settled (${order.status}), retry later")
            ChargeOutcome.UNCHANGED -> {}
            else ->
                // A fix made by the job means the webhook that should have made it never came.
                if (source != "webhook" && (outcome == ChargeOutcome.REVERSED || outcome == ChargeOutcome.REINSTATED)) {
                    log.error("[payment-alert] reconcile fixed charge {} order {} {}: {} (source={})", chargeId, order.orderNo, outcome, money, source)
                } else {
                    log.info("[payment] charge {} order {} {}: {} (source={})", chargeId, order.orderNo, outcome, money, source)
                }
        }
        return ChargeResult(outcome, orderId)
    }
}
