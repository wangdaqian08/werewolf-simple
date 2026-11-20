package com.werewolf.service

import com.werewolf.model.PaymentOrderStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager

/** NOT_OURS: a paid session without our orderNo / client_reference_id and no order; ignored, never an alert. */
enum class FulfillOutcome { CREDITED, ALREADY_DONE, NOT_PAID, EXPIRED, FAILED, FLAGGED, NOT_OURS }

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
        val snapshot = try {
            gateway.retrieveSession(sessionId)
        } catch (e: StripeResourceMissingException) {
            return expireUnknownSession(sessionId, source)
        }
        val outcome = when {
            snapshot.paymentStatus == "paid" || snapshot.paymentStatus == "no_payment_required" -> paid(snapshot, source)
            snapshot.status == "expired" -> {
                markTerminal(snapshot, PaymentOrderStatus.EXPIRED, source)
                FulfillOutcome.EXPIRED
            }
            else -> FulfillOutcome.NOT_PAID
        }
        val orderNo = snapshot.orderNo ?: snapshot.clientReferenceId
        if (source == "reconcile" && outcome == FulfillOutcome.CREDITED) {
            // The job credited a paid order, so the webhook (and the return page) never did.
            log.error("[payment-alert] reconcile credited session {} order {}", sessionId, orderNo)
        } else {
            log.info("[payment] fulfill session={} order={} source={} outcome={}", sessionId, orderNo, source, outcome)
        }
        return outcome
    }

    /**
     * Stripe does not know [sessionId] under this key (a test-mode session after the switch to live
     * keys), so it can never be paid with it: its CREATED order becomes EXPIRED.
     */
    fun expireUnknownSession(sessionId: String, source: String): FulfillOutcome {
        val order = writer.findOrderBySession(sessionId)
        if (order != null && writer.applyTerminal(order.id ?: error("order not persisted"), PaymentOrderStatus.EXPIRED)) {
            log.warn("[payment] session {} unknown to this Stripe key, order {} expired (source={})", sessionId, order.orderNo, source)
            return FulfillOutcome.EXPIRED
        }
        log.warn("[payment] session {} unknown to this Stripe key, order {} left {} (source={})",
            sessionId, order?.orderNo, order?.status, source)
        return FulfillOutcome.NOT_PAID
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
            if (snapshot.orderNo == null && snapshot.clientReferenceId == null) {
                // Without our identifiers it is someone else's session (stripe trigger, another integration).
                log.warn("[payment] paid session {} is not ours, ignored (source={})", snapshot.sessionId, source)
                return FulfillOutcome.NOT_OURS
            }
            log.error("[payment-alert] paid session {} has no order (source={})", snapshot.sessionId, source)
            throw e
        } catch (e: Exception) {
            log.error("[payment-alert] paid session {} could not be credited (source={})", snapshot.sessionId, source, e)
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
