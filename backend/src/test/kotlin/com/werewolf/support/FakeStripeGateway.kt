package com.werewolf.support

import com.stripe.exception.ApiConnectionException
import com.werewolf.service.CheckoutRequest
import com.werewolf.service.ChargeSnapshot
import com.werewolf.service.CheckoutSnapshot
import com.werewolf.service.RefundInfo
import com.werewolf.service.StripeGateway
import com.werewolf.service.StripeResourceMissingException
import com.werewolf.service.StripeResourceMissingException.Resource

/**
 * In-memory Stripe for integration tests. Create / expire / list behave like Stripe
 * (same idempotency key → same session; expire only changes an open session), and
 * every call is counted. A session or customer it did not create is unknown, like
 * Stripe's `resource_missing`; [reset] forgets everything, like switching Stripe keys.
 * One instance is shared by the whole payment test context, so tests use unique
 * session ids or call [reset].
 */
class FakeStripeGateway : StripeGateway {
    private val sessions = LinkedHashMap<String, CheckoutSnapshot>()
    private val createdAt = HashMap<String, Long>()
    private val sessionByIdempotencyKey = HashMap<String, String>()
    private val customers = HashSet<String>()
    private val charges = LinkedHashMap<String, ChargeSnapshot>()
    // Latest refund/dispute creation time per charge, so the listing honours the created window.
    private val moneyEventAt = HashMap<String, Long>()
    private var seq = 0

    val createCalls = mutableListOf<Pair<CheckoutRequest, String>>()
    var retrieveCalls = 0; private set
    var expireCalls = 0; private set
    var listCalls = 0; private set
    var createCustomerCalls = 0; private set

    /** When true, create / retrieve / expire fail like an unreachable Stripe. */
    var failCreate = false
    var failRetrieve = false
    var failExpire = false
    /** When true, expire returns the session unchanged (still open), as an odd Stripe reply would. */
    var expireKeepsOpen = false

    @Synchronized
    fun reset() {
        sessions.clear(); createdAt.clear(); sessionByIdempotencyKey.clear(); customers.clear(); createCalls.clear(); charges.clear(); moneyEventAt.clear()
        retrieveCalls = 0; expireCalls = 0; listCalls = 0; createCustomerCalls = 0
        failCreate = false; failRetrieve = false; failExpire = false; expireKeepsOpen = false
        failCreateRefund = false; refundStatusOnCreate = "succeeded"; createRefundCalls = 0; refundByIdempotencyKey.clear()
        createRefundKeys.clear(); failRetrieveCharge = false
    }

    /** Put (or overwrite) a session as Stripe would currently report it. */
    @Synchronized
    fun put(snapshot: CheckoutSnapshot, createdEpochSec: Long = nowSec()) {
        sessions[snapshot.sessionId] = snapshot
        createdAt.putIfAbsent(snapshot.sessionId, createdEpochSec)
    }

    /** The customer paid: the session completes with a PaymentIntent and a charge with a receipt. */
    @Synchronized
    fun markPaid(sessionId: String): CheckoutSnapshot {
        val s = session(sessionId).copy(
            status = "complete", paymentStatus = "paid", url = null,
            paymentIntentId = "pi_$sessionId", chargeId = "ch_$sessionId",
            receiptUrl = "https://pay.stripe.com/receipts/$sessionId",
        )
        sessions[sessionId] = s
        return s
    }

    @Synchronized
    override fun createCheckoutSession(req: CheckoutRequest, idempotencyKey: String): CheckoutSnapshot {
        createCalls += req to idempotencyKey
        if (failCreate) throw ApiConnectionException("fake: Stripe unreachable")
        sessionByIdempotencyKey[idempotencyKey]?.let { return session(it) }
        if (req.customerId !in customers) throw StripeResourceMissingException(Resource.CUSTOMER, req.customerId)
        val id = "cs_fake_${++seq}_${System.nanoTime()}"
        val s = CheckoutSnapshot(
            sessionId = id, status = "open", paymentStatus = "unpaid",
            orderNo = req.orderNo, clientReferenceId = req.orderNo,
            paymentIntentId = null, chargeId = null, receiptUrl = null,
            url = "https://checkout.stripe.test/$id", expiresAtEpochSec = req.expiresAtEpochSec,
        )
        put(s)
        sessionByIdempotencyKey[idempotencyKey] = id
        return s
    }

    @Synchronized
    override fun retrieveSession(sessionId: String): CheckoutSnapshot {
        retrieveCalls++
        if (failRetrieve) throw ApiConnectionException("fake: Stripe unreachable")
        return session(sessionId)
    }

    @Synchronized
    override fun expireSession(sessionId: String): CheckoutSnapshot {
        expireCalls++
        if (failExpire) throw ApiConnectionException("fake: Stripe unreachable")
        val s = session(sessionId)
        if (s.status != "open" || expireKeepsOpen) return s
        return s.copy(status = "expired", url = null).also { sessions[sessionId] = it }
    }

    @Synchronized
    override fun listCompletedSessions(createdAfterEpochSec: Long): Sequence<CheckoutSnapshot> {
        listCalls++
        return sessions.values
            .filter { it.status == "complete" && (createdAt[it.sessionId] ?: 0) >= createdAfterEpochSec }
            .toList().asSequence()
    }

    @Synchronized
    override fun createCustomer(userId: String): String {
        createCustomerCalls++
        return "cus_fake_${++seq}".also { customers += it }
    }

    @Synchronized
    fun addCharge(chargeId: String, paymentIntentId: String?, orderNo: String?, amount: Long) {
        charges[chargeId] = ChargeSnapshot(chargeId, paymentIntentId, orderNo, amount, emptyList(), null)
    }

    @Synchronized
    fun refund(chargeId: String, amount: Long, status: String = "succeeded", createdEpochSec: Long = nowSec()): String {
        moneyEventAt.merge(chargeId, createdEpochSec, ::maxOf)
        val id = "re_fake_${++seq}"
        val c = charge(chargeId)
        charges[chargeId] = c.copy(refunds = c.refunds + RefundInfo(id, amount, status))
        return id
    }

    /** createRefund: throw a Stripe error instead (the request must leave no lock). */
    var failCreateRefund = false
    /** Status Stripe gives a refund the app creates ("succeeded", or "pending" to test the lock). */
    var refundStatusOnCreate = "succeeded"
    var createRefundCalls = 0
    val createRefundKeys = mutableListOf<String>()
    /** retrieveCharge: throw a Stripe error instead (a blip after the refund exists). */
    var failRetrieveCharge = false
    private val refundByIdempotencyKey = mutableMapOf<String, RefundInfo>()

    @Synchronized
    override fun createRefund(paymentIntentId: String, orderNo: String, userId: String, idempotencyKey: String): RefundInfo {
        createRefundCalls++
        createRefundKeys += idempotencyKey
        if (failCreateRefund) throw StripeResourceMissingException(Resource.CHARGE, paymentIntentId)
        refundByIdempotencyKey[idempotencyKey]?.let { return it }
        val c = charges.values.firstOrNull { it.paymentIntentId == paymentIntentId }
            ?: throw StripeResourceMissingException(Resource.CHARGE, paymentIntentId)
        val id = refund(c.chargeId, c.amount, refundStatusOnCreate)
        return RefundInfo(id, c.amount, refundStatusOnCreate).also { refundByIdempotencyKey[idempotencyKey] = it }
    }

    @Synchronized
    fun setRefundStatus(chargeId: String, refundId: String, status: String) {
        val c = charge(chargeId)
        charges[chargeId] = c.copy(refunds = c.refunds.map { if (it.refundId == refundId) it.copy(status = status) else it })
    }

    @Synchronized
    fun openDispute(chargeId: String, status: String = "needs_response", createdEpochSec: Long = nowSec()) {
        moneyEventAt.merge(chargeId, createdEpochSec, ::maxOf)
        setDisputeStatus(chargeId, status)
    }

    @Synchronized
    fun setDisputeStatus(chargeId: String, status: String) {
        charges[chargeId] = charge(chargeId).copy(disputeStatus = status)
    }

    @Synchronized
    override fun retrieveCharge(chargeId: String): ChargeSnapshot {
        if (failRetrieveCharge) throw StripeResourceMissingException(Resource.CHARGE, chargeId)
        return charge(chargeId)
    }

    @Synchronized
    override fun listRecentChargeIdsWithRefundsOrDisputes(createdAfterEpochSec: Long): Sequence<String> =
        charges.values
            .filter { (it.refunds.isNotEmpty() || it.disputeStatus != null) && (moneyEventAt[it.chargeId] ?: 0) >= createdAfterEpochSec }
            .map { it.chargeId }.toList().asSequence()

    private fun charge(id: String) =
        charges[id] ?: throw StripeResourceMissingException(Resource.CHARGE, id)

    private fun session(id: String) =
        sessions[id] ?: throw StripeResourceMissingException(Resource.SESSION, id)

    private fun nowSec() = System.currentTimeMillis() / 1000
}
