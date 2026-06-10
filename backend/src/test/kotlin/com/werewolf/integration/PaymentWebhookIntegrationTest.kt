package com.werewolf.integration

import com.stripe.Stripe
import com.stripe.model.Event
import com.stripe.net.ApiResource
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentService
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Webhook fulfillment idempotency on the real H2 schema: duplicate event ids
 * are dropped, duplicate fulfillment of the same order is a no-op, and the
 * wallet is credited exactly once. Events are built from raw Stripe JSON
 * (same shape `stripe trigger checkout.session.completed` delivers).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentWebhookIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var restTemplate: TestRestTemplate

    private fun sessionCompletedEvent(eventId: String, sessionId: String): Event {
        val json = """
            {
              "id": "$eventId",
              "object": "event",
              "api_version": "${Stripe.API_VERSION}",
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "$sessionId",
                  "object": "checkout.session",
                  "payment_intent": "pi_test_123"
                }
              }
            }
        """.trimIndent()
        return ApiResource.GSON.fromJson(json, Event::class.java)
    }

    private fun seedOrder(): Pair<String, PaymentOrder> {
        val userId = "google:test-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "Buyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(
                Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199),
            )
        }
        val order = paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = product.id ?: error("no product id"),
                credits = 100,
                amountCents = 199,
                currency = "usd",
            ).also { it.stripeSessionId = "cs_test_${UUID.randomUUID()}" },
        )
        return userId to order
    }

    @Test
    fun `completed session credits the wallet exactly once, replays are no-ops`() {
        val (userId, order) = seedOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")

        // First delivery fulfills
        paymentService.handleEvent(sessionCompletedEvent("evt_1_$sessionId", sessionId))
        assertThat(walletService.balance(userId)).isEqualTo(100)
        val fulfilled = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()
        assertThat(fulfilled.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(fulfilled.stripePaymentIntentId).isEqualTo("pi_test_123")

        // Same event id again (Stripe retry) → dedup, no double credit
        paymentService.handleEvent(sessionCompletedEvent("evt_1_$sessionId", sessionId))
        assertThat(walletService.balance(userId)).isEqualTo(100)

        // Different event id, same session (replayed trigger) → conditional
        // CREATED→COMPLETED update already consumed, still no double credit
        paymentService.handleEvent(sessionCompletedEvent("evt_2_$sessionId", sessionId))
        assertThat(walletService.balance(userId)).isEqualTo(100)
    }

    @Test
    fun `expired session marks the order EXPIRED without crediting`() {
        val (userId, order) = seedOrder()
        val sessionId = order.stripeSessionId ?: error("no session id")
        val json = """
            {
              "id": "evt_exp_$sessionId",
              "object": "event",
              "api_version": "${Stripe.API_VERSION}",
              "type": "checkout.session.expired",
              "data": { "object": { "id": "$sessionId", "object": "checkout.session" } }
            }
        """.trimIndent()
        paymentService.handleEvent(ApiResource.GSON.fromJson(json, Event::class.java))

        assertThat(paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow().status)
            .isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(userId)).isEqualTo(0)
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
