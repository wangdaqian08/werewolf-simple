package com.werewolf.service

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentOrderRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** What one reconcile run did. alerts = `[payment-alert]` lines this run logged (money taken, no order; once per session). */
data class ReconcileReport(val credited: Int, val expired: Int, val failed: Int, val backfilled: Int, val alerts: Int,
    val reversed: Int = 0, val reinstated: Int = 0)

/**
 * The fallback that credits a paid order even if every webhook is lost. Runs soon after startup
 * (Q9: payments made while the home PC was offline) and every reconcile-interval-ms after:
 *  1. CREATED orders older than reconcile-min-age-minutes: settle from Stripe (no session → FAILED, but
 *     only after session-expiry-minutes more, once no unstored session can still be paid);
 *  2. COMPLETED orders without a receipt: store the charge and receipt;
 *  3. Stripe's completed sessions of the last sweep-lookback-days: fulfill any whose order is not COMPLETED
 *     (runs first, so a session paid in its last minutes is credited before a session-less order could be failed);
 *  4. refunds and disputes: re-reconcile the charges of recent refunds/disputes, of every DISPUTED order and of
 *     orders reversed within sweep-lookback-days (status changes on older ones the created-window listing misses).
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
    private val chargeService: PaymentChargeService,
) {
    private val log = LoggerFactory.getLogger(PaymentReconciliationJob::class.java)

    // Sessions already alerted as "no such order", so the sweep does not repeat it every run.
    // shortcut: re-alerts once per restart
    private val alertedSessions = ConcurrentHashMap.newKeySet<String>()
    private val alertedCharges = ConcurrentHashMap.newKeySet<String>()

    // First run shortly after startup on the scheduler thread, so a slow Stripe never delays boot.
    @Scheduled(
        initialDelayString = "\${app.payment.reconcile-initial-delay-ms}",
        fixedDelayString = "\${app.payment.reconcile-interval-ms}",
    )
    fun onSchedule() {
        reconcile()
    }

    fun reconcile(): ReconcileReport {
        if (!props.enabled) return ReconcileReport(0, 0, 0, 0, 0)
        val counts = Counts()
        sweepStripe(counts)
        settleStaleOrders(counts)
        backfillReceipts(counts)
        reconcileRefundsAndDisputes(counts)
        val report = ReconcileReport(counts.credited, counts.expired, counts.failed, counts.backfilled, counts.alerts,
            counts.reversed, counts.reinstated)
        log.info("[payment] reconcile done {}", report)
        return report
    }

    private class Counts { var credited = 0; var expired = 0; var failed = 0; var backfilled = 0; var alerts = 0; var reversed = 0; var reinstated = 0 }

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
                val snapshot = try {
                    gateway.retrieveSession(sessionId)
                } catch (e: StripeResourceMissingException) {
                    // shortcut: a pre-key-switch order is re-asked every run (DEBUG only); mark it if that ever matters
                    log.debug("[payment] receipt backfill of order={}: session {} unknown to this Stripe key", order.orderNo, sessionId)
                    continue
                }
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
                        if (alertedSessions.add(snapshot.sessionId)) {
                            log.error("[payment-alert] completed session {} carries orderNo {} but there is no such order",
                                snapshot.sessionId, orderNo)
                            counts.alerts++
                        } else {
                            log.debug("[payment] completed session {} (orderNo {}) still has no order, already alerted",
                                snapshot.sessionId, orderNo)
                        }
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

    private fun reconcileRefundsAndDisputes(counts: Counts) {
        val chargeIds = linkedSetOf<String>()
        try {
            val since = Instant.now().minus(props.sweepLookbackDays, ChronoUnit.DAYS).epochSecond
            chargeIds += gateway.listRecentChargeIdsWithRefundsOrDisputes(since)
        } catch (e: Exception) {
            log.warn("[payment] refund/dispute listing stopped: {}", e.message) // the per-order re-check below still runs
        }
        // The listing finds only refunds/disputes created in the window; re-check orders whose state may have moved since.
        val sinceLocal = LocalDateTime.now().minusDays(props.sweepLookbackDays)
        val orders = paymentOrderRepository.findByStatus(PaymentOrderStatus.DISPUTED) +
            paymentOrderRepository.findReversedUpdatedSince(sinceLocal)
        for (order in orders) {
            val chargeId = order.stripeChargeId
            if (chargeId == null) {
                log.debug("[payment] order={} ({}) has no charge id, skipped in refund/dispute re-check", order.orderNo, order.status)
            } else {
                chargeIds += chargeId
            }
        }
        for (chargeId in chargeIds) {
            if (chargeId in alertedCharges) continue // already alerted: no order for it, skip the Stripe call too
            try {
                when (chargeService.reconcileCharge(chargeId, "reconcile")) {
                    ChargeOutcome.REVERSED -> counts.reversed++
                    ChargeOutcome.REINSTATED -> counts.reinstated++
                    else -> {}
                }
            } catch (e: OrderNotFoundForChargeException) {
                // reconcile already logged the [payment-alert]; count it once per process
                if (alertedCharges.add(chargeId)) counts.alerts++
            } catch (e: IllegalStateException) {
                // NOT_SETTLED: the earlier steps credit the order first, the next run applies the refund
                log.debug("[payment] refund/dispute reconcile of charge {} skipped: {}", chargeId, e.message)
            } catch (e: Exception) {
                log.warn("[payment] refund/dispute reconcile of charge {} failed: {}", chargeId, e.message)
            }
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
