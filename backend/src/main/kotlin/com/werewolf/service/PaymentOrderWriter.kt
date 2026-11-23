package com.werewolf.service

import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.repository.PaymentOrderRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.Session
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The DB side of payment fulfillment, one short transaction per call. Callers do their
 * Stripe network calls first (outside any transaction) and pass the result in.
 */
@Component
class PaymentOrderWriter(
    private val paymentOrderRepository: PaymentOrderRepository,
    private val walletService: WalletService,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(PaymentOrderWriter::class.java)

    /** Commit a CREATED order before Stripe is called, so Stripe never holds a session we did not save. */
    @Transactional
    fun createOrder(userId: String, product: Product): PaymentOrder =
        paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = product.id ?: error("product not persisted"),
                credits = product.credits + product.bonusCredits,
                amountCents = product.priceCents,
                currency = product.currency,
            ),
        )

    @Transactional
    fun attachSession(orderId: Int, sessionId: String, expiresAt: LocalDateTime) {
        val order = paymentOrderRepository.findById(orderId).orElseThrow { IllegalStateException("no order $orderId") }
        order.stripeSessionId = sessionId
        order.sessionExpiresAt = expiresAt
        order.updatedAt = LocalDateTime.now()
    }

    /**
     * Stripe says [snapshot] is paid. The conditional CREATED → COMPLETED update and the
     * wallet credit commit together, so concurrent callers credit at most once: the loser's
     * update waits on the row lock, then matches 0 rows.
     */
    @Transactional
    fun applyPaid(snapshot: CheckoutSnapshot): FulfillOutcome {
        val order = lookup(snapshot) ?: throw OrderNotFoundForSessionException(snapshot.sessionId)
        val orderId = order.id ?: error("order not persisted")
        if (paymentOrderRepository.markCompletedIfCreated(orderId) == 0) {
            entityManager.refresh(order) // the bulk update bypassed the loaded entity
            // Paid twice: another session, or another PaymentIntent (the stored session may not be the one that paid).
            val paidTwice = (order.stripeSessionId != null && order.stripeSessionId != snapshot.sessionId) ||
                (order.stripePaymentIntentId != null && order.stripePaymentIntentId != snapshot.paymentIntentId)
            if (order.status in PaymentOrderStatus.SETTLED && !paidTwice) return FulfillOutcome.ALREADY_DONE
            flag(order, if (paidTwice) "second payment (session ${snapshot.sessionId}, intent ${snapshot.paymentIntentId}) for ${order.status} order"
                        else "paid session ${snapshot.sessionId} but order is ${order.status}")
            return FulfillOutcome.FLAGGED
        }
        val now = LocalDateTime.now()
        order.status = PaymentOrderStatus.COMPLETED
        order.stripeSessionId = order.stripeSessionId ?: snapshot.sessionId
        order.stripePaymentIntentId = snapshot.paymentIntentId
        order.stripeChargeId = snapshot.chargeId
        order.receiptUrl = snapshot.receiptUrl
        order.completedAt = now
        order.updatedAt = now
        paymentOrderRepository.save(order)
        walletService.credit(
            order.userId, order.credits, CreditTxType.PURCHASE,
            paymentOrderId = orderId, note = order.orderNo,
        )
        return FulfillOutcome.CREDITED
    }

    /** The order [snapshot] belongs to: by session id, then client_reference_id, then metadata orderNo. */
    @Transactional(readOnly = true)
    fun findOrder(snapshot: CheckoutSnapshot): PaymentOrder? = lookup(snapshot)

    @Transactional(readOnly = true)
    fun findOrderBySession(sessionId: String): PaymentOrder? = paymentOrderRepository.findByStripeSessionId(sessionId).orElse(null)

    /** CREATED → [newStatus] (EXPIRED / FAILED); false if the order had already left CREATED. */
    @Transactional
    fun applyTerminal(orderId: Int, newStatus: PaymentOrderStatus): Boolean {
        if (paymentOrderRepository.markStatusIfCreated(orderId, newStatus) == 0) return false
        // Loaded after the bulk update, so it already carries newStatus.
        paymentOrderRepository.findById(orderId).ifPresent { it.updatedAt = LocalDateTime.now() }
        return true
    }

    /** Store the charge and receipt Stripe now reports for a COMPLETED order; never touches status or credits. */
    @Transactional
    fun storeReceipt(orderId: Int, chargeId: String?, receiptUrl: String) {
        val order = paymentOrderRepository.findById(orderId).orElseThrow { IllegalStateException("no order $orderId") }
        order.stripeChargeId = order.stripeChargeId ?: chargeId
        order.receiptUrl = receiptUrl
        order.updatedAt = LocalDateTime.now()
    }

    @Transactional
    fun applyMoneyState(orderId: Int, chargeId: String, state: MoneyState, reason: String): ChargeOutcome {
        val order = entityManager.find(PaymentOrder::class.java, orderId) ?: throw IllegalStateException("no order $orderId")
        // Refresh under the lock: open-in-view may already hold a stale instance.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE)
        entityManager.unwrap(Session::class.java).setReadOnly(order, false)
        when (order.status) {
            PaymentOrderStatus.CREATED -> return ChargeOutcome.NOT_SETTLED
            PaymentOrderStatus.EXPIRED, PaymentOrderStatus.FAILED -> {
                val outcome = if (order.needsReview) ChargeOutcome.NEEDS_REVIEW else ChargeOutcome.UNCHANGED
                log.info("[payment] order {} is {} (never credited), no credit change: {}", order.orderNo, order.status, reason)
                return outcome
            }
            else -> {}
        }
        order.stripeChargeId = order.stripeChargeId ?: chargeId
        val outcome = when {
            order.needsReview -> {
                if (order.status != state.orderStatus) {
                    log.error("[payment-alert] order {} needs review and its money state changed: {} → {} ({})",
                        order.orderNo, order.status, state.orderStatus, reason)
                }
                ChargeOutcome.NEEDS_REVIEW
            }
            state.partialRefundSeen -> {
                flag(order, "partial refund")
                ChargeOutcome.NEEDS_REVIEW
            }
            !state.creditsShouldBePresent && !order.creditsReversed -> {
                order.creditsReversed = true
                val type = if (state.dispute == DisputePhase.OPEN || state.dispute == DisputePhase.LOST) {
                    CreditTxType.DISPUTE_REVERSAL
                } else {
                    CreditTxType.PURCHASE_REVERSAL
                }
                walletService.forceDebit(order.userId, order.credits, type, orderId, note(order, reason))
                ChargeOutcome.REVERSED
            }
            state.creditsShouldBePresent && order.creditsReversed -> {
                order.creditsReversed = false
                walletService.credit(order.userId, order.credits, CreditTxType.PURCHASE_REINSTATED,
                    paymentOrderId = orderId, note = note(order, reason))
                ChargeOutcome.REINSTATED
            }
            else -> ChargeOutcome.UNCHANGED
        }
        // updatedAt moves only on a real change: the reconciliation job selects by it.
        if (order.status != state.orderStatus || outcome == ChargeOutcome.REVERSED || outcome == ChargeOutcome.REINSTATED) {
            order.updatedAt = LocalDateTime.now()
        }
        order.status = state.orderStatus
        return outcome
    }

    @Transactional(readOnly = true)
    fun findOrderForCharge(c: ChargeSnapshot): PaymentOrder? =
        paymentOrderRepository.findFirstByStripeChargeId(c.chargeId).orElse(null)
            ?: c.paymentIntentId?.let { paymentOrderRepository.findFirstByStripePaymentIntentId(it).orElse(null) }
            ?: c.orderNo?.let { paymentOrderRepository.findByOrderNo(it).orElse(null) }

    private fun note(order: PaymentOrder, reason: String) = "${order.orderNo} $reason".take(200)

    private fun lookup(snapshot: CheckoutSnapshot): PaymentOrder? =
        paymentOrderRepository.findByStripeSessionId(snapshot.sessionId).orElse(null)
            ?: snapshot.clientReferenceId?.let { paymentOrderRepository.findByOrderNo(it).orElse(null) }
            ?: snapshot.orderNo?.let { paymentOrderRepository.findByOrderNo(it).orElse(null) }

    /** Flag once, with one `[payment-alert]`; an order already flagged keeps its first reason. */
    private fun flag(order: PaymentOrder, reason: String) {
        if (order.needsReview) {
            log.info("[payment] order {} already flagged for review, not re-flagged: {}", order.orderNo, reason)
            return
        }
        log.error("[payment-alert] order {} flagged for review: {}", order.orderNo, reason)
        order.needsReview = true
        order.reviewReason = reason.take(255)
        order.updatedAt = LocalDateTime.now()
        paymentOrderRepository.save(order)
    }

    /** The order row locked for a self refund, after [check] ran on it; marks the request if not yet marked. */
    @Transactional
    fun markSelfRefund(userId: String, orderNo: String, check: (PaymentOrder) -> Unit): SelfRefundTarget {
        val order = lockedOrder(orderNo) ?: throw OrderNotFoundException("Order not found")
        if (order.userId != userId) throw OrderNotFoundException("Order not found")
        check(order)
        val fresh = order.selfRefundRequestedAt == null
        // Milliseconds: the same value after a DB round trip on any platform, so it can key the Stripe call.
        val requestedAt = order.selfRefundRequestedAt
            ?: LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).also { order.selfRefundRequestedAt = it }
        return SelfRefundTarget(
            orderId = order.id ?: error("order not persisted"),
            paymentIntentId = order.stripePaymentIntentId ?: error("order ${order.orderNo} has no PaymentIntent"),
            chargeId = order.stripeChargeId ?: error("order ${order.orderNo} has no charge"),
            requestedAt = requestedAt,
            fresh = fresh,
        )
    }

    /** Stripe refused the refund this request marked: drop the mark unless a refund got stored meanwhile. */
    @Transactional
    fun clearSelfRefund(orderId: Int) {
        val order = lockedOrder(orderId)
        if (order.selfRefundId == null) order.selfRefundRequestedAt = null
    }

    @Transactional
    fun storeSelfRefundId(orderId: Int, refundId: String) {
        lockedOrder(orderId).selfRefundId = refundId
    }

    /**
     * Settle the order's self refund from Stripe's view of it: succeeded unlocks the account,
     * failed or canceled clears the request (unlock, alert), anything else keeps the lock.
     */
    @Transactional
    fun settleSelfRefund(orderId: Int, refunds: List<RefundInfo>) {
        val order = lockedOrder(orderId)
        val refundId = order.selfRefundId ?: return
        if (order.selfRefundSettledAt != null) return
        when (refunds.firstOrNull { it.refundId == refundId }?.status) {
            "succeeded" -> order.selfRefundSettledAt = LocalDateTime.now()
            "failed", "canceled" -> {
                order.selfRefundRequestedAt = null
                order.selfRefundId = null
                log.error("[payment-alert] self-service refund {} of order {} failed", refundId, order.orderNo)
            }
            else -> {}
        }
    }

    /** The order's status and whether its self refund is settled, read fresh. */
    @Transactional(readOnly = true)
    fun selfRefundState(orderId: Int): Pair<PaymentOrderStatus, Boolean> {
        val order = entityManager.find(PaymentOrder::class.java, orderId) ?: throw IllegalStateException("no order $orderId")
        entityManager.refresh(order)
        return order.status to (order.selfRefundSettledAt != null)
    }

    private fun lockedOrder(orderNo: String): PaymentOrder? =
        paymentOrderRepository.findByOrderNo(orderNo).orElse(null)?.let { lockedOrder(it.id ?: error("order not persisted")) }

    // Refresh under the lock: open-in-view may already hold a stale instance.
    private fun lockedOrder(orderId: Int): PaymentOrder {
        val order = entityManager.find(PaymentOrder::class.java, orderId) ?: throw IllegalStateException("no order $orderId")
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE)
        entityManager.unwrap(Session::class.java).setReadOnly(order, false)
        return order
    }
}

data class SelfRefundTarget(
    val orderId: Int,
    val paymentIntentId: String,
    val chargeId: String,
    val requestedAt: LocalDateTime,
    /** This request made the mark (a resumed or concurrent request did not). */
    val fresh: Boolean,
)
