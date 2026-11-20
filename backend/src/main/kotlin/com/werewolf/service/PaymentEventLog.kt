package com.werewolf.service

import com.stripe.model.Event
import com.werewolf.model.PaymentEventStatus
import com.werewolf.repository.PaymentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDateTime

private val mapper = jacksonObjectMapper()

/**
 * `data.object.id` from the event's raw JSON. Never from the deserialized object: an event
 * from another API version deserializes as empty.
 */
fun rawObjectId(event: Event): String? =
    event.dataObjectDeserializer.rawJson
        ?.let { mapper.readTree(it).get("id") }
        ?.takeUnless { it.isNull }?.asString()

/** A top-level string field of `data.object` from the raw JSON, e.g. a Refund's or Dispute's `charge`. */
fun rawObjectField(event: Event, field: String): String? =
    event.dataObjectDeserializer.rawJson
        ?.let { mapper.readTree(it).get(field) }
        ?.takeUnless { it.isNull }?.asString()

/**
 * One payment_events row per Stripe event, kept through every delivery. Each method commits
 * on its own (REQUIRES_NEW), so a failed delivery's row and error survive the failure.
 */
@Component
class PaymentEventLog(private val paymentEventRepository: PaymentEventRepository) {
    private val log = LoggerFactory.getLogger(PaymentEventLog::class.java)

    /** Record this delivery; false = the event was already processed, skip it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun begin(event: Event): Boolean {
        paymentEventRepository.tryInsert(event.id, event.type, rawObjectId(event))
        val row = paymentEventRepository.findById(event.id).orElseThrow { IllegalStateException("no event row ${event.id}") }
        if (row.status == PaymentEventStatus.PROCESSED) return false
        row.attempts += 1
        row.status = PaymentEventStatus.RECEIVED
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markProcessed(eventId: String, orderId: Int?) {
        val row = paymentEventRepository.findById(eventId).orElseThrow { IllegalStateException("no event row $eventId") }
        row.status = PaymentEventStatus.PROCESSED
        row.paymentOrderId = orderId ?: row.paymentOrderId
        row.processedAt = LocalDateTime.now()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(eventId: String, error: String) {
        val row = paymentEventRepository.findById(eventId).orElseThrow { IllegalStateException("no event row $eventId") }
        row.status = PaymentEventStatus.FAILED
        row.lastError = error.take(1000)
        if (row.attempts > 1) {
            log.error("[payment-alert] webhook event {} ({}) failed again, attempt {}: {}", eventId, row.eventType, row.attempts, error)
        } else {
            log.warn("[payment] webhook event {} ({}) failed, Stripe will retry: {}", eventId, row.eventType, error)
        }
    }
}
