package com.werewolf.integration

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.param.EventListParams
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentService
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Talks to the REAL Stripe sandbox over the network. Gated by the
 * STRIPE_SANDBOX_TESTS_ENABLED feature flag (boolean — no value-matching
 * on secrets): machines without the flag skip the class cleanly. The key
 * comes from the STRIPE_SANDBOX_SECRET env var — your shell locally, a
 * GitHub Actions repository secret in CI.
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SANDBOX_TESTS_ENABLED", matches = "true")
@SpringBootTest(properties = ["app.payment.stripe-secret-key=\${STRIPE_SANDBOX_SECRET:}"])
@ActiveProfiles("test")
class StripeSandboxIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var props: PaymentProperties

    @BeforeEach
    fun guardTestModeKey() {
        // Safety assert (not gating): this class must never run against a live key.
        check(
            props.stripeSecretKey.startsWith("sk_test_") ||
                props.stripeSecretKey.startsWith("rk_test_"),
        ) { "STRIPE_SANDBOX_SECRET must be a Stripe TEST-mode key (sk_test_/rk_test_)" }
    }

    private fun seedUserAndProduct(): Pair<String, Product> {
        val userId = "google:sandbox-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "SandboxBuyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(
                Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199),
            )
        }
        return userId to product
    }

    @Test
    fun `createCheckout creates a real sandbox session with correct amount and metadata`() {
        val (userId, product) = seedUserAndProduct()

        val url = paymentService.createCheckout(userId, product.productKey)

        assertThat(url).contains("checkout.stripe.com")
        val order = paymentOrderRepository.findAll().single { it.userId == userId }
        val sessionId = order.stripeSessionId ?: error("order has no session id")

        val session = Session.retrieve(sessionId)
        assertThat(session.mode).isEqualTo("payment")
        assertThat(session.amountTotal).isEqualTo(product.priceCents.toLong())
        assertThat(session.currency).isEqualTo(product.currency)
        assertThat(session.clientReferenceId).isEqualTo(order.orderNo)
        assertThat(session.metadata["orderNo"]).isEqualTo(order.orderNo)
        assertThat(session.metadata["userId"]).isEqualTo(userId)
        assertThat(session.successUrl).startsWith(props.frontendBaseUrl)

        session.expire() // tidy up the sandbox
    }

    @Test
    fun `a real checkout_session_expired event round-trips through handleEvent`() {
        val (userId, product) = seedUserAndProduct()
        paymentService.createCheckout(userId, product.productKey)
        val order = paymentOrderRepository.findAll().single { it.userId == userId }
        val sessionId = order.stripeSessionId ?: error("order has no session id")

        Session.retrieve(sessionId).expire()

        // Poll the real Events API until Stripe records the expiry (usually < 5 s).
        val params = EventListParams.builder()
            .setType("checkout.session.expired")
            .setLimit(50L)
            .build()
        var event: Event? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (event == null && System.currentTimeMillis() < deadline) {
            event = Event.list(params).data
                .firstOrNull { it.dataObjectDeserializer.rawJson.contains(sessionId) }
            if (event == null) Thread.sleep(2_000)
        }
        val expiredEvent = event
            ?: error("checkout.session.expired for $sessionId not visible on the Events API after 60s")

        paymentService.handleEvent(expiredEvent)

        val updated = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()
        assertThat(updated.status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(userId)).isEqualTo(0)
    }
}
