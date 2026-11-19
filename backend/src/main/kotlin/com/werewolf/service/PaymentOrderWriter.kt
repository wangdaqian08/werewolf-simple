package com.werewolf.service

import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentOrderRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

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

    /** CREATED → [newStatus] (EXPIRED / FAILED); false if the order had already left CREATED. */
    @Transactional
    fun applyTerminal(orderId: Int, newStatus: PaymentOrderStatus): Boolean {
        if (paymentOrderRepository.markStatusIfCreated(orderId, newStatus) == 0) return false
        // Loaded after the bulk update, so it already carries newStatus.
        paymentOrderRepository.findById(orderId).ifPresent { it.updatedAt = LocalDateTime.now() }
        return true
    }

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
