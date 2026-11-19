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
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single fulfillment path: Stripe's view of a session (from the fake gateway) is
 * turned into credits exactly once, whichever caller asks and however often.
 */
@PaymentIntegrationTest
class PaymentFulfillmentIntegrationTest {

    @Autowired lateinit var fulfillment: PaymentFulfillmentService
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository

    private fun seedOrder(withSessionId: Boolean = true): PaymentOrder {
        val userId = "google:fulfil-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "Buyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199))
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
}
