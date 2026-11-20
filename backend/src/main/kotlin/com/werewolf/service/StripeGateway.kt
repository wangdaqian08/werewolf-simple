package com.werewolf.service

import com.stripe.exception.StripeException

/** What we need from a Stripe Checkout Session, independent of stripe-java's model classes. */
data class CheckoutSnapshot(
    val sessionId: String,
    val status: String?,          // "open" | "complete" | "expired"
    val paymentStatus: String?,   // "paid" | "unpaid" | "no_payment_required"
    val orderNo: String?,         // metadata["orderNo"]
    val clientReferenceId: String?,
    val paymentIntentId: String?,
    val chargeId: String?,        // payment_intent.latest_charge.id
    val receiptUrl: String?,      // payment_intent.latest_charge.receipt_url
    val url: String?,             // hosted page URL while open
    val expiresAtEpochSec: Long?,
)

data class CheckoutRequest(
    val orderNo: String,
    val userId: String,
    val customerId: String,
    val productName: String,
    val currency: String,
    val unitAmountCents: Long,
    val successUrl: String,
    val cancelUrl: String,
    val expiresAtEpochSec: Long,
)

data class RefundInfo(val refundId: String, val amount: Long, val status: String?) // "succeeded"|"pending"|"failed"|"canceled"|"requires_action"

/** A charge's money state: what was refunded and whether it was disputed. */
data class ChargeSnapshot(
    val chargeId: String,
    val paymentIntentId: String?,
    val orderNo: String?,          // payment_intent.metadata["orderNo"]
    val amount: Long,
    val refunds: List<RefundInfo>,
    val disputeStatus: String?,    // null = never disputed
)

/** The only seam to the Stripe API; tests swap in a fake. Never call it inside a DB transaction. */
interface StripeGateway {
    fun createCheckoutSession(req: CheckoutRequest, idempotencyKey: String): CheckoutSnapshot
    fun retrieveSession(sessionId: String): CheckoutSnapshot   // expands payment_intent.latest_charge
    fun expireSession(sessionId: String): CheckoutSnapshot     // returns current state if no longer open
    fun listCompletedSessions(createdAfterEpochSec: Long): Sequence<CheckoutSnapshot> // auto-paging
    fun createCustomer(userId: String): String                 // metadata userId; returns customer id
    fun retrieveCharge(chargeId: String): ChargeSnapshot       // refunds + dispute read alongside the charge
    /** Charge ids of refunds and disputes created since the cutoff (Refund.list + Dispute.list), distinct. */
    fun listRecentChargeIdsWithRefundsOrDisputes(createdAfterEpochSec: Long): Sequence<String>
}

/**
 * Stripe answered `resource_missing`: this key has no such customer / session, e.g. a test-mode id
 * still stored after the switch to live keys. A [StripeException], so unhandled it is still a 503.
 */
class StripeResourceMissingException(val resource: Resource, id: String, cause: Throwable? = null) :
    StripeException("No such ${resource.name.lowercase()}: '$id'", null, "resource_missing", 404, cause) {
    enum class Resource { CUSTOMER, SESSION, CHARGE }
}
