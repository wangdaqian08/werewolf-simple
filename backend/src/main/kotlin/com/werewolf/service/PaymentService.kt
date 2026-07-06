package com.werewolf.service

import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import com.werewolf.config.PaymentProperties
import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.PaymentEventRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

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
    private val paymentEventRepository: PaymentEventRepository,
    private val walletService: WalletService,
    private val props: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(PaymentService::class.java)

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

    @Transactional
    fun createCheckout(userId: String, productKey: String): String {
        if (userId.startsWith("guest:"))
            throw GuestPurchaseException("Purchases require a signed-in account")
        if (props.stripeSecretKey.isBlank())
            throw PaymentsUnavailableException("Payments are not configured")

        val product = productRepository.findByProductKeyAndActiveTrue(productKey).orElse(null)
            ?: throw ProductNotFoundException("Unknown product: $productKey")

        val order = paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = product.id ?: error("product not persisted"),
                credits = product.credits + product.bonusCredits,
                amountCents = product.priceCents,
                currency = product.currency,
            ),
        )

        val params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setClientReferenceId(order.orderNo)
            .putMetadata("orderNo", order.orderNo)
            .putMetadata("userId", userId)
            .setSuccessUrl("${props.frontendBaseUrl}/pay/result?status=success&orderNo=${order.orderNo}")
            .setCancelUrl("${props.frontendBaseUrl}/pay/result?status=cancel")
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setQuantity(1)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(product.currency)
                            .setUnitAmount(product.priceCents.toLong())
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName(product.name)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        val session = Session.create(params)
        order.stripeSessionId = session.id
        paymentOrderRepository.save(order)
        log.info("[payment] checkout created order={} user={} product={} session={}",
            order.orderNo, userId, productKey, session.id)
        return session.url
    }

    /**
     * Handle a verified Stripe event. Idempotent at two levels: event-id
     * dedup (payment_events insert-first) and the CREATED→COMPLETED
     * conditional update on the order.
     */
    @Transactional
    fun handleEvent(event: Event) {
        if (paymentEventRepository.tryInsert(event.id, event.type) == 0) {
            log.info("[payment] duplicate event {} ignored", event.id)
            return
        }
        when (event.type) {
            "checkout.session.completed" -> {
                val session = extractSession(event) ?: run {
                    log.error("[payment] cannot deserialize session from event {}", event.id)
                    return
                }
                fulfill(session)
            }
            "checkout.session.expired" -> {
                val session = extractSession(event) ?: return
                paymentOrderRepository.findByStripeSessionId(session.id).ifPresent { order ->
                    if (order.status == PaymentOrderStatus.CREATED) {
                        order.status = PaymentOrderStatus.EXPIRED
                        paymentOrderRepository.save(order)
                        log.info("[payment] order {} expired", order.orderNo)
                    }
                }
            }
            else -> log.debug("[payment] ignoring event type {}", event.type)
        }
    }

    private fun fulfill(session: Session) {
        val order = paymentOrderRepository.findByStripeSessionId(session.id).orElse(null)
        if (order == null) {
            log.error("[payment] no order for session {}", session.id)
            return
        }
        val orderId = order.id ?: error("order not persisted")
        if (paymentOrderRepository.markCompletedIfCreated(orderId) == 0) {
            log.info("[payment] order {} already fulfilled/expired, skipping", order.orderNo)
            return
        }
        order.status = PaymentOrderStatus.COMPLETED
        order.stripePaymentIntentId = session.paymentIntent
        order.completedAt = LocalDateTime.now()
        paymentOrderRepository.save(order)
        val balance = walletService.credit(
            order.userId, order.credits, CreditTxType.PURCHASE,
            paymentOrderId = orderId, note = order.orderNo,
        )
        log.info("[payment] order {} fulfilled: +{} credits for {} (balance {})",
            order.orderNo, order.credits, order.userId, balance)
    }

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

    private fun extractSession(event: Event): Session? =
        event.dataObjectDeserializer.`object`.orElse(null) as? Session
}

class GuestPurchaseException(message: String) : RuntimeException(message)
class PaymentsUnavailableException(message: String) : RuntimeException(message)
class ProductNotFoundException(message: String) : RuntimeException(message)
class OrderNotFoundException(message: String) : RuntimeException(message)
