package com.werewolf.support

import com.werewolf.service.CheckoutRequest
import com.werewolf.service.CheckoutSnapshot
import com.werewolf.service.StripeGateway

/**
 * In-memory Stripe for integration tests. Create / expire / list behave like Stripe
 * (same idempotency key → same session; expire only changes an open session), and
 * every call is counted. One instance is shared by the whole payment test context,
 * so tests use unique session ids or call [reset].
 */
class FakeStripeGateway : StripeGateway {
    private val sessions = LinkedHashMap<String, CheckoutSnapshot>()
    private val createdAt = HashMap<String, Long>()
    private val sessionByIdempotencyKey = HashMap<String, String>()
    private var seq = 0

    val createCalls = mutableListOf<Pair<CheckoutRequest, String>>()
    var retrieveCalls = 0; private set
    var expireCalls = 0; private set
    var listCalls = 0; private set
    var createCustomerCalls = 0; private set

    @Synchronized
    fun reset() {
        sessions.clear(); createdAt.clear(); sessionByIdempotencyKey.clear(); createCalls.clear()
        retrieveCalls = 0; expireCalls = 0; listCalls = 0; createCustomerCalls = 0
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
        sessionByIdempotencyKey[idempotencyKey]?.let { return session(it) }
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
        return session(sessionId)
    }

    @Synchronized
    override fun expireSession(sessionId: String): CheckoutSnapshot {
        expireCalls++
        val s = session(sessionId)
        if (s.status != "open") return s
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
        return "cus_fake_${++seq}"
    }

    private fun session(id: String) =
        sessions[id] ?: throw IllegalArgumentException("No such checkout.session: '$id'") // Stripe: resource_missing

    private fun nowSec() = System.currentTimeMillis() / 1000
}
