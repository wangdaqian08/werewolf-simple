package com.werewolf.service

import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.net.Webhook
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps Stripe's static signature verification so tests can inject a fake.
 * Throws [SignatureVerificationException] on a bad/missing signature.
 */
@Component
class StripeWebhookVerifier(private val props: PaymentProperties) {
    open fun verify(payload: String, sigHeader: String?): Event =
        Webhook.constructEvent(payload, sigHeader, props.stripeWebhookSecret)
}

/**
 * Credit purchases via Stripe Checkout (hosted page) + webhook fulfillment.
 *
 * Order lifecycle: CREATED at checkout click → COMPLETED by the
 * checkout.session.completed webhook (conditional UPDATE = idempotent) →
 * wallet credited exactly once. EXPIRED via checkout.session.expired.
 * The success-page redirect is never trusted for fulfillment — the frontend
 * polls GET /api/payment/order/{orderNo} until the webhook has landed.
 *
 * Guests cannot buy: their "guest:" identity is forgeable (decision 3).
 */
@Service
class PaymentService(
    private val productRepository: ProductRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val userRepository: UserRepository,
    private val eventLog: PaymentEventLog,
    private val fulfillment: PaymentFulfillmentService,
    private val writer: PaymentOrderWriter,
    private val gateway: StripeGateway,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    // shortcut: in-memory per-user lock, single backend instance — upgrade to a DB lock if the backend ever runs more than one instance
    private val checkoutLocks = ConcurrentHashMap<String, Any>()

    @PostConstruct
    fun initStripe() {
        if (props.stripeSecretKey.isNotBlank()) {
            Stripe.apiKey = props.stripeSecretKey
        }
    }

    @Transactional(readOnly = true)
    fun products() = productRepository.findByActiveTrueOrderBySortOrderAsc().map {
        mapOf(
            "productKey" to it.productKey,
            "name" to it.name,
            "credits" to it.credits,
            "bonusCredits" to it.bonusCredits,
            "priceCents" to it.priceCents,
            "currency" to it.currency,
        )
    }

    /**
     * Start (or resume) a purchase without ever charging twice. Not @Transactional: every
     * Stripe call runs outside a DB transaction and [PaymentOrderWriter] commits each write.
     * A Stripe failure is a 503; an order it leaves CREATED without a session is settled by
     * reconciliation.
     */
    fun createCheckout(userId: String, productKey: String): String {
        if (userId.startsWith("guest:"))
            throw GuestPurchaseException("Purchases require a signed-in account")
        if (!props.enabled || props.stripeSecretKey.isBlank())
            throw PaymentsUnavailableException("Payments are not configured")

        val product = productRepository.findByProductKeyAndActiveTrue(productKey).orElse(null)
            ?: throw ProductNotFoundException("Unknown product: $productKey")
        val productId = product.id ?: error("product not persisted")

        // One checkout at a time per user, so two clicks cannot open two payable sessions.
        synchronized(checkoutLocks.computeIfAbsent(userId) { Any() }) {
            try {
                openSessionUrl(userId, productId)?.let {
                    log.info("[payment] checkout reused open session user={} product={}", userId, productKey)
                    return it
                }
                expireOtherSessions(userId)
                val customerId = customerIdFor(userId)
                val order = writer.createOrder(userId, product)
                val expiresAt = LocalDateTime.now().plusMinutes(props.sessionExpiryMinutes)
                val session = gateway.createCheckoutSession(
                    CheckoutRequest(
                        orderNo = order.orderNo,
                        userId = userId,
                        customerId = customerId,
                        productName = product.name,
                        currency = product.currency,
                        unitAmountCents = product.priceCents.toLong(),
                        successUrl = "${props.frontendBaseUrl}/pay/result?status=success&orderNo=${order.orderNo}",
                        cancelUrl = "${props.frontendBaseUrl}/pay/result?status=cancel",
                        expiresAtEpochSec = expiresAt.atZone(ZoneId.systemDefault()).toEpochSecond(),
                    ),
                    idempotencyKey = "checkout-${order.orderNo}",
                )
                writer.attachSession(order.id ?: error("order not persisted"), session.sessionId, expiresAt)
                log.info("[payment] checkout created order={} user={} product={} session={}",
                    order.orderNo, userId, productKey, session.sessionId)
                return session.url ?: error("new checkout session ${session.sessionId} has no url")
            } catch (e: StripeException) {
                log.warn("[payment] checkout failed at Stripe user={} product={}: {}", userId, productKey, e.message)
                throw PaymentsUnavailableException("Payments are temporarily unavailable")
            }
        }
    }

    /** URL of this user's newest unexpired order for the product, if Stripe still has its session open. */
    private fun openSessionUrl(userId: String, productId: Int): String? {
        val order = paymentOrderRepository
            .findFirstByUserIdAndProductIdAndStatusAndSessionExpiresAtAfterOrderByCreatedAtDesc(
                userId, productId, PaymentOrderStatus.CREATED, LocalDateTime.now(),
            ).orElse(null) ?: return null
        val session = gateway.retrieveSession(order.stripeSessionId ?: return null)
        return if (session.status == "open") session.url else null
    }

    /**
     * One purchase in flight per user: expire the user's other sessions, settling any that already
     * ended. A Stripe error propagates (→ 503) and a session still open after expire is refused, so a
     * new session is never opened beside one that may still be payable.
     */
    private fun expireOtherSessions(userId: String) {
        for (order in paymentOrderRepository.findByUserIdAndStatusAndStripeSessionIdIsNotNull(userId, PaymentOrderStatus.CREATED)) {
            val sessionId = order.stripeSessionId ?: continue
            val session = gateway.expireSession(sessionId)
            when (session.status) {
                "complete" -> fulfillment.fulfill(sessionId, "checkout")
                "expired" -> writer.applyTerminal(order.id ?: error("order not persisted"), PaymentOrderStatus.EXPIRED)
                else -> {
                    log.warn("[payment] session {} of order {} is still {} after expire; checkout refused",
                        sessionId, order.orderNo, session.status)
                    throw PaymentsUnavailableException("Payments are temporarily unavailable")
                }
            }
        }
    }

    /** The user's Stripe Customer, created and stored on the first purchase. */
    private fun customerIdFor(userId: String): String {
        val user = userRepository.findById(userId).orElse(null) ?: error("no user $userId")
        user.stripeCustomerId?.let { return it }
        val customerId = gateway.createCustomer(userId)
        user.stripeCustomerId = customerId
        userRepository.save(user)
        return customerId
    }

    /**
     * Handle a verified Stripe event. The event is only a trigger: its session id goes to
     * [PaymentFulfillmentService], which re-reads the session from Stripe. Every delivery is
     * logged by [PaymentEventLog]; a failure is recorded and rethrown, so the webhook answers
     * non-2xx and Stripe retries. Not @Transactional: fulfillment calls Stripe and must run
     * outside any DB transaction.
     */
    fun handleEvent(event: Event) {
        if (!eventLog.begin(event)) {
            log.info("[payment] event {} already processed, skipped", event.id)
            return
        }
        try {
            val orderId = when (event.type) {
                // expired → fulfill marks it EXPIRED from Stripe's state (or credits it, if Stripe says paid)
                "checkout.session.completed", "checkout.session.async_payment_succeeded", "checkout.session.expired" ->
                    sessionId(event).let { fulfillment.fulfill(it, "webhook"); orderIdFor(it) }
                "checkout.session.async_payment_failed" ->
                    sessionId(event).let { fulfillment.syncFailed(it, "webhook"); orderIdFor(it) }
                else -> { log.debug("[payment] ignoring event type {}", event.type); null }
            }
            eventLog.markProcessed(event.id, orderId)
        } catch (e: Exception) {
            eventLog.markFailed(event.id, e.message ?: e.javaClass.name)
            throw e
        }
    }

    private fun sessionId(event: Event): String =
        rawObjectId(event) ?: error("event ${event.id} (${event.type}) has no data.object.id")

    private fun orderIdFor(sessionId: String): Int? = paymentOrderRepository.findByStripeSessionId(sessionId).orElse(null)?.id

    /** Caller's own Stripe order history (most recent 50) for the account page. */
    @Transactional(readOnly = true)
    fun listOrders(userId: String): List<Map<String, Any?>> {
        val productNames = productRepository.findAll().associate { it.id to it.name }
        return paymentOrderRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId).map {
            mapOf(
                "orderNo" to it.orderNo,
                "productName" to (productNames[it.productId] ?: ""),
                "credits" to it.credits,
                "amountCents" to it.amountCents,
                "currency" to it.currency,
                "status" to it.status.name,
                "createdAt" to it.createdAt.toString(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun orderStatus(userId: String, orderNo: String): Map<String, Any?> {
        val order = paymentOrderRepository.findByOrderNo(orderNo).orElse(null)
            ?: throw OrderNotFoundException("Order not found")
        if (order.userId != userId) throw OrderNotFoundException("Order not found")
        return mapOf(
            "orderNo" to order.orderNo,
            "status" to order.status.name,
            "credits" to order.credits,
        )
    }
}

class GuestPurchaseException(message: String) : RuntimeException(message)
class PaymentsUnavailableException(message: String) : RuntimeException(message)
class ProductNotFoundException(message: String) : RuntimeException(message)
class OrderNotFoundException(message: String) : RuntimeException(message)
