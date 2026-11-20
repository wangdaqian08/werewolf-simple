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
import com.werewolf.service.PaymentChargeService
import com.werewolf.service.PaymentReconciliationJob
import com.werewolf.service.ReconcileReport
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

/**
 * The reconciliation job credits a paid order even when the webhook never arrived:
 * it settles stale CREATED orders, backfills missing receipts and sweeps Stripe's
 * completed sessions. Fake Stripe; reconcile() is called directly.
 */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class PaymentReconciliationIntegrationTest {

    @Autowired lateinit var job: PaymentReconciliationJob
    @Autowired lateinit var chargeService: PaymentChargeService
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        gateway.reset()
        // The DB is shared with the other payment tests: settle their leftovers first,
        // so each test's report counts only its own orders.
        job.reconcile()
    }

    private fun seedOrder(
        ageMinutes: Long,
        withSession: Boolean = true,
        status: PaymentOrderStatus = PaymentOrderStatus.CREATED,
    ): PaymentOrder {
        val userId = "google:reconcile-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "Buyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199))
        }
        val order = paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(),
                userId = userId,
                productId = product.id ?: error("no product id"),
                credits = 100,
                amountCents = 199,
                currency = "usd",
                status = status,
            ).also { if (withSession) it.stripeSessionId = "cs_test_${UUID.randomUUID()}" },
        )
        // created_at is set by Hibernate and not updatable through the entity
        jdbc.update(
            "UPDATE payment_orders SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(ageMinutes)), order.id,
        )
        return order
    }

    private fun openSession(order: PaymentOrder): String {
        val sessionId = order.stripeSessionId ?: error("no session")
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "open", paymentStatus = "unpaid",
                orderNo = order.orderNo, clientReferenceId = order.orderNo,
                paymentIntentId = null, chargeId = null, receiptUrl = null,
                url = "https://checkout.stripe.test/$sessionId", expiresAtEpochSec = null,
            ),
        )
        return sessionId
    }

    private fun reload(order: PaymentOrder) = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()

    private fun purchaseRows(userId: String) =
        creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId).filter { it.type == CreditTxType.PURCHASE }

    @Test
    fun `a CREATED order 11 min old whose session is paid is credited`() {
        val order = seedOrder(ageMinutes = 11)
        gateway.markPaid(openSession(order))

        val report = job.reconcile()

        assertThat(report.credited).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(purchaseRows(order.userId)).hasSize(1)
    }

    @Test
    fun `a CREATED order 11 min old whose session expired is EXPIRED`() {
        val order = seedOrder(ageMinutes = 11)
        gateway.expireSession(openSession(order))

        val report = job.reconcile()

        assertThat(report.expired).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `a CREATED order whose session this Stripe key does not know is EXPIRED once and not warned about again`(output: CapturedOutput) {
        val order = seedOrder(ageMinutes = 11) // its session is unknown to the fake, like a test-mode session after the key switch
        val sessionId = order.stripeSessionId ?: error("no session")

        val report = job.reconcile()

        assertThat(report.expired).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(output.all).contains("session $sessionId unknown to this Stripe key, order ${order.orderNo} expired")
        val mentions = output.all.split(sessionId).size

        job.reconcile()

        assertThat(output.all.split(sessionId).size).isEqualTo(mentions)
    }

    @Test
    fun `a CREATED order 42 min old with no session is FAILED`() {
        // 31-min session lifetime + 10-min reconcile age: no session Stripe made for it can still be paid
        val order = seedOrder(ageMinutes = 42, withSession = false)

        val report = job.reconcile()

        assertThat(report.failed).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.FAILED)
    }

    @Test
    fun `a CREATED order 15 min old with no session is left CREATED - a session Stripe made for it may still be paid`() {
        val order = seedOrder(ageMinutes = 15, withSession = false)

        val report = job.reconcile()

        assertThat(report.failed).isEqualTo(0)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.CREATED)
    }

    @Test
    fun `orders only 9 min old are left alone`() {
        val expired = seedOrder(ageMinutes = 9)
        gateway.expireSession(openSession(expired))
        val noSession = seedOrder(ageMinutes = 9, withSession = false)
        val retrievesBefore = gateway.retrieveCalls

        val report = job.reconcile()

        assertThat(reload(expired).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(reload(noSession).status).isEqualTo(PaymentOrderStatus.CREATED)
        assertThat(gateway.retrieveCalls).isEqualTo(retrievesBefore)
        assertThat(report).isEqualTo(ReconcileReport(0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun `a COMPLETED order without a receipt gets its charge and receipt, and nothing else changes`() {
        val order = seedOrder(ageMinutes = 60, status = PaymentOrderStatus.COMPLETED)
        val sessionId = openSession(order)
        gateway.markPaid(sessionId)

        val report = job.reconcile()

        assertThat(report.backfilled).isEqualTo(1)
        assertThat(report.credited).isEqualTo(0)
        val done = reload(order)
        assertThat(done.receiptUrl).isEqualTo("https://pay.stripe.com/receipts/$sessionId")
        assertThat(done.stripeChargeId).isEqualTo("ch_$sessionId")
        assertThat(done.status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
        assertThat(purchaseRows(order.userId)).isEmpty()
    }

    @Test
    fun `the Stripe sweep credits a paid session whose order is CREATED and only 1 min old`() {
        val order = seedOrder(ageMinutes = 1)
        gateway.markPaid(openSession(order))

        val report = job.reconcile()

        assertThat(report.credited).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
    }

    @Test
    fun `a completed session with an unknown orderNo is alerted once and does not stop the run`(output: CapturedOutput) {
        val sessionId = "cs_test_${UUID.randomUUID()}"
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "open", paymentStatus = "unpaid",
                orderNo = UUID.randomUUID().toString(), clientReferenceId = null,
                paymentIntentId = null, chargeId = null, receiptUrl = null, url = null, expiresAtEpochSec = null,
            ),
        )
        gateway.markPaid(sessionId)
        val order = seedOrder(ageMinutes = 1)
        gateway.markPaid(openSession(order)) // listed after the unknown one

        val report = job.reconcile()

        assertThat(report.alerts).isEqualTo(1)
        assertThat(output.all).contains("[payment-alert]").contains(sessionId)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)

        // The next run sees the same session again but does not alert again.
        val alertLines = output.all.split("[payment-alert]").size
        assertThat(job.reconcile().alerts).isEqualTo(0)
        assertThat(output.all.split("[payment-alert]").size).isEqualTo(alertLines)
    }

    /** A COMPLETED, credited order whose charge is [chargeId]; its receipt is set so the backfill step ignores it. */
    private fun paidOrder(status: PaymentOrderStatus = PaymentOrderStatus.COMPLETED): PaymentOrder {
        val order = seedOrder(ageMinutes = 60, status = status)
        order.stripeChargeId = "ch_${UUID.randomUUID()}"
        order.receiptUrl = "https://pay.stripe.com/receipts/${order.orderNo}"
        paymentOrderRepository.save(order)
        walletService.credit(order.userId, 100, CreditTxType.PURCHASE, paymentOrderId = order.id, note = "seed")
        gateway.addCharge(order.stripeChargeId ?: error("no charge"), null, order.orderNo, 199)
        return order
    }

    @Test
    fun `a COMPLETED order whose charge was fully refunded without a webhook is reversed once`() {
        val order = paidOrder()
        gateway.refund(order.stripeChargeId ?: error("no charge"), 199)

        assertThat(job.reconcile().reversed).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)

        assertThat(job.reconcile().reversed).isEqualTo(0)
        assertThat(walletService.balance(order.userId)).isEqualTo(0)
    }

    @Test
    fun `a DISPUTED order whose dispute was won is reinstated even when the dispute is older than the window`() {
        val order = paidOrder()
        val chargeId = order.stripeChargeId ?: error("no charge")
        val fortyDaysAgo = System.currentTimeMillis() / 1000 - 40 * 86_400
        gateway.openDispute(chargeId, "needs_response", createdEpochSec = fortyDaysAgo)
        chargeService.reconcileCharge(chargeId, "webhook") // the webhook that did arrive: order DISPUTED, credits taken back
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
        gateway.setDisputeStatus(chargeId, "won")

        val report = job.reconcile()

        assertThat(report.reinstated).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
    }

    @Test
    fun `a session-less CREATED order 42 min old whose orderNo is on a completed session is credited, not failed`() {
        val order = seedOrder(ageMinutes = 42, withSession = false)
        val sessionId = "cs_test_${UUID.randomUUID()}"
        gateway.put(
            CheckoutSnapshot(
                sessionId = sessionId, status = "open", paymentStatus = "unpaid",
                orderNo = order.orderNo, clientReferenceId = order.orderNo,
                paymentIntentId = null, chargeId = null, receiptUrl = null, url = null, expiresAtEpochSec = null,
            ),
        )
        gateway.markPaid(sessionId)

        val report = job.reconcile()

        assertThat(report.credited).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
    }

    @Test
    fun `a foreign refunded charge is ignored with no alert`(output: CapturedOutput) {
        val chargeId = "ch_foreign_${UUID.randomUUID()}"
        gateway.addCharge(chargeId, null, null, 500)
        gateway.refund(chargeId, 500)

        val report = job.reconcile()

        assertThat(report.alerts).isEqualTo(0)
        assertThat(report.reversed).isEqualTo(0)
        assertThat(output.all).doesNotContain("[payment-alert] charge $chargeId")
    }

    @Test
    fun `our orderNo with no order is alerted once per process`(output: CapturedOutput) {
        val chargeId = "ch_orphan_${UUID.randomUUID()}"
        gateway.addCharge(chargeId, null, UUID.randomUUID().toString(), 500)
        gateway.refund(chargeId, 500)

        assertThat(job.reconcile().alerts).isEqualTo(1)
        assertThat(job.reconcile().alerts).isEqualTo(0)
        assertThat(output.all.split("[payment-alert] charge $chargeId").size).isEqualTo(2)
    }

    @Test
    fun `reconciling an already reversed order twice leaves updatedAt alone`() {
        val order = paidOrder()
        gateway.refund(order.stripeChargeId ?: error("no charge"), 199)
        job.reconcile()
        val first = reload(order).updatedAt

        job.reconcile()

        assertThat(reload(order).updatedAt).isEqualTo(first)
    }

    /** An order paid through the fake (its session listed by the sweep) and credited by reconcile(); its charge is in the fake. */
    private fun sweptPaidOrder(): Pair<PaymentOrder, String> {
        val order = seedOrder(ageMinutes = 1)
        val sessionId = openSession(order)
        gateway.markPaid(sessionId)
        val chargeId = "ch_$sessionId"
        gateway.addCharge(chargeId, "pi_$sessionId", order.orderNo, 199)
        assertThat(job.reconcile().credited).isEqualTo(1)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        return order to chargeId
    }

    @Test
    fun `the sweep leaves a refunded order alone, so a refund that later fails is reinstated`(output: CapturedOutput) {
        val (order, chargeId) = sweptPaidOrder()
        val refundId = gateway.refund(chargeId, 199)

        // The session stays complete/paid after the refund: the sweep must not re-fulfill (and flag) the REFUNDED order.
        assertThat(job.reconcile().reversed).isEqualTo(1)
        assertThat(job.reconcile().reversed).isEqualTo(0)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(reload(order).needsReview).isFalse()
        assertThat(walletService.balance(order.userId)).isEqualTo(0)

        gateway.setRefundStatus(chargeId, refundId, "failed")

        assertThat(job.reconcile().reinstated).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(output.all).doesNotContain("[payment-alert] order ${order.orderNo}")
    }

    @Test
    fun `the sweep leaves a disputed order alone, so a won dispute is reinstated`(output: CapturedOutput) {
        val (order, chargeId) = sweptPaidOrder()
        gateway.openDispute(chargeId)

        assertThat(job.reconcile().reversed).isEqualTo(1)
        assertThat(job.reconcile().reversed).isEqualTo(0)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
        assertThat(reload(order).needsReview).isFalse()
        assertThat(walletService.balance(order.userId)).isEqualTo(0)

        gateway.setDisputeStatus(chargeId, "won")

        assertThat(job.reconcile().reinstated).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
        assertThat(output.all).doesNotContain("[payment-alert] order ${order.orderNo}")
    }

    @Test
    fun `a DISPUTED order whose charge this Stripe key does not know is skipped without a WARN`(output: CapturedOutput) {
        val order = seedOrder(ageMinutes = 60, status = PaymentOrderStatus.DISPUTED)
        val chargeId = "ch_testmode_${UUID.randomUUID()}" // a test-mode charge after the switch to live keys
        paymentOrderRepository.save(order.also { it.stripeChargeId = chargeId })

        job.reconcile()
        job.reconcile()

        assertThat(output.all.lines().filter { chargeId in it }).noneMatch { " WARN " in it }
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.DISPUTED)
    }

    @Test
    fun `a REFUNDED order whose 40-day-old refund later failed is reinstated by the updatedAt re-check alone`() {
        val order = paidOrder()
        val chargeId = order.stripeChargeId ?: error("no charge")
        val fortyDaysAgo = System.currentTimeMillis() / 1000 - 40 * 86_400
        val refundId = gateway.refund(chargeId, 199, createdEpochSec = fortyDaysAgo)
        chargeService.reconcileCharge(chargeId, "webhook") // the refund webhook: REFUNDED, credits taken back
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        gateway.setRefundStatus(chargeId, refundId, "failed")

        val report = job.reconcile()

        assertThat(report.reinstated).isEqualTo(1)
        assertThat(reload(order).status).isEqualTo(PaymentOrderStatus.COMPLETED)
        assertThat(walletService.balance(order.userId)).isEqualTo(100)
    }
}
