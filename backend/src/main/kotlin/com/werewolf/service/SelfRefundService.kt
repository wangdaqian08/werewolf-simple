package com.werewolf.service

import com.stripe.exception.StripeException
import com.werewolf.config.PaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.ZoneOffset

/** Why a self refund was refused; [reason] null means the order is outside the refund window. */
class SelfRefundNotAllowedException(val reason: SelfRefundBlock?) :
    RuntimeException("Order cannot be refunded here: ${reason ?: "NOT_ELIGIBLE"}")

/**
 * The change-of-mind refund a player starts from 充值記錄 (spec §7): re-check the rule under the
 * order lock, mark the request (which locks the account), refund the whole charge at Stripe, then
 * reconcile the charge so the credits come back and the lock settles in the same request.
 */
@Service
class SelfRefundService(
    private val policy: SelfRefundPolicy,
    private val writer: PaymentOrderWriter,
    private val gateway: StripeGateway,
    private val chargeService: PaymentChargeService,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(SelfRefundService::class.java)

    fun request(userId: String, orderNo: String): Map<String, Any> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Stripe calls must not run inside a DB transaction" }
        if (userId.startsWith("guest:")) throw GuestPurchaseException("Refunds require a signed-in account")
        if (!props.enabled || props.stripeSecretKey.isBlank()) throw PaymentsUnavailableException("Payments are not configured")

        val target = writer.markSelfRefund(userId, orderNo) { order ->
            val view = policy.viewsFor(userId, listOf(order))[order.id]
            val resumable = view?.reason == SelfRefundBlock.PENDING && order.selfRefundId == null
            if (view == null || !(view.eligible || resumable)) throw SelfRefundNotAllowedException(view?.reason)
        }
        // One key per attempt: a retry after a failed refund must not replay Stripe's failed answer.
        val key = "self-refund-$orderNo-${target.requestedAt.toEpochSecond(ZoneOffset.UTC)}"
        val refund = try {
            gateway.createRefund(target.paymentIntentId, orderNo, userId, key)
        } catch (e: StripeException) {
            writer.clearSelfRefund(target.orderId, target.requestedAt)
            log.warn("[payment] self-service refund of order {} failed at Stripe: {}", orderNo, e.message)
            throw e
        }
        writer.storeSelfRefundId(target.orderId, refund.refundId)
        val outcome = chargeService.reconcile(target.chargeId, "self-service").outcome
        val (status, settled) = writer.selfRefundState(target.orderId)
        log.info("[payment] self-service refund order={} user={} refund={} outcome={}", orderNo, userId, refund.refundId, outcome)
        return mapOf("status" to status.name, "refundPending" to !settled)
    }
}
