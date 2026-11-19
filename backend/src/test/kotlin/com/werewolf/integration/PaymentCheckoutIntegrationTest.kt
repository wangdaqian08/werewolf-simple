package com.werewolf.integration

import com.werewolf.integration.TestConstants.FIELD_NICKNAME
import com.werewolf.integration.TestConstants.FIELD_TOKEN
import com.werewolf.integration.TestConstants.LOGIN_URL
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentService
import com.werewolf.service.PaymentsUnavailableException
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Starting a purchase cannot charge a player twice: the order is committed before Stripe
 * is called, the create call carries an idempotency key, an open session for the same
 * product is reused, and the player's other open sessions are expired first.
 */
@PaymentIntegrationTest
class PaymentCheckoutIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var jwtUtil: JwtUtil

    private lateinit var starter: Product
    private lateinit var value: Product

    @BeforeEach
    fun setUp() {
        gateway.reset()
        starter = product("starter", "Starter", credits = 100, bonus = 0, priceCents = 199)
        value = product("value", "Value", credits = 300, bonus = 30, priceCents = 499)
    }

    private fun product(key: String, name: String, credits: Int, bonus: Int, priceCents: Int) =
        productRepository.findByProductKeyAndActiveTrue(key).orElseGet {
            productRepository.save(Product(productKey = key, name = name, credits = credits, bonusCredits = bonus, priceCents = priceCents))
        }

    private fun newUser(): String = "google:checkout-${UUID.randomUUID()}".also {
        userRepository.save(User(userId = it, nickname = "Buyer"))
    }

    private fun orders(userId: String): List<PaymentOrder> = paymentOrderRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId)

    private fun reload(order: PaymentOrder) = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()

    @Test
    fun `first checkout commits one order and opens one 31-minute session with the customer and idempotency key`() {
        val userId = newUser()

        val url = paymentService.createCheckout(userId, "starter")

        val order = orders(userId).single()
        assertThat(order.status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(order.credits).isEqualTo(100)
        val (req, key) = gateway.createCalls.single()
        assertThat(key).isEqualTo("checkout-${order.orderNo}")
        assertThat(req.orderNo).isEqualTo(order.orderNo)
        assertThat(req.userId).isEqualTo(userId)
        assertThat(req.customerId).startsWith("cus_").isEqualTo(userRepository.findById(userId).orElseThrow().stripeCustomerId)
        assertThat(req.unitAmountCents).isEqualTo(199)
        val session = gateway.retrieveSession(order.stripeSessionId ?: error("order has no session"))
        assertThat(url).isEqualTo(session.url)
        val in31 = LocalDateTime.now().plusMinutes(31) // Stripe's 30-min minimum plus headroom for latency
        assertThat(order.sessionExpiresAt).isCloseTo(in31, within(5, ChronoUnit.SECONDS))
        assertThat(req.expiresAtEpochSec).isCloseTo(in31.atZone(ZoneId.systemDefault()).toEpochSecond(), within(5L))
    }

    @Test
    fun `same product again while its session is open returns the same URL and creates nothing`() {
        val userId = newUser()
        val first = paymentService.createCheckout(userId, "starter")

        val second = paymentService.createCheckout(userId, "starter")

        assertThat(second).isEqualTo(first)
        assertThat(orders(userId)).hasSize(1)
        assertThat(gateway.createCalls).hasSize(1)
        assertThat(gateway.expireCalls).isZero()
    }

    @Test
    fun `two simultaneous checkouts by one user for one product open one session and share its URL`() {
        val userId = newUser()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map { pool.submit<String> { start.await(); paymentService.createCheckout(userId, "starter") } }
        start.countDown()
        val urls = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(urls.distinct()).hasSize(1)
        assertThat(orders(userId)).hasSize(1)
        assertThat(gateway.createCalls).hasSize(1)
    }

    @Test
    fun `a different product expires the old session, marks its order EXPIRED and opens a new one`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        val old = orders(userId).single()

        val url = paymentService.createCheckout(userId, "value")

        assertThat(reload(old).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(gateway.retrieveSession(old.stripeSessionId ?: error("no session")).status).isEqualTo("expired")
        val new = orders(userId).single { it.orderNo != old.orderNo }
        assertThat(new.status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(new.productId).isEqualTo(value.id)
        assertThat(new.credits).isEqualTo(330)
        assertThat(url).isEqualTo(gateway.retrieveSession(new.stripeSessionId ?: error("no session")).url)
        assertThat(gateway.createCalls).hasSize(2)
    }

    @Test
    fun `an old session found paid at expiry time is credited, and the new session is still created`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        val old = orders(userId).single()
        gateway.markPaid(old.stripeSessionId ?: error("no session"))

        paymentService.createCheckout(userId, "value")

        assertThat(reload(old).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(userId)).isEqualTo(100)
        val new = orders(userId).single { it.orderNo != old.orderNo }
        assertThat(new.status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(new.stripeSessionId).isNotNull()
        assertThat(gateway.createCalls).hasSize(2)
    }

    @Test
    fun `the second purchase reuses the stored Stripe customer`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        paymentService.createCheckout(userId, "value")

        assertThat(gateway.createCustomerCalls).isEqualTo(1)
        assertThat(gateway.createCalls.map { it.first.customerId }.distinct()).hasSize(1)
    }

    @Test
    fun `Stripe failing on create is PaymentsUnavailable and a 503, leaving a CREATED order without a session`() {
        val userId = newUser()
        gateway.failCreate = true

        assertThatThrownBy { paymentService.createCheckout(userId, "starter") }
            .isInstanceOf(PaymentsUnavailableException::class.java)
        val order = orders(userId).single()
        assertThat(order.status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(order.stripeSessionId).isNull()

        val headers = HttpHeaders().also { it.setBearerAuth(jwtUtil.generateToken(userId, "Buyer", null)) }
        val resp = restTemplate.postForEntity("/api/payment/checkout", HttpEntity(mapOf("productKey" to "starter"), headers), Map::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `failing to expire another product's session refuses with PaymentsUnavailable - one purchase in flight`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        val old = orders(userId).single()
        gateway.failExpire = true

        assertThatThrownBy { paymentService.createCheckout(userId, "value") }
            .isInstanceOf(PaymentsUnavailableException::class.java)
        assertThat(reload(old).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(orders(userId)).hasSize(1)
        assertThat(gateway.createCalls).hasSize(1)
    }

    @Test
    fun `an old session Stripe still reports open after expire refuses with PaymentsUnavailable`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        val old = orders(userId).single()
        gateway.expireKeepsOpen = true

        assertThatThrownBy { paymentService.createCheckout(userId, "value") }
            .isInstanceOf(PaymentsUnavailableException::class.java)
        assertThat(reload(old).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(orders(userId)).hasSize(1)
        assertThat(gateway.createCalls).hasSize(1)
    }

    @Test
    fun `failing to expire a session of the same product refuses with PaymentsUnavailable - no second payable session`() {
        val userId = newUser()
        paymentService.createCheckout(userId, "starter")
        val old = orders(userId).single()
        // Our clock says the session has expired, so it is not reused; Stripe may still have it open.
        paymentOrderRepository.save(reload(old).also { it.sessionExpiresAt = LocalDateTime.now().minusMinutes(1) })
        gateway.failExpire = true

        assertThatThrownBy { paymentService.createCheckout(userId, "starter") }
            .isInstanceOf(PaymentsUnavailableException::class.java)
        assertThat(orders(userId)).hasSize(1)
        assertThat(gateway.createCalls).hasSize(1)
    }

    @Test
    fun `guest checkout is 403`() {
        @Suppress("UNCHECKED_CAST")
        val token = (restTemplate.postForEntity(LOGIN_URL, mapOf(FIELD_NICKNAME to "GuestBuyer"), Map::class.java).body
            ?: error("no login body"))[FIELD_TOKEN] as String
        val headers = HttpHeaders().also { it.setBearerAuth(token) }

        val resp = restTemplate.postForEntity("/api/payment/checkout", HttpEntity(mapOf("productKey" to "starter"), headers), Map::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(gateway.createCalls).isEmpty()
    }
}
