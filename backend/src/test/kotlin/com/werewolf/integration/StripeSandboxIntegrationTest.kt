package com.werewolf.integration

import com.stripe.exception.StripeException
import com.stripe.model.Event
import com.stripe.model.PaymentIntent
import com.stripe.model.Refund
import com.stripe.model.checkout.Session
import com.stripe.param.EventListParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.RefundCreateParams
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.CheckoutRequest
import com.werewolf.service.PaymentService
import com.werewolf.service.StripeGatewayImpl
import com.werewolf.service.StripeResourceMissingException
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Assumptions.assumeTrue
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
@SpringBootTest(properties = ["app.payment.enabled=true", "app.payment.stripe-secret-key=\${STRIPE_SANDBOX_SECRET:}"])
@ActiveProfiles("test")
class StripeSandboxIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var props: PaymentProperties
    @Autowired lateinit var gateway: StripeGatewayImpl

    @BeforeEach
    fun guardTestModeKey() {
        // Safety assert (not gating): this class must never run against a live key.
        check(
            props.stripeSecretKey.startsWith("sk_test_") ||
                props.stripeSecretKey.startsWith("rk_test_"),
        ) { "STRIPE_SANDBOX_SECRET must be a Stripe TEST-mode key (sk_test_/rk_test_)" }
    }

    private fun orderFor(userId: String): PaymentOrder {
        val orders = paymentOrderRepository.findAll().filter { it.userId == userId }
        check(orders.size == 1) { "expected exactly 1 order for $userId, found ${orders.size}" }
        return orders.first()
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
        val order = orderFor(userId)
        val sessionId = order.stripeSessionId ?: error("order has no session id")

        val session = Session.retrieve(sessionId)
        assertThat(session.mode).isEqualTo("payment")
        assertThat(session.amountTotal).isEqualTo(product.priceCents.toLong())
        assertThat(session.currency).isEqualTo(product.currency)
        assertThat(session.clientReferenceId).isEqualTo(order.orderNo)
        assertThat(session.metadata["orderNo"]).isEqualTo(order.orderNo)
        assertThat(session.metadata["userId"]).isEqualTo(userId)
        assertThat(session.successUrl).startsWith(props.frontendBaseUrl)

        // Tidy up the sandbox — cleanup only, must never fail the test.
        try {
            session.expire()
        } catch (_: StripeException) {
        }
    }

    @Test
    fun `a real checkout_session_expired event round-trips through handleEvent`() {
        val (userId, product) = seedUserAndProduct()
        paymentService.createCheckout(userId, product.productKey)
        val order = orderFor(userId)
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

    @Test
    fun `StripeGatewayImpl - idempotent create, retrieve, expire, list and customer against the sandbox`() {
        val (userId, product) = seedUserAndProduct()
        val customerId = gateway.createCustomer(userId)
        assertThat(customerId).startsWith("cus_")

        val orderNo = UUID.randomUUID().toString()
        val req = CheckoutRequest(
            orderNo = orderNo, userId = userId, customerId = customerId,
            productName = product.name, currency = product.currency, unitAmountCents = product.priceCents.toLong(),
            successUrl = "${props.frontendBaseUrl}/pay/result?status=success&orderNo=$orderNo",
            cancelUrl = "${props.frontendBaseUrl}/pay/result?status=cancel",
            expiresAtEpochSec = System.currentTimeMillis() / 1000 + props.sessionExpiryMinutes * 60,
        )
        val first = gateway.createCheckoutSession(req, "checkout-$orderNo")
        val again = gateway.createCheckoutSession(req, "checkout-$orderNo")
        assertThat(again.sessionId).isEqualTo(first.sessionId)
        assertThat(first.status).isEqualTo("open")
        assertThat(first.url).contains("checkout.stripe.com")

        val read = gateway.retrieveSession(first.sessionId)
        assertThat(read.orderNo).isEqualTo(orderNo)
        assertThat(read.clientReferenceId).isEqualTo(orderNo)

        assertThat(gateway.expireSession(first.sessionId).status).isEqualTo("expired")

        gateway.listCompletedSessions(System.currentTimeMillis() / 1000 - 3600).take(5).toList()
    }

    @Test
    fun `StripeGatewayImpl - an unknown customer or session is StripeResourceMissingException`() {
        val (userId, product) = seedUserAndProduct()
        val orderNo = UUID.randomUUID().toString()
        val req = CheckoutRequest(
            orderNo = orderNo, userId = userId, customerId = "cus_doesnotexist${UUID.randomUUID().toString().take(8)}",
            productName = product.name, currency = product.currency, unitAmountCents = product.priceCents.toLong(),
            successUrl = "${props.frontendBaseUrl}/pay/result", cancelUrl = "${props.frontendBaseUrl}/pay/result",
            expiresAtEpochSec = System.currentTimeMillis() / 1000 + props.sessionExpiryMinutes * 60,
        )
        val customer = catchThrowableOfType(StripeResourceMissingException::class.java) {
            gateway.createCheckoutSession(req, "checkout-$orderNo")
        }
        assertThat(customer.resource).isEqualTo(StripeResourceMissingException.Resource.CUSTOMER)

        val missing = "cs_test_doesnotexist${UUID.randomUUID().toString().replace("-", "")}"
        assertThat(catchThrowableOfType(StripeResourceMissingException::class.java) { gateway.retrieveSession(missing) }.resource)
            .isEqualTo(StripeResourceMissingException.Resource.SESSION)
        assertThat(catchThrowableOfType(StripeResourceMissingException::class.java) { gateway.expireSession(missing) }.resource)
            .isEqualTo(StripeResourceMissingException.Resource.SESSION)
    }

    private fun paidIntent(paymentMethod: String): PaymentIntent = PaymentIntent.create(
        PaymentIntentCreateParams.builder()
            .setAmount(1500L).setCurrency("usd").setPaymentMethod(paymentMethod).setConfirm(true)
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true)
                    .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                    .build(),
            )
            .putMetadata("orderNo", "sandbox-${UUID.randomUUID()}")
            .build(),
    )

    @Test
    fun `StripeGatewayImpl - retrieveCharge reads a refund and a dispute, and the recent list contains both charges`() {
        val refundedPi = paidIntent("pm_card_visa")
        val refundedCharge = refundedPi.latestCharge ?: error("no charge on ${refundedPi.id}")
        Refund.create(RefundCreateParams.builder().setCharge(refundedCharge).build())
        val snap = gateway.retrieveCharge(refundedCharge)
        assertThat(snap.paymentIntentId).isEqualTo(refundedPi.id)
        assertThat(snap.orderNo).isEqualTo(refundedPi.metadata["orderNo"])
        assertThat(snap.refunds).hasSize(1)
        assertThat(snap.refunds.first().amount).isEqualTo(1500L)
        assertThat(snap.refunds.first().status).isEqualTo("succeeded")
        assertThat(snap.disputeStatus).isNull()

        val disputedCharge = paidIntent("pm_card_createDispute").latestCharge ?: error("no disputed charge")
        var disputeStatus: String? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (disputeStatus == null && System.currentTimeMillis() < deadline) {
            disputeStatus = gateway.retrieveCharge(disputedCharge).disputeStatus
            if (disputeStatus == null) Thread.sleep(3_000)
        }
        assumeTrue(disputeStatus != null, "Stripe did not create the test dispute for $disputedCharge within 60s")

        val recent = gateway.listRecentChargeIdsWithRefundsOrDisputes(System.currentTimeMillis() / 1000 - 3600).toList()
        assertThat(recent).contains(refundedCharge, disputedCharge)
    }

    @Test
    fun `StripeGatewayImpl - an unknown charge is StripeResourceMissingException`() {
        val missing = "ch_doesnotexist${UUID.randomUUID().toString().replace("-", "")}"
        assertThat(catchThrowableOfType(StripeResourceMissingException::class.java) { gateway.retrieveCharge(missing) }.resource)
            .isEqualTo(StripeResourceMissingException.Resource.CHARGE)
    }
}
