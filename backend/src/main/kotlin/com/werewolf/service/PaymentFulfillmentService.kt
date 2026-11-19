package com.werewolf.service

import com.werewolf.model.PaymentOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class FulfillOutcome { CREDITED, ALREADY_DONE, NOT_PAID, EXPIRED, FAILED, FLAGGED }

class OrderNotFoundForSessionException(sessionId: String) :
    RuntimeException("No payment order for checkout session $sessionId")

/**
 * The only place a paid Checkout Session becomes credits. Callers: the webhook, the
 * return page's status check and the reconciliation job (`source` names which).
 *
 * Always re-reads the session from Stripe and trusts that, never the caller's payload.
 * Deliberately not @Transactional: the Stripe call stays outside the DB transaction,
 * and [PaymentOrderWriter] does the writes.
 */
@Service
class PaymentFulfillmentService(
    private val gateway: StripeGateway,
    private val writer: PaymentOrderWriter,
) {
    private val log = LoggerFactory.getLogger(PaymentFulfillmentService::class.java)

    fun fulfill(sessionId: String, source: String): FulfillOutcome {
        checkNoTransaction()
        val snapshot = gateway.retrieveSession(sessionId)
        val outcome = when {
            snapshot.paymentStatus == "paid" || snapshot.paymentStatus == "no_payment_required" -> paid(snapshot, source)
            snapshot.status == "expired" -> {
                markTerminal(snapshot, PaymentOrderStatus.EXPIRED, source)
                FulfillOutcome.EXPIRED
            }
            else -> FulfillOutcome.NOT_PAID
        }
        log.info("[payment] fulfill session={} order={} source={} outcome={}",
            sessionId, snapshot.orderNo ?: snapshot.clientReferenceId, source, outcome)
        return outcome
    }

    /**
     * Re-read the session; if Stripe says its (delayed) payment failed, CREATED → FAILED.
     * Call it only for `checkout.session.async_payment_failed`: a delayed payment still
     * clearing looks the same (complete + unpaid) as one that failed.
     */
    fun syncFailed(sessionId: String, source: String): Boolean {
        checkNoTransaction()
        val snapshot = gateway.retrieveSession(sessionId)
        // A failed delayed payment leaves the session complete but unpaid.
        val failed = snapshot.status == "complete" && snapshot.paymentStatus == "unpaid" &&
            markTerminal(snapshot, PaymentOrderStatus.FAILED, source)
        log.info("[payment] syncFailed session={} status={}/{} source={} markedFailed={}",
            sessionId, snapshot.status, snapshot.paymentStatus, source, failed)
        return failed
    }

    private fun paid(snapshot: CheckoutSnapshot, source: String): FulfillOutcome {
        val outcome = try {
            writer.applyPaid(snapshot)
        } catch (e: OrderNotFoundForSessionException) {
            log.error("[payment-alert] paid session {} has no order (source={})", snapshot.sessionId, source)
            throw e
        }
        return outcome // FLAGGED was already alerted by the writer
    }

    private fun checkNoTransaction() =
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Stripe calls must not run inside a DB transaction" }

    private fun markTerminal(snapshot: CheckoutSnapshot, status: PaymentOrderStatus, source: String): Boolean {
        val order = writer.findOrder(snapshot)
        if (order == null) {
            // No money moved, so not an alert; the session may not even be ours.
            log.warn("[payment] {} session {} has no order (source={})", status, snapshot.sessionId, source)
            return false
        }
        return writer.applyTerminal(order.id ?: error("order not persisted"), status)
    }
}
