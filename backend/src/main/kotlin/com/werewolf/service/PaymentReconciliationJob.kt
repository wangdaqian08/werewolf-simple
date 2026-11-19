package com.werewolf.service

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** What one reconcile run did. alerts = `[payment-alert]` lines this job logged (money taken, no order). */
data class ReconcileReport(val credited: Int, val expired: Int, val failed: Int, val backfilled: Int, val alerts: Int)

/**
 * The fallback that credits a paid order even if every webhook is lost. Runs once at startup
 * (Q9: payments made while the home PC was offline) and every reconcile-interval-ms after:
 *  1. CREATED orders older than reconcile-min-age-minutes: settle from Stripe (no session → FAILED, but
 *     only after session-expiry-minutes more, once no unstored session can still be paid);
 *  2. COMPLETED orders without a receipt: store the charge and receipt;
 *  3. Stripe's completed sessions of the last sweep-lookback-days: fulfill any whose order is not COMPLETED.
 * Not @Transactional: fulfill calls Stripe outside any DB transaction. Each item is handled on its
 * own, so one failure never stops the run; fulfill is idempotent, so racing the webhook is harmless.
 */
@Component
class PaymentReconciliationJob(
    private val props: PaymentProperties,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val fulfillment: PaymentFulfillmentService,
    private val writer: PaymentOrderWriter,
    private val gateway: StripeGateway,
) {
    private val log = LoggerFactory.getLogger(PaymentReconciliationJob::class.java)

    // Synchronous on purpose: it finishes before the app reports ready (and before any test runs).
    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        try {
            reconcile()
        } catch (e: Exception) {
            log.error("[payment] startup reconcile failed", e) // never block startup
        }
    }

    // initialDelay = interval: the startup run above is the first one.
    @Scheduled(
        initialDelayString = "\${app.payment.reconcile-interval-ms}",
        fixedDelayString = "\${app.payment.reconcile-interval-ms}",
    )
    fun onSchedule() {
        reconcile()
    }

    fun reconcile(): ReconcileReport {
        if (!props.enabled) return ReconcileReport(0, 0, 0, 0, 0)
        val counts = Counts()
        settleStaleOrders(counts)
        backfillReceipts(counts)
        sweepStripe(counts)
        val report = ReconcileReport(counts.credited, counts.expired, counts.failed, counts.backfilled, counts.alerts)
        log.info("[payment] reconcile done {}", report)
        return report
    }

    private class Counts { var credited = 0; var expired = 0; var failed = 0; var backfilled = 0; var alerts = 0 }

    private fun settleStaleOrders(counts: Counts) {
        val now = LocalDateTime.now()
        val cutoff = now.minusMinutes(props.reconcileMinAgeMinutes)
        // A crash between Stripe creating a session and attachSession leaves a session we never stored,
        // payable until it expires; fail a session-less order only once any such session has expired.
        val noSessionCutoff = cutoff.minusMinutes(props.sessionExpiryMinutes)
        for (order in paymentOrderRepository.findByStatusAndCreatedAtBefore(PaymentOrderStatus.CREATED, cutoff)) {
            try {
                val sessionId = order.stripeSessionId
                if (sessionId != null) {
                    count(fulfillment.fulfill(sessionId, "reconcile"), counts)
                } else if (order.createdAt?.isBefore(noSessionCutoff) == true &&
                    writer.applyTerminal(order.id ?: error("order not persisted"), PaymentOrderStatus.FAILED)
                ) {
                    log.info("[payment] reconcile order={} had no session after {} min, marked FAILED",
                        order.orderNo, props.sessionExpiryMinutes + props.reconcileMinAgeMinutes)
                    counts.failed++
                }
            } catch (e: Exception) {
                log.warn("[payment] reconcile order={} failed: {}", order.orderNo, e.message)
            }
        }
    }

    private fun backfillReceipts(counts: Counts) {
        for (order in paymentOrderRepository.findByStatusAndReceiptUrlIsNull(PaymentOrderStatus.COMPLETED)) {
            val sessionId = order.stripeSessionId ?: continue
            try {
                val snapshot = gateway.retrieveSession(sessionId)
                val receiptUrl = snapshot.receiptUrl ?: continue
                writer.storeReceipt(order.id ?: error("order not persisted"), snapshot.chargeId, receiptUrl)
                log.info("[payment] reconcile order={} receipt backfilled", order.orderNo)
                counts.backfilled++
            } catch (e: Exception) {
                log.warn("[payment] receipt backfill of order={} failed: {}", order.orderNo, e.message)
            }
        }
    }

    private fun sweepStripe(counts: Counts) {
        val since = Instant.now().minus(props.sweepLookbackDays, ChronoUnit.DAYS).epochSecond
        try {
            // Only the session id and orderNo of a listed session are used; fulfill re-reads it.
            for (snapshot in gateway.listCompletedSessions(since)) {
                val orderNo = snapshot.orderNo ?: continue // not ours
                try {
                    val order = paymentOrderRepository.findByOrderNo(orderNo).orElse(null)
                    if (order == null) {
                        log.error("[payment-alert] completed session {} carries orderNo {} but there is no such order",
                            snapshot.sessionId, orderNo)
                        counts.alerts++
                    } else if (order.status != PaymentOrderStatus.COMPLETED) {
                        log.info("[payment] sweep found completed session {} for {} order={}",
                            snapshot.sessionId, order.status, orderNo)
                        count(fulfillment.fulfill(snapshot.sessionId, "reconcile"), counts)
                    }
                } catch (e: Exception) {
                    log.warn("[payment] sweep of session {} order={} failed: {}", snapshot.sessionId, orderNo, e.message)
                }
            }
        } catch (e: Exception) {
            log.warn("[payment] Stripe sweep stopped: {}", e.message) // listing failed; the next run retries
        }
    }

    private fun count(outcome: FulfillOutcome, counts: Counts) {
        when (outcome) {
            FulfillOutcome.CREDITED -> counts.credited++
            FulfillOutcome.EXPIRED -> counts.expired++
            else -> {} // fulfill logged it; FLAGGED was alerted by the writer
        }
    }
}
