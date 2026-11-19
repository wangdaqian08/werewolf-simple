package com.werewolf.integration

import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.net.ApiResource
import com.stripe.net.Webhook
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentEventStatus
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentEventRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.CheckoutSnapshot
import com.werewolf.service.OrderNotFoundForSessionException
import com.werewolf.service.PaymentService
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.*

/**
 * The webhook is only a trigger: it takes the session id from the event and calls the one
 * fulfillment path, which re-reads the session from (fake) Stripe. Every delivery is
 * recorded in payment_events, and a failed delivery stays visible and is retried.
 */
@PaymentIntegrationTest
class PaymentWebhookIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var paymentEventRepository: PaymentEventRepository
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var props: PaymentProperties
    @Autowired lateinit var restTemplate: TestRestTemplate

    /** Event JSON as Stripe delivers it. The payload is deliberately minimal: fulfillment must not need it. */
    private fun eventJson(type: String, eventId: String, sessionId: String, apiVersion: String = Stripe.API_VERSION) = """
        {
          "id": "$eventId",
          "object": "event",
          "api_version": "$apiVersion",
          "type": "$type",
          "data": { "object": { "id": "$sessionId", "object": "checkout.session" } }
        }
    """.trimIndent()

    private fun event(type: String, eventId: String, sessionId: String, apiVersion: String = Stripe.API_VERSION): Event =
        ApiResource.GSON.fromJson(eventJson(type, eventId, sessionId, apiVersion), Event::class.java)

    private fun completed(eventId: String, sessionId: String) = event("checkout.session.completed", eventId, sessionId)

    private fun newUser(): String = "google:test-${UUID.randomUUID()}".also {
        userRepository.save(User(userId = it, nickname = "Buyer"))
    }

    private fun seedOrder(userId: String = newUser(), orderNo: String = UUID.randomUUID().toString(),
                          sessionId: String = "cs_test_${UUID.randomUUID()}"): PaymentOrder {
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199))
        }
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = orderNo,
                userId = userId,
                productId = product.id ?: error("no product id"),
                credits = 100,
                amountCents = 199,
                currency = "usd",
            ).also { it.stripeSessionId = sessionId },
        )
    }

    /** An open session in fake Stripe for [orderNo]. */
    private fun openSession(sessionId: String, orderNo: String) = gateway.put(
        CheckoutSnapshot(
            sessionId = sessionId, status = "open", paymentStatus = "unpaid",
            orderNo = orderNo, clientReferenceId = orderNo,
            paymentIntentId = null, chargeId = null, receiptUrl = null,
            url = "https://checkout.stripe.test/$sessionId", expiresAtEpochSec = null,
        ),
    )

    /** A seeded order whose session fake Stripe reports as paid. */
    private fun paidOrder(): PaymentOrder = seedOrder().also {
        val sessionId = it.stripeSessionId ?: error("no session id")
        openSession(sessionId, it.orderNo)
        gateway.markPaid(sessionId)
    }

    private fun reload(order: PaymentOrder) = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()
    private fun eventRow(eventId: String) = paymentEventRepository.findById(eventId).orElseThrow()

    @Test
    fun `completed session credits once, a replay of the same event is skipped and logged once`() {
        val order = paidOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        val eventId = "evt_1_$sessionId"

        paymentService.handleEvent(completed(eventId, sessionId))
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        val done = reload(order)
        assertThat(done.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(done.stripePaymentIntentId).isEqualTo("pi_$sessionId")

        // Stripe retry of the same event → skipped
        paymentService.handleEvent(completed(eventId, sessionId))
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        val row = eventRow(eventId)
        assertThat(row.status).isEqualTo(PaymentEventStatus.PROCESSED)
        assertThat(row.attempts).isEqualTo(1)
        assertThat(row.objectId).isEqualTo(sessionId)
        assertThat(row.paymentOrderId).isEqualTo(order.id)
        assertThat(row.processedAt).isNotNull()

        // A different event for the same session → the order is already done, still one credit
        paymentService.handleEvent(event("checkout.session.async_payment_succeeded", "evt_2_$sessionId", sessionId))
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
    }

    @Test
    fun `order not found fails the event, which is credited once the order exists and Stripe re-delivers`() {
        val userId = newUser()
        val orderNo = UUID.randomUUID().toString()
        val sessionId = "cs_test_${UUID.randomUUID()}"
        openSession(sessionId, orderNo)
        gateway.markPaid(sessionId)
        val eventId = "evt_nf_$sessionId"

        assertThatThrownBy { paymentService.handleEvent(completed(eventId, sessionId)) }
            .isInstanceOf(OrderNotFoundForSessionException::class.java)
        val failed = eventRow(eventId)
        assertThat(failed.status).isEqualTo(PaymentEventStatus.FAILED)
        assertThat(failed.lastError).contains(sessionId)
        assertThat(failed.attempts).isEqualTo(1)

        val order = seedOrder(userId = userId, orderNo = orderNo, sessionId = sessionId)
        paymentService.handleEvent(completed(eventId, sessionId))

        assertThat(walletService.balance(userId)).isEqualTo(100)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        val processed = eventRow(eventId)
        assertThat(processed.status).isEqualTo(PaymentEventStatus.PROCESSED)
        assertThat(processed.attempts).isEqualTo(2)
    }

    @Test
    fun `an event from an older API version is still credited - the session id comes from the raw JSON`() {
        val order = paidOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        val old = event("checkout.session.completed", "evt_old_$sessionId", sessionId, apiVersion = "2020-08-27")
        assertThat(old.dataObjectDeserializer.`object`).isEmpty // the trap the raw JSON avoids

        paymentService.handleEvent(old)

        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
    }

    @Test
    fun `completed but unpaid waits, async_payment_succeeded then credits`() {
        val order = seedOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        openSession(sessionId, order.orderNo)
        gateway.put(gateway.retrieveSession(sessionId).copy(status = "complete")) // delayed method: complete, unpaid

        paymentService.handleEvent(completed("evt_c_$sessionId", sessionId))
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(eventRow("evt_c_$sessionId").status).isEqualTo(PaymentEventStatus.PROCESSED)

        gateway.markPaid(sessionId)
        paymentService.handleEvent(event("checkout.session.async_payment_succeeded", "evt_s_$sessionId", sessionId))
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
    }

    @Test
    fun `expired event marks the order EXPIRED without crediting`() {
        val order = seedOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        openSession(sessionId, order.orderNo)
        gateway.expireSession(sessionId)

        paymentService.handleEvent(event("checkout.session.expired", "evt_exp_$sessionId", sessionId))

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
        assertThat(eventRow("evt_exp_$sessionId").status).isEqualTo(PaymentEventStatus.PROCESSED)
    }

    @Test
    fun `async payment failed marks the order FAILED without crediting`() {
        val order = seedOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        openSession(sessionId, order.orderNo)
        gateway.put(gateway.retrieveSession(sessionId).copy(status = "complete")) // failed delayed payment

        paymentService.handleEvent(event("checkout.session.async_payment_failed", "evt_f_$sessionId", sessionId))

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.FAILED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `an unhandled event type is recorded as processed without calling Stripe`() {
        val retrievesBefore = gateway.retrieveCalls
        val eventId = "evt_other_${UUID.randomUUID()}"

        paymentService.handleEvent(event("customer.created", eventId, "cus_${UUID.randomUUID()}"))

        assertThat(eventRow(eventId).status).isEqualTo(PaymentEventStatus.PROCESSED)
        assertThat(gateway.retrieveCalls).isEqualTo(retrievesBefore)
    }

    @Test
    fun `webhook endpoint rejects bad signatures with 400`() {
        val headers = HttpHeaders().also {
            it.contentType = MediaType.APPLICATION_JSON
            it.set("Stripe-Signature", "t=1,v1=bogus")
        }
        val resp = restTemplate.postForEntity(
            "/api/payment/webhook",
            HttpEntity("""{"id":"evt_fake"}""", headers),
            Map::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun postSigned(payload: String): org.springframework.http.ResponseEntity<String> {
        // Same HMAC scheme Stripe uses: v1 = HMAC-SHA256(secret, "<ts>.<payload>")
        val ts = System.currentTimeMillis() / 1000
        val signature = Webhook.Util.computeHmacSha256(props.stripeWebhookSecret, "$ts.$payload")
        val headers = HttpHeaders().also {
            it.contentType = MediaType.APPLICATION_JSON
            it.set("Stripe-Signature", "t=$ts,v1=$signature")
        }
        return restTemplate.postForEntity("/api/payment/webhook", HttpEntity(payload, headers), String::class.java)
    }

    @Test
    fun `correctly signed webhook is accepted and fulfills the order`() {
        val order = paidOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")

        val resp = postSigned(eventJson("checkout.session.completed", "evt_signed_$sessionId", sessionId))

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        val fulfilled = reload(order)
        assertThat(fulfilled.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(fulfilled.stripePaymentIntentId).isEqualTo("pi_$sessionId")
    }

    @Test
    fun `signed webhook whose processing fails answers non-2xx so Stripe retries`() {
        val sessionId = "cs_test_${UUID.randomUUID()}"
        openSession(sessionId, UUID.randomUUID().toString())
        gateway.markPaid(sessionId) // paid, but no order

        val resp = postSigned(eventJson("checkout.session.completed", "evt_signed_nf_$sessionId", sessionId))

        assertThat(resp.statusCode.is5xxServerError).withFailMessage("status %s", resp.statusCode).isTrue()
        assertThat(eventRow("evt_signed_nf_$sessionId").status).isEqualTo(PaymentEventStatus.FAILED)
    }

    @Test
    fun `guest accounts cannot create a checkout`() {
        // Login as guest via the real endpoint, then hit checkout
        @Suppress("UNCHECKED_CAST")
        val login = restTemplate.postForEntity(
            "/api/user/login", mapOf("nickname" to "CheapSkate"), Map::class.java,
        ).body as Map<String, Any?>
        val token = login["token"] as String

        val headers = HttpHeaders().also {
            it.setBearerAuth(token)
            it.contentType = MediaType.APPLICATION_JSON
        }
        val resp = restTemplate.postForEntity(
            "/api/payment/checkout",
            HttpEntity(mapOf("productKey" to "starter"), headers),
            Map::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

}
