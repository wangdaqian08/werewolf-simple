package com.werewolf.service

import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.net.Webhook
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.StripeResourceMissingException.Resource
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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
 * polls GET /api/payment/order/{orderNo}, which also asks Stripe (throttled) while the order is CREATED.
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
    private val chargeService: PaymentChargeService,
    private val gateway: StripeGateway,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    // shortcut: in-memory per-user lock, single backend instance — upgrade to a DB lock if the backend ever runs more than one instance
    private val checkoutLocks = ConcurrentHashMap<String, Any>()

    // When each CREATED order last asked Stripe from the return page.
    // shortcut: every checked order keeps its small entry until restart; prune if the backend ever runs for months
    private val lastStatusCheck = ConcurrentHashMap<String, Instant>()

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
            var order: PaymentOrder? = null // committed before Stripe is called; logged if Stripe then fails
            try {
                openSessionUrl(userId, productId)?.let {
                    log.info("[payment] checkout reused open session user={} product={}", userId, productKey)
                    return it
                }
                expireOtherSessions(userId)
                val customerId = customerIdFor(userId)
                order = writer.createOrder(userId, product)
                val orderNo = order.orderNo
                // Epoch seconds from Instant: a local time is ambiguous in the DST repeated hour.
                val expiresAt = Instant.now().plus(Duration.ofMinutes(props.sessionExpiryMinutes))
                fun request(customer: String) = CheckoutRequest(
                    orderNo = orderNo,
                    userId = userId,
                    customerId = customer,
                    productName = product.name,
                    currency = product.currency,
                    unitAmountCents = product.priceCents.toLong(),
                    successUrl = "${props.frontendBaseUrl}/pay/result?status=success&orderNo=$orderNo",
                    cancelUrl = "${props.frontendBaseUrl}/pay/result?status=cancel",
                    expiresAtEpochSec = expiresAt.epochSecond,
                )
                val session = try {
                    gateway.createCheckoutSession(request(customerId), idempotencyKey = "checkout-$orderNo")
                } catch (e: StripeResourceMissingException) {
                    if (e.resource != Resource.CUSTOMER) throw e
                    // A customer id from another Stripe mode (test → live switch): replace it and retry once.
                    log.warn("[payment] customer {} of user {} unknown to this Stripe key, replaced", customerId, userId)
                    userRepository.clearStripeCustomerId(userId, customerId)
                    // New key: Stripe may keep the failed result under the first one, and the params changed.
                    gateway.createCheckoutSession(request(customerIdFor(userId)), idempotencyKey = "checkout-$orderNo-new-customer")
                }
                writer.attachSession(order.id ?: error("order not persisted"), session.sessionId,
                    LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()))
                log.info("[payment] checkout created order={} user={} product={} session={}",
                    orderNo, userId, productKey, session.sessionId)
                return session.url ?: error("new checkout session ${session.sessionId} has no url")
            } catch (e: StripeException) {
                log.warn("[payment] checkout failed at Stripe user={} product={} order={}: {}",
                    userId, productKey, order?.orderNo, e.message)
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
        val sessionId = order.stripeSessionId ?: return null
        val session = try {
            gateway.retrieveSession(sessionId)
        } catch (e: StripeResourceMissingException) {
            fulfillment.expireUnknownSession(sessionId, "checkout")
            return null
        }
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
            val session = try {
                gateway.expireSession(sessionId)
            } catch (e: StripeResourceMissingException) {
                fulfillment.expireUnknownSession(sessionId, "checkout")
                continue
            }
            when (session.status) {
                "complete" -> fulfillment.fulfill(sessionId, "checkout")
                "expired" -> if (writer.applyTerminal(order.id ?: error("order not persisted"), PaymentOrderStatus.EXPIRED))
                    log.info("[payment] checkout expired order={} session={} for a new checkout", order.orderNo, sessionId)
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
                "charge.refunded" -> reconcile(event, rawObjectId(event))
                // A Refund or Dispute object: its charge. refund.* also covers card refunds that fail, which
                // charge.refund.updated is sent for only on selected payment methods.
                "charge.refund.updated", "refund.updated", "refund.failed",
                "charge.dispute.created", "charge.dispute.updated", "charge.dispute.closed" ->
                    reconcile(event, rawObjectField(event, "charge"))
                else -> { log.debug("[payment] ignoring event type {}", event.type); null }
            }
            eventLog.markProcessed(event.id, orderId)
        } catch (e: Exception) {
            runCatching { eventLog.markFailed(event.id, e.message ?: e.javaClass.name) }
                .onFailure { log.error("[payment] could not record event {} as failed", event.id, it) }
            throw e // the original failure, never the recording one
        }
    }

    // Reads Stripe, never touches a PaymentOrder entity first: the reconcile re-reads the order under its lock.
    private fun reconcile(event: Event, chargeId: String?): Int? =
        chargeService.reconcile(chargeId ?: error("event ${event.id} (${event.type}) has no charge id"), "webhook").orderId

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

    /**
     * The return page's poll. A CREATED order also asks Stripe (at most once per
     * statusCheckThrottleSeconds per order), so a paid order is credited even if the webhook
     * is late or lost. Not @Transactional: fulfill calls Stripe outside any DB transaction.
     */
    fun orderStatus(userId: String, orderNo: String): Map<String, Any?> {
        var order = paymentOrderRepository.findByOrderNo(orderNo).orElse(null)
            ?: throw OrderNotFoundException("Order not found")
        if (order.userId != userId) throw OrderNotFoundException("Order not found")
        val sessionId = order.stripeSessionId
        if (order.status == PaymentOrderStatus.CREATED && sessionId != null && claimStatusCheck(orderNo)) {
            try {
                fulfillment.fulfill(sessionId, "status-check")
            } catch (e: Exception) {
                // The page keeps polling; the webhook or reconciliation settles it.
                log.warn("[payment] status check of order {} failed: {}", orderNo, e.message)
            }
            order = paymentOrderRepository.findByOrderNo(orderNo).orElseThrow()
        }
        return mapOf(
            "orderNo" to order.orderNo,
            "status" to order.status.name,
            "credits" to order.credits,
        )
    }

    /** True (and records now) if this order's last Stripe check was at least the throttle ago. */
    private fun claimStatusCheck(orderNo: String): Boolean {
        val now = Instant.now()
        var claimed = false
        lastStatusCheck.compute(orderNo) { _, last ->
            if (last == null || Duration.between(last, now).seconds >= props.statusCheckThrottleSeconds) {
                claimed = true
                now
            } else last
        }
        return claimed
    }
}

class GuestPurchaseException(message: String) : RuntimeException(message)
class PaymentsUnavailableException(message: String) : RuntimeException(message)
class ProductNotFoundException(message: String) : RuntimeException(message)
class OrderNotFoundException(message: String) : RuntimeException(message)
