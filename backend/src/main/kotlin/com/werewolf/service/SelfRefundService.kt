package com.werewolf.service

import com.stripe.exception.StripeException
import com.werewolf.config.PaymentProperties
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.SelfRefundStore
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Why a self refund was refused; [reason] null means the order is outside the refund window. */
class SelfRefundNotAllowedException(val reason: SelfRefundBlock?) :
    RuntimeException("Order cannot be refunded here: ${reason ?: "NOT_ELIGIBLE"}")

/**
 * The change-of-mind refund a player starts from 充值記錄 (spec §7): check the rule, mark the
 * request with one conditional UPDATE (the DB re-checks the order and the limit), refund the
 * whole charge at Stripe, then reconcile the charge so the credits come back and the lock
 * settles in the same request. No DB transaction spans these steps.
 */
@Service
class SelfRefundService(
    private val policy: SelfRefundPolicy,
    private val store: SelfRefundStore,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val gateway: StripeGateway,
    private val chargeService: PaymentChargeService,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(SelfRefundService::class.java)

    fun request(userId: String, orderNo: String): Map<String, Any> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Stripe calls must not run inside a DB transaction" }
        if (userId.startsWith("guest:")) throw GuestPurchaseException("Refunds require a signed-in account")
        if (!props.enabled || props.stripeSecretKey.isBlank()) throw PaymentsUnavailableException("Payments are not configured")

        val order = paymentOrderRepository.findByOrderNo(orderNo).orElse(null)?.takeIf { it.userId == userId }
            ?: throw OrderNotFoundException("Order not found")
        val orderId = order.id ?: error("order not persisted")
        val paymentIntentId = order.stripePaymentIntentId ?: error("order $orderNo has no PaymentIntent")
        val chargeId = order.stripeChargeId ?: error("order $orderNo has no charge")
        // Spent credits and being in a room live in other tables: checked here only (a race ends in a negative balance, spec R2).
        val view = policy.viewsFor(userId, listOf(order))[orderId]
        if (view == null || !view.eligible) throw SelfRefundNotAllowedException(view?.reason)

        val (requestedAt, fresh) = mark(orderId, orderNo, userId)
        // One key per attempt: a retry after a failed refund must not replay Stripe's failed answer.
        val key = "self-refund-$orderNo-${requestedAt.toInstant(ZoneOffset.UTC).toEpochMilli()}"
        val refund = try {
            gateway.createRefund(paymentIntentId, orderNo, userId, key)
        } catch (e: StripeException) {
            if (fresh) store.clearMark(orderId, requestedAt)
            log.warn("[payment] self-service refund of order {} failed at Stripe: {}", orderNo, e.message)
            throw e
        }
        store.storeRefundId(orderId, refund.refundId)
        // The refund exists now; if reading it back fails, the webhook or the job settles it.
        val outcome = try {
            chargeService.reconcile(chargeId, "self-service").outcome
        } catch (e: Exception) {
            log.warn("[payment] self-service refund {} of order {} created; reconcile deferred: {}", refund.refundId, orderNo, e.message)
            null
        }
        val (status, settled) = store.state(orderId)
        log.info("[payment] self-service refund order={} user={} refund={} outcome={}", orderNo, userId, refund.refundId, outcome)
        return mapOf("status" to status.name, "refundPending" to !settled)
    }

    /** The request time that keys the Stripe call, and whether this request made the mark. */
    private fun mark(orderId: Int, orderNo: String, userId: String): Pair<LocalDateTime, Boolean> {
        // Milliseconds: the same value after a DB round trip on any platform, so it can key the Stripe call.
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS)
        val made = try {
            store.mark(orderNo, userId, now)
        } catch (e: DuplicateKeyException) {
            throw SelfRefundNotAllowedException(SelfRefundBlock.PENDING) // another order's refund is still open
        }
        if (made) return now to true
        // A concurrent tap or a crash left a mark without a refund id: resume it with its own key.
        val unfinished = store.unfinishedMark(orderId) ?: throw SelfRefundNotAllowedException(null) // changed since the check
        return unfinished to false
    }
}
