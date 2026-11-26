package com.werewolf.service

import com.werewolf.repository.SelfRefundStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

enum class ChargeOutcome { REVERSED, REINSTATED, UNCHANGED, NEEDS_REVIEW, NOT_SETTLED, NOT_OURS }

data class ChargeResult(val outcome: ChargeOutcome, val orderId: Int?)

class ChargeNotSettledException(message: String) : IllegalStateException(message)

class OrderNotFoundForChargeException(chargeId: String) :
    RuntimeException("No payment order for charge $chargeId")

@Service
class PaymentChargeService(
    private val gateway: StripeGateway,
    private val writer: PaymentOrderWriter,
    private val fulfillment: PaymentFulfillmentService,
    private val selfRefunds: SelfRefundStore,
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
        var outcome = writer.applyMoneyState(orderId, chargeId, state, "$money ($source)")
        if (outcome == ChargeOutcome.NOT_SETTLED) {
            // The charge event beat the payment's own: credit the order from its session first.
            val fulfilled = order.stripeSessionId?.let { fulfillment.fulfill(it, source) }
            if (fulfilled == FulfillOutcome.CREDITED || fulfilled == FulfillOutcome.ALREADY_DONE) {
                outcome = writer.applyMoneyState(orderId, chargeId, state, "$money ($source)")
            }
        }
        when (outcome) {
            ChargeOutcome.NOT_SETTLED ->
                throw ChargeNotSettledException("charge $chargeId: order ${order.orderNo} is not settled (${order.status}), retry later")
            ChargeOutcome.UNCHANGED -> {}
            else ->
                // A fix made by the job means the webhook that should have made it never came.
                if (source == "reconcile" && (outcome == ChargeOutcome.REVERSED || outcome == ChargeOutcome.REINSTATED)) {
                    log.error("[payment-alert] reconcile fixed charge {} order {} {}: {} (source={})", chargeId, order.orderNo, outcome, money, source)
                } else {
                    log.info("[payment] charge {} order {} {}: {} (source={})", chargeId, order.orderNo, outcome, money, source)
                }
        }
        settleSelfRefund(orderId, charge.refunds)
        return ChargeResult(outcome, orderId)
    }

    /**
     * Settle the order's self refund from Stripe's view of it: succeeded unlocks the account,
     * failed or canceled drops the request (unlock, alert once), anything else keeps the lock.
     */
    private fun settleSelfRefund(orderId: Int, refunds: List<RefundInfo>) {
        val open = selfRefunds.openRefund(orderId) ?: return
        when (refunds.firstOrNull { it.refundId == open.refundId }?.status) {
            "succeeded" -> selfRefunds.markSettled(orderId, open.refundId, LocalDateTime.now())
            "failed", "canceled" ->
                if (selfRefunds.dropFailed(orderId, open.refundId) == 1) {
                    log.error("[payment-alert] self-service refund {} of order {} failed", open.refundId, open.orderNo)
                }
            else -> {}
        }
    }
}
