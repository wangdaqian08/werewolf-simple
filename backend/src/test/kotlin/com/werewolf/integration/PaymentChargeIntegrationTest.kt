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
import com.werewolf.service.ChargeOutcome
import com.werewolf.service.DisputePhase
import com.werewolf.service.MoneyState
import com.werewolf.service.OrderNotFoundForChargeException
import com.werewolf.service.PaymentChargeService
import com.werewolf.service.PaymentOrderWriter
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import jakarta.persistence.EntityManagerFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Credits follow the money: Stripe's current view of a charge decides whether the purchase's credits are present. */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class PaymentChargeIntegrationTest {

    @Autowired lateinit var charges: PaymentChargeService
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var emf: EntityManagerFactory
    @Autowired lateinit var writer: PaymentOrderWriter

    private val amount = 2990L
    private val credits = 330

    @BeforeEach
    fun resetGateway() = gateway.reset()

    /** A COMPLETED order of 330 credits, credited, with 100 already spent: balance 230. Its charge is in the fake. */
    private fun seedOrder(status: PaymentOrderStatus = PaymentOrderStatus.COMPLETED): PaymentOrder {
        val userId = "google:charge-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "Buyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Starter", credits = 100, priceCents = 199))
        }
        val tag = UUID.randomUUID().toString()
        val order = paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(), userId = userId,
                productId = product.id ?: error("no product id"),
                credits = credits, amountCents = amount.toInt(), currency = "usd",
            ).also {
                it.status = status
                it.stripeSessionId = "cs_$tag"
                if (status == PaymentOrderStatus.COMPLETED) {
                    it.stripePaymentIntentId = "pi_$tag"
                    it.stripeChargeId = "ch_$tag"
                    it.receiptUrl = "https://pay.stripe.com/receipts/$tag" // as fulfillment stores it; keeps the receipt backfill away
                }
            },
        )
        if (status == PaymentOrderStatus.COMPLETED) {
            walletService.credit(userId, credits, CreditTxType.PURCHASE, paymentOrderId = order.id, note = order.orderNo)
            walletService.debit(userId, 100, CreditTxType.PERK_SPEND)
        }
        gateway.addCharge("ch_$tag", "pi_$tag", order.orderNo, amount)
        return order
    }

    private val PaymentOrder.charge get() = stripeChargeId ?: "ch_${stripeSessionId?.removePrefix("cs_")}"

    private fun reload(order: PaymentOrder) = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()

    private fun rows(order: PaymentOrder, type: CreditTxType) =
        creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(order.userId)
            .filter { it.type == type && it.paymentOrderId == order.id }

    private fun reconcile(order: PaymentOrder) = charges.reconcile(order.charge, "test").outcome

    /** Run [block] as one web request with open-in-view on (Spring's default outside prod): one shared EntityManager. */
    private fun <T> inRequest(block: () -> T): T {
        val em = emf.createEntityManager()
        TransactionSynchronizationManager.bindResource(emf, EntityManagerHolder(em))
        try {
            return block()
        } finally {
            TransactionSynchronizationManager.unbindResource(emf)
            em.close()
        }
    }

    private fun alerts(output: CapturedOutput) = output.all.split("[payment-alert]").size - 1

    @Test
    fun `full refund takes back all 330 credits, the balance goes negative, and a replay is UNCHANGED`() {
        val order = seedOrder()
        gateway.refund(order.charge, amount)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)

        assertThat(walletService.balance(order.userId)).isEqualTo(230 - 330)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(reload(order).creditsReversed).isTrue()
        val reversal = rows(order, CreditTxType.PURCHASE_REVERSAL).single()
        assertThat(reversal.amount).isEqualTo(-330)
        assertThat(reversal.note).startsWith(order.orderNo)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.UNCHANGED)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
    }

    @Test
    fun `an inquiry takes the credits back, and closing it gives them back`() {
        val order = seedOrder()
        gateway.openDispute(order.charge, "warning_needs_response")

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(rows(order, CreditTxType.DISPUTE_REVERSAL).single().amount).isEqualTo(-330)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).isEmpty()

        gateway.setDisputeStatus(order.charge, "warning_closed")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REINSTATED)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
        assertThat(rows(order, CreditTxType.PURCHASE_REINSTATED).single().amount).isEqualTo(330)
    }

    @Test
    fun `a chargeback takes the credits back, a won dispute gives them back, and a replay is UNCHANGED`() {
        val order = seedOrder()
        gateway.openDispute(order.charge, "needs_response")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)

        gateway.setDisputeStatus(order.charge, "won")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REINSTATED)
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.UNCHANGED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
        assertThat(rows(order, CreditTxType.DISPUTE_REVERSAL)).hasSize(1)
        assertThat(rows(order, CreditTxType.PURCHASE_REINSTATED)).hasSize(1)
    }

    @Test
    fun `a lost dispute keeps the credits taken`() {
        val order = seedOrder()
        gateway.openDispute(order.charge, "lost")

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTE_LOST)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
    }

    @Test
    fun `dispute won then full refund - reversed once in net, REFUNDED`() {
        val order = seedOrder()
        gateway.openDispute(order.charge, "needs_response")
        reconcile(order)
        gateway.setDisputeStatus(order.charge, "won")
        reconcile(order)
        gateway.refund(order.charge, amount)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(rows(order, CreditTxType.DISPUTE_REVERSAL)).hasSize(1)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
        assertThat(rows(order, CreditTxType.PURCHASE_REINSTATED)).hasSize(1)
    }

    @Test
    fun `full refund then a dispute opened and won - stays reversed, REFUNDED`() {
        val order = seedOrder()
        gateway.refund(order.charge, amount)
        reconcile(order)
        gateway.openDispute(order.charge, "needs_response")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.UNCHANGED)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
        gateway.setDisputeStatus(order.charge, "won")

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.UNCHANGED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
        assertThat(rows(order, CreditTxType.PURCHASE_REINSTATED)).isEmpty()
    }

    @Test
    fun `a pending refund takes the credits back, and if it fails they come back`() {
        val order = seedOrder()
        val refundId = gateway.refund(order.charge, amount, status = "pending")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REVERSED)

        gateway.setRefundStatus(order.charge, refundId, "failed")
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.REINSTATED)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
    }

    @Test
    fun `a partial refund is flagged once and never changes credits, even when the rest is refunded`(output: CapturedOutput) {
        val order = seedOrder()
        gateway.refund(order.charge, 1000)
        val alertsBefore = alerts(output)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)
        assertThat(alerts(output) - alertsBefore).isEqualTo(1)
        assertThat(reload(order).needsReview).isTrue()
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)

        gateway.refund(order.charge, amount - 1000)
        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(alerts(output) - alertsBefore).isEqualTo(2)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).isEmpty()
        assertThat(rows(order, CreditTxType.PURCHASE_REINSTATED)).isEmpty()
    }

    @Test
    fun `a replayed partial refund adds no alert`(output: CapturedOutput) {
        val order = seedOrder()
        gateway.refund(order.charge, 1000)
        reconcile(order)
        val alertsBefore = alerts(output)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(alerts(output)).isEqualTo(alertsBefore)
    }

    @Test
    fun `a dispute on a partially refunded order changes no credits but alerts the status change`(output: CapturedOutput) {
        val order = seedOrder()
        gateway.refund(order.charge, 1000)
        reconcile(order)
        gateway.openDispute(order.charge, "needs_response")
        val alertsBefore = alerts(output)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
        assertThat(alerts(output) - alertsBefore).isEqualTo(1)
        assertThat(output.all).contains("order ${order.orderNo} needs review and its money state changed: COMPLETED → DISPUTED")
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).isEmpty()
    }

    @Test
    fun `a partial refund that later fails keeps the order with manual handling`() {
        val order = seedOrder()
        val refundId = gateway.refund(order.charge, 1000)
        reconcile(order)
        gateway.setRefundStatus(order.charge, refundId, "failed")
        gateway.refund(order.charge, amount)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(walletService.balance(order.userId)).isEqualTo(230)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).isEmpty()
    }

    @Test
    fun `an order already flagged for review, like a double payment, gets no automatic credit change`() {
        val order = seedOrder()
        paymentOrderRepository.save(reload(order).also { it.needsReview = true; it.reviewReason = "second payment" })
        gateway.refund(order.charge, amount)

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(reload(order).reviewReason).isEqualTo("second payment")
        assertThat(walletService.balance(order.userId)).isEqualTo(230)
    }

    @Test
    fun `with open-in-view, a full refund replayed in a second request reverses once and persists the order`() {
        val order = seedOrder()
        gateway.refund(order.charge, amount)

        assertThat(inRequest { reconcile(order) }).isEqualTo(ChargeOutcome.REVERSED)
        assertThat(inRequest { reconcile(order) }).isEqualTo(ChargeOutcome.UNCHANGED)

        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(reload(order).creditsReversed).isTrue()
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
    }

    @Test
    fun `with open-in-view, two concurrent requests reconciling the same full refund reverse exactly once`() {
        val order = seedOrder()
        gateway.refund(order.charge, amount)

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            pool.submit<ChargeOutcome> {
                start.await()
                inRequest { charges.reconcile(order.charge, "request-$it").outcome }
            }
        }
        start.countDown()
        val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes).containsExactlyInAnyOrder(ChargeOutcome.REVERSED, ChargeOutcome.UNCHANGED)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
        assertThat(reload(order).creditsReversed).isTrue()
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
    }

    @Test
    fun `a refund on an EXPIRED or FAILED order changes nothing and does not throw`() {
        listOf(PaymentOrderStatus.EXPIRED, PaymentOrderStatus.FAILED).forEach { status ->
            val order = seedOrder(status)
            gateway.refund(order.charge, amount)

            assertThat(reconcile(order)).isEqualTo(ChargeOutcome.UNCHANGED)

            assertThat(reload(order).status).isEqualTo(status)
            assertThat(reload(order).creditsReversed).isFalse()
            assertThat(walletService.balance(order.userId)).isEqualTo(0)
        }
    }

    @Test
    fun `a dispute on a flagged EXPIRED order is NEEDS_REVIEW, keeps its status and changes no credits`() {
        val order = seedOrder(PaymentOrderStatus.EXPIRED)
        paymentOrderRepository.save(reload(order).also { it.needsReview = true; it.reviewReason = "paid session but order is EXPIRED" })
        gateway.openDispute(order.charge, "needs_response")

        assertThat(reconcile(order)).isEqualTo(ChargeOutcome.NEEDS_REVIEW)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(reload(order).creditsReversed).isFalse()
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `an order still CREATED throws so the webhook retries, and nothing changes`() {
        val order = seedOrder(PaymentOrderStatus.CREATED)
        gateway.refund(order.charge, amount)

        assertThatThrownBy { reconcile(order) }.isInstanceOf(IllegalStateException::class.java)

        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(reload(order).creditsReversed).isFalse()
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `the order is found by PaymentIntent, then by orderNo, when its charge id is not stored - and the charge id is stored`() {
        val byIntent = seedOrder()
        val chargeA = byIntent.charge
        paymentOrderRepository.save(reload(byIntent).also { it.stripeChargeId = null })
        gateway.refund(chargeA, amount)
        assertThat(charges.reconcile(chargeA, "test").outcome).isEqualTo(ChargeOutcome.REVERSED)
        assertThat(reload(byIntent).stripeChargeId).isEqualTo(chargeA)

        val byOrderNo = seedOrder()
        val chargeB = byOrderNo.charge
        paymentOrderRepository.save(reload(byOrderNo).also { it.stripeChargeId = null; it.stripePaymentIntentId = null })
        gateway.refund(chargeB, amount)
        assertThat(charges.reconcile(chargeB, "test").outcome).isEqualTo(ChargeOutcome.REVERSED)
        assertThat(reload(byOrderNo).stripeChargeId).isEqualTo(chargeB)
    }

    @Test
    fun `a charge that is not ours is NOT_OURS without an alert`(output: CapturedOutput) {
        gateway.addCharge("ch_foreign", "pi_foreign", null, 500)
        gateway.refund("ch_foreign", 500)
        val alertsBefore = alerts(output)

        assertThat(charges.reconcile("ch_foreign", "test").outcome).isEqualTo(ChargeOutcome.NOT_OURS)

        assertThat(alerts(output)).isEqualTo(alertsBefore)
    }

    @Test
    fun `a charge with our orderNo but no order throws and alerts`(output: CapturedOutput) {
        gateway.addCharge("ch_lost", "pi_lost", UUID.randomUUID().toString(), 500)
        gateway.refund("ch_lost", 500)
        val alertsBefore = alerts(output)

        assertThatThrownBy { charges.reconcile("ch_lost", "test") }
            .isInstanceOf(OrderNotFoundForChargeException::class.java)

        assertThat(alerts(output) - alertsBefore).isEqualTo(1)
    }

    @Test
    fun `reconcile refuses to run inside a DB transaction`() {
        val order = seedOrder()

        assertThatThrownBy { transactionTemplate.executeWithoutResult { reconcile(order) } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `two concurrent reconciles of the same full refund reverse exactly once`() {
        val order = seedOrder()
        gateway.refund(order.charge, amount)

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (1..2).map {
            pool.submit<ChargeOutcome> {
                start.await()
                charges.reconcile(order.charge, "thread-$it").outcome
            }
        }
        start.countDown()
        val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(outcomes).containsExactlyInAnyOrder(ChargeOutcome.REVERSED, ChargeOutcome.UNCHANGED)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
        assertThat(walletService.balance(order.userId)).isEqualTo(-100)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
    }

    @Test
    fun `a receipt stored from a stale read never undoes a reversal committed meanwhile`() {
        val order = seedOrder()
        val orderId = order.id ?: error("no id")
        val requiresNew = TransactionTemplate(transactionTemplate.transactionManager!!)
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        val fullRefund = MoneyState(amount, fullyRefunded = true, partialRefundSeen = false, dispute = DisputePhase.NONE)

        transactionTemplate.executeWithoutResult {
            paymentOrderRepository.findById(orderId).orElseThrow() // unlocked read: COMPLETED, not reversed
            requiresNew.executeWithoutResult { writer.applyMoneyState(orderId, order.charge, fullRefund, "test") } // commits meanwhile
            writer.storeReceipt(orderId, null, "https://pay.stripe.com/receipts/new") // flushes the stale instance
        }

        val after = reload(order)
        assertThat(after.receiptUrl).isEqualTo("https://pay.stripe.com/receipts/new")
        assertThat(after.status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(after.creditsReversed).isTrue()
        assertThat(reconcile(order.also { gateway.refund(it.charge, amount) })).isEqualTo(ChargeOutcome.UNCHANGED)
        assertThat(rows(order, CreditTxType.PURCHASE_REVERSAL)).hasSize(1)
    }
}
