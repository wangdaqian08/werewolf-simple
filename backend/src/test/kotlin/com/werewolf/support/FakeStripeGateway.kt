package com.werewolf.support

import com.stripe.exception.ApiConnectionException
import com.werewolf.service.CheckoutRequest
import com.werewolf.service.CheckoutSnapshot
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
        sessions.clear(); createdAt.clear(); sessionByIdempotencyKey.clear(); customers.clear(); createCalls.clear()
        retrieveCalls = 0; expireCalls = 0; listCalls = 0; createCustomerCalls = 0
        failCreate = false; failRetrieve = false; failExpire = false; expireKeepsOpen = false
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

    private fun session(id: String) =
        sessions[id] ?: throw StripeResourceMissingException(Resource.SESSION, id)

    private fun nowSec() = System.currentTimeMillis() / 1000
}
