package com.werewolf.integration.controller

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.FIELD_USER
import com.werewolf.integration.TestConstants.FIELD_USER_ID
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * GET /api/payment/orders contract: owner-scoped list of Stripe orders with
 * all 7 fields, most-recent first, and unauthenticated rejection.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaymentControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository

    companion object {
        const val ORDERS_URL = "/api/payment/orders"
    }

    private lateinit var product: Product

    @BeforeEach
    fun seedProduct() {
        product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(
                Product(productKey = "starter", name = "Starter Pack", credits = 100, priceCents = 199),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun login(nickname: String): Pair<String, String> {
        val body = restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to nickname), Map::class.java).body!!
        val token = body[FIELD_TOKEN] as String
        val userId = (body[FIELD_USER] as Map<*, *>)[FIELD_USER_ID] as String
        return token to userId
    }

    private fun authHeaders(token: String) = HttpHeaders().also { it.setBearerAuth(token) }

    private fun saveOrder(userId: String, status: PaymentOrderStatus): PaymentOrder {
        val productId = product.id ?: error("product not persisted")
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = productId,
                credits = product.credits,
                amountCents = product.priceCents,
                currency = "usd",
                status = status,
            ),
        )
    }

    @Test
    fun `GET payment-orders returns only the caller's orders with all 7 fields`() {
        val (tokenA, userIdA) = login("PayOrdA")
        val (_, userIdB) = login("PayOrdB")

        saveOrder(userIdA, PaymentOrderStatus.COMPLETED)
        saveOrder(userIdB, PaymentOrderStatus.CREATED)

        val resp = restTemplate.exchange(
            ORDERS_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(tokenA)), List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = resp.body!! as List<Map<String, Any?>>

        // Only A's order returned, not B's
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["orderNo"]).isNotNull()
        assertThat(row["productName"]).isEqualTo("Starter Pack")
        assertThat(row["credits"]).isEqualTo(100)
        assertThat(row["amountCents"]).isEqualTo(199)
        assertThat(row["currency"]).isEqualTo("usd")
        assertThat(row["status"]).isEqualTo("COMPLETED")
        assertThat(row["createdAt"]).isNotNull()
    }

    @Test
    fun `GET payment-orders returns rows in most-recent-first order`() {
        val (token, userId) = login("PayOrdOrder")
        saveOrder(userId, PaymentOrderStatus.COMPLETED)
        saveOrder(userId, PaymentOrderStatus.CREATED)

        val resp = restTemplate.exchange(
            ORDERS_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(token)), List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = resp.body!! as List<Map<String, Any?>>
        assertThat(rows).hasSize(2)
        val timestamps = rows.map { it["createdAt"] as String }
        assertThat(timestamps).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `GET payment-orders caps the response at 50 rows`() {
        val (token, userId) = login("PayOrdCap")
        repeat(51) { saveOrder(userId, PaymentOrderStatus.COMPLETED) }

        val resp = restTemplate.exchange(
            ORDERS_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(token)), List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!).hasSize(50)
    }

    @Test
    fun `GET payment-orders returns 200 and an empty list when the caller has no orders`() {
        val (token, _) = login("PayOrdEmpty")

        val resp = restTemplate.exchange(
            ORDERS_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(token)), List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!).isEmpty()
    }

    @Test
    fun `GET payment-orders with a guest token returns 200 and an empty list, not 403`() {
        // Checkout blocks "guest:" identities with 403 (GuestPurchaseException);
        // the read-only order history must NOT inherit that block.
        val (token, userId) = login("PayOrdGuest")
        assertThat(userId).startsWith("guest:")

        val resp = restTemplate.exchange(
            ORDERS_URL, HttpMethod.GET, HttpEntity<Nothing>(authHeaders(token)), List::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body!!).isEmpty()
    }

    @Test
    fun `GET payment-orders without token is rejected with 401 or 403`() {
        val resp = restTemplate.getForEntity(ORDERS_URL, Map::class.java)
        assertThat(resp.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)
    }
}
