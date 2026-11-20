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
            if (order.status == PaymentOrderStatus.COMPLETED && !paidTwice) return FulfillOutcome.ALREADY_DONE
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

    /**
     * Bring the order's credits in line with Stripe's money [state] (R7): at most one corrective
     * ledger row. The row lock serializes concurrent events for one order, so the second caller
     * reads the first one's `creditsReversed` and writes nothing. CREATED is NOT_SETTLED (the caller
     * retries); EXPIRED / FAILED were never credited and are left alone. An order needing review (a partial
     * refund ever seen, or flagged for another reason) gets no automatic credit change; its status
     * still mirrors the money, and a change of it is alerted.
     */
    @Transactional
    fun applyMoneyState(orderId: Int, state: MoneyState, reason: String): ChargeOutcome {
        val order = entityManager.find(PaymentOrder::class.java, orderId) ?: throw IllegalStateException("no order $orderId")
        // Lock AND re-read: with open-in-view the EntityManager may already hold this order, loaded before the lock.
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE)
        entityManager.unwrap(Session::class.java).setReadOnly(order, false) // a read-only instance would never be flushed
        when (order.status) {
            PaymentOrderStatus.CREATED -> return ChargeOutcome.NOT_SETTLED
            PaymentOrderStatus.EXPIRED, PaymentOrderStatus.FAILED -> {
                // Never credited, so there is nothing to take back or give back; the status stays.
                val outcome = if (order.needsReview) ChargeOutcome.NEEDS_REVIEW else ChargeOutcome.UNCHANGED
                log.info("[payment] order {} is {} (never credited), no credit change: {}", order.orderNo, order.status, reason)
                return outcome
            }
            else -> {}
        }
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
                walletService.forceDebit(order.userId, order.credits, CreditTxType.PURCHASE_REVERSAL, orderId, note(order, reason))
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
        order.status = state.orderStatus
        order.updatedAt = LocalDateTime.now()
        return outcome
    }

    /** The order [c] belongs to: by charge id, then PaymentIntent id, then PaymentIntent metadata orderNo. */
    @Transactional(readOnly = true)
    fun findOrderForCharge(c: ChargeSnapshot): PaymentOrder? =
        paymentOrderRepository.findFirstByStripeChargeId(c.chargeId).orElse(null)
            ?: c.paymentIntentId?.let { paymentOrderRepository.findFirstByStripePaymentIntentId(it).orElse(null) }
            ?: c.orderNo?.let { paymentOrderRepository.findByOrderNo(it).orElse(null) }

    private fun note(order: PaymentOrder, reason: String) = "${order.orderNo} $reason".take(200)

    @Transactional
    fun flagForReview(orderId: Int, reason: String) {
        flag(paymentOrderRepository.findById(orderId).orElseThrow { IllegalStateException("no order $orderId") }, reason)
    }

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
}
