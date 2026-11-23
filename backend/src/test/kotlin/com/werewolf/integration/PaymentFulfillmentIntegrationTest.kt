package com.werewolf.integration

import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.CheckoutSnapshot
import com.werewolf.service.FulfillOutcome
import com.werewolf.service.OrderNotFoundForSessionException
import com.werewolf.service.PaymentFulfillmentService
import com.werewolf.service.PaymentOrderWriter
import com.werewolf.service.PaymentService
import com.werewolf.util.JwtUtil
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single fulfillment path: Stripe's view of a session (from the fake gateway) is
 * turned into credits exactly once, whichever caller asks and however often.
 */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class PaymentFulfillmentIntegrationTest {

    @Autowired lateinit var writer: PaymentOrderWriter
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @Autowired lateinit var fulfillment: PaymentFulfillmentService
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var jwtUtil: JwtUtil

    private fun seedOrder(withSessionId: Boolean = true): PaymentOrder {
        val userId = "google:fulfil-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "Buyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Starter", credits = 100, priceCents = 199))
        }
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = product.id ?: error("no product id"),
                credits = 100,
                amountCents = 199,
                currency = "usd",
            ).also { if (withSessionId) it.stripeSessionId = "cs_test_${UUID.randomUUID()}" },
        )
    }

    /** An open Stripe session for [order], as checkout would have created it. */
    private fun openSession(order: PaymentOrder, sessionId: String = order.stripeSessionId ?: error("no session")) =
        CheckoutSnapshot(
            sessionId = sessionId, status = "open", paymentStatus = "unpaid",
            orderNo = order.orderNo, clientReferenceId = order.orderNo,
            paymentIntentId = null, chargeId = null, receiptUrl = null,
            url = "https://checkout.stripe.test/$sessionId", expiresAtEpochSec = null,
        ).also { gateway.put(it) }

    private fun purchaseRows(userId: String) =
        creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId).filter { it.type == CreditTxType.PURCHASE }

    private fun reload(order: PaymentOrder) = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()

    @Test
    fun `paid session credits the order once and stores charge and receipt`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.markPaid(sessionId)

        assertThat(fulfillment.fulfill(sessionId, "webhook")).isEqualTo(FulfillOutcome.CREDITED)

        val done = reload(order)
        assertThat(done.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(done.stripePaymentIntentId).isEqualTo("pi_$sessionId")
        assertThat(done.stripeChargeId).isEqualTo("ch_$sessionId")
        assertThat(done.receiptUrl).isEqualTo("https://pay.stripe.com/receipts/$sessionId")
        assertThat(done.completedAt).isNotNull()
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        val rows = purchaseRows(order.userId)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().paymentOrderId).isEqualTo(order.id)
        assertThat(rows.single().note).isEqualTo(order.orderNo)
    }

    @Test
    fun `second fulfill of the same session is ALREADY_DONE and credits nothing`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.markPaid(sessionId)
        fulfillment.fulfill(sessionId, "webhook")

        assertThat(fulfillment.fulfill(sessionId, "status-check")).isEqualTo(FulfillOutcome.ALREADY_DONE)

        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
    }

    @Test
    fun `unpaid session is NOT_PAID and changes nothing`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId

        assertThat(fulfillment.fulfill(sessionId, "status-check")).isEqualTo(FulfillOutcome.NOT_PAID)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
        assertThat(purchaseRows(order.userId)).isEmpty()
    }

    @Test
    fun `expired session marks the order EXPIRED`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.expireSession(sessionId)

        assertThat(fulfillment.fulfill(sessionId, "reconcile")).isEqualTo(FulfillOutcome.EXPIRED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `order saved without a session id is found by client_reference_id and gets the session id`() {
        val order = seedOrder(withSessionId = false)
        val sessionId = "cs_test_${UUID.randomUUID()}"
        gateway.put(openSession(order, sessionId).copy(orderNo = null))
        gateway.markPaid(sessionId)

        assertThat(fulfillment.fulfill(sessionId, "reconcile")).isEqualTo(FulfillOutcome.CREDITED)

        assertThat(reload(order).stripeSessionId).isEqualTo(sessionId)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
    }

    @Test
    fun `paid session with no order throws and writes no ledger row`() {
        val sessionId = "cs_test_${UUID.randomUUID()}"
        val unknownOrderNo = UUID.randomUUID().toString()
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "open", paymentStatus = "unpaid",
                orderNo = unknownOrderNo, clientReferenceId = unknownOrderNo,
                paymentIntentId = null, chargeId = null, receiptUrl = null, url = null, expiresAtEpochSec = null,
            ),
        )
        gateway.markPaid(sessionId)
        val ledgerBefore = creditTransactionRepository.count()

        assertThatThrownBy { fulfillment.fulfill(sessionId, "webhook") }
            .isInstanceOf(OrderNotFoundForSessionException::class.java)

        assertThat(creditTransactionRepository.count()).isEqualTo(ledgerBefore)
    }

    @Test
    fun `paid session for an EXPIRED order is FLAGGED for review and not credited`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        paymentOrderRepository.save(reload(order).also { it.status = PaymentOrderStatus.EXPIRED })
        gateway.markPaid(sessionId)

        assertThat(fulfillment.fulfill(sessionId, "webhook")).isEqualTo(FulfillOutcome.FLAGGED)

        val flagged = reload(order)
        assertThat(flagged.status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(flagged.needsReview).isTrue()
        assertThat(flagged.reviewReason).isNotBlank()
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
        assertThat(purchaseRows(order.userId)).isEmpty()
    }

    @Test
    fun `two concurrent fulfills of one session credit exactly once`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.markPaid(sessionId)

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            pool.submit<FulfillOutcome> {
                start.await()
                fulfillment.fulfill(sessionId, if (it == 1) "webhook" else "status-check")
            }
        }
        start.countDown()
        val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes).containsExactlyInAnyOrder(FulfillOutcome.CREDITED, FulfillOutcome.ALREADY_DONE)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
    }

    @Test
    fun `a second paid session for an already COMPLETED order is FLAGGED - the customer paid twice`() {
        val order = seedOrder()
        val first = openSession(order).sessionId
        gateway.markPaid(first)
        fulfillment.fulfill(first, "webhook")
        val second = "cs_test_${UUID.randomUUID()}"
        openSession(order, second)
        gateway.markPaid(second)

        assertThat(fulfillment.fulfill(second, "reconcile")).isEqualTo(FulfillOutcome.FLAGGED)

        val flagged = reload(order)
        assertThat(flagged.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(flagged.stripeSessionId).isEqualTo(first)
        assertThat(flagged.needsReview).isTrue()
        assertThat(flagged.reviewReason).contains(second)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
    }

    @Test
    fun `an order already flagged is not flagged or alerted again`(output: CapturedOutput) {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        paymentOrderRepository.save(reload(order).also { it.status = PaymentOrderStatus.EXPIRED })
        gateway.markPaid(sessionId)
        fulfillment.fulfill(sessionId, "webhook")
        val firstReason = reload(order).reviewReason
        val alertsBefore = output.all.split("[payment-alert]").size

        assertThat(fulfillment.fulfill(sessionId, "reconcile")).isEqualTo(FulfillOutcome.FLAGGED)

        assertThat(reload(order).reviewReason).isEqualTo(firstReason)
        assertThat(output.all.split("[payment-alert]").size).isEqualTo(alertsBefore)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `syncFailed marks a complete-but-unpaid session's order FAILED`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.put(gateway.retrieveSession(sessionId).copy(status = "complete"))

        assertThat(fulfillment.syncFailed(sessionId, "webhook")).isTrue()

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.FAILED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `syncFailed leaves the order alone when Stripe says the session is paid`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.markPaid(sessionId)

        assertThat(fulfillment.syncFailed(sessionId, "webhook")).isFalse()

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.CREATED)
    }

    @Test
    fun `expired session with no matching order is EXPIRED and does not throw`() {
        val sessionId = "cs_test_${UUID.randomUUID()}"
        val unknownOrderNo = UUID.randomUUID().toString()
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "expired", paymentStatus = "unpaid",
                orderNo = unknownOrderNo, clientReferenceId = unknownOrderNo,
                paymentIntentId = null, chargeId = null, receiptUrl = null, url = null, expiresAtEpochSec = null,
            ),
        )

        assertThat(fulfillment.fulfill(sessionId, "webhook")).isEqualTo(FulfillOutcome.EXPIRED)
    }

    @Test
    fun `applyTerminal inside a transaction that already loaded the order does not write CREATED back`() {
        val order = seedOrder()
        val orderId = order.id ?: error("no id")

        transactionTemplate.executeWithoutResult {
            paymentOrderRepository.findById(orderId).orElseThrow() // the stale CREATED copy
            assertThat(writer.applyTerminal(orderId, PaymentOrderStatus.EXPIRED)).isTrue()
        }

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
    }

    @Test
    fun `fulfill and syncFailed refuse to run inside a DB transaction`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId

        assertThatThrownBy { transactionTemplate.executeWithoutResult { fulfillment.fulfill(sessionId, "webhook") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { transactionTemplate.executeWithoutResult { fulfillment.syncFailed(sessionId, "webhook") } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `another session paid while the order was CREATED, then the stored session paid too, is FLAGGED`() {
        val order = seedOrder()
        val stored = openSession(order).sessionId
        val other = "cs_test_${UUID.randomUUID()}"
        openSession(order, other)
        gateway.markPaid(other)
        assertThat(fulfillment.fulfill(other, "webhook")).isEqualTo(FulfillOutcome.CREDITED)
        assertThat(reload(order).stripeSessionId).isEqualTo(stored) // the session that paid is not the stored one
        gateway.markPaid(stored)

        assertThat(fulfillment.fulfill(stored, "webhook")).isEqualTo(FulfillOutcome.FLAGGED)

        assertThat(reload(order).needsReview).isTrue()
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
    }

    @Test
    fun `a paid session without our identifiers is NOT_OURS - ignored without an alert`(output: CapturedOutput) {
        val sessionId = "cs_test_${UUID.randomUUID()}"
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "open", paymentStatus = "unpaid",
                orderNo = null, clientReferenceId = null,
                paymentIntentId = null, chargeId = null, receiptUrl = null, url = null, expiresAtEpochSec = null,
            ),
        )
        gateway.markPaid(sessionId)
        val ledgerBefore = creditTransactionRepository.count()
        val alertsBefore = output.all.split("[payment-alert]").size

        assertThat(fulfillment.fulfill(sessionId, "webhook")).isEqualTo(FulfillOutcome.NOT_OURS)

        assertThat(output.all.split("[payment-alert]").size).isEqualTo(alertsBefore)
        assertThat(creditTransactionRepository.count()).isEqualTo(ledgerBefore)
    }

    @Test
    fun `return-page status check of a CREATED order whose session is paid answers COMPLETED and credits it`() {
        val order = seedOrder()
        val sessionId = openSession(order).sessionId
        gateway.markPaid(sessionId)
        val headers = HttpHeaders().also { it.setBearerAuth(jwtUtil.generateToken(order.userId, "Buyer", null)) }

        val resp = restTemplate.exchange("/api/payment/order/${order.orderNo}", HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("status")).isEqualTo("COMPLETED")
        assertThat(resp.body?.keys).containsExactlyInAnyOrder("orderNo", "status", "credits", "receiptUrl", "refund")
        assertThat(resp.body?.get("receiptUrl")).isEqualTo("https://pay.stripe.com/receipts/$sessionId")
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
    }

    @Test
    fun `two status checks within 5 s ask Stripe once`() {
        val order = seedOrder()
        openSession(order)
        val before = gateway.retrieveCalls

        assertThat(paymentService.orderStatus(order.userId, order.orderNo)["status"]).isEqualTo("CREATED")
        assertThat(paymentService.orderStatus(order.userId, order.orderNo)["status"]).isEqualTo("CREATED")

        assertThat(gateway.retrieveCalls - before).isEqualTo(1)
    }

    @Test
    fun `a status check whose Stripe call fails still answers with the stored status`() {
        val order = seedOrder()
        openSession(order)
        gateway.failRetrieve = true
        try {
            assertThat(paymentService.orderStatus(order.userId, order.orderNo)["status"]).isEqualTo("CREATED")
        } finally {
            gateway.failRetrieve = false
        }
    }

    @Test
    fun `a status check of an order whose session this Stripe key does not know marks it EXPIRED`(output: CapturedOutput) {
        val order = seedOrder() // its session is unknown to the fake: a test-mode session after the switch to live keys

        assertThat(paymentService.orderStatus(order.userId, order.orderNo)["status"]).isEqualTo("EXPIRED")
        assertThat(output.all).contains("session ${order.stripeSessionId} unknown to this Stripe key, order ${order.orderNo} expired")
    }
}
