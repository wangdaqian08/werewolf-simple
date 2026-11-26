package com.werewolf.integration

import com.werewolf.dto.RoomConfigRequest
import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentChargeService
import com.werewolf.service.RoomService
import com.werewolf.service.SelfRefundPolicy
import com.werewolf.service.SelfRefundView
import com.werewolf.service.WalletService
import com.werewolf.support.FakeStripeGateway
import com.werewolf.support.PaymentIntegrationTest
import com.werewolf.support.setSelfRefund
import com.werewolf.util.JwtUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** POST /api/payment/order/{orderNo}/refund: the change-of-mind refund a player starts from 充值記錄 (spec §7). */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class SelfRefundIntegrationTest {
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var jwtUtil: JwtUtil
    @Autowired lateinit var gateway: FakeStripeGateway
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var charges: PaymentChargeService
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var policy: SelfRefundPolicy
    @Autowired lateinit var store: com.werewolf.repository.SelfRefundStore
    @Autowired lateinit var jdbc: JdbcTemplate

    private val credits = 330
    private val amount = 499L

    @BeforeEach
    fun reset() = gateway.reset()

    /** A paid, credited order two days old; its charge is in the fake under its PaymentIntent. */
    private fun paidOrder(userId: String = newUser()): PaymentOrder {
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Pack", credits = credits, priceCents = amount.toInt()))
        }
        val tag = UUID.randomUUID().toString()
        val order = paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(), userId = userId, productId = product.id ?: error("no product"),
                credits = credits, amountCents = amount.toInt(), currency = "aud",
            ).also {
                it.status = PaymentOrderStatus.COMPLETED
                it.completedAt = LocalDateTime.now().minusDays(2)
                it.stripeSessionId = "cs_$tag"
                it.stripePaymentIntentId = "pi_$tag"
                it.stripeChargeId = "ch_$tag"
                it.receiptUrl = "https://pay.stripe.com/receipts/$tag"
            },
        )
        walletService.credit(userId, credits, CreditTxType.PURCHASE, paymentOrderId = order.id, note = order.orderNo)
        gateway.addCharge("ch_$tag", "pi_$tag", order.orderNo, amount)
        return order
    }

    private fun newUser(): String = "google:refund-${UUID.randomUUID()}".also {
        userRepository.save(User(userId = it, nickname = "Buyer"))
    }

    private fun post(userId: String, orderNo: String): ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().also { it.setBearerAuth(jwtUtil.generateToken(userId, "Buyer", null)) }
        return restTemplate.postForEntity("/api/payment/order/$orderNo/refund", HttpEntity<Any>(headers), Map::class.java)
    }

    private fun reload(o: PaymentOrder) = paymentOrderRepository.findByOrderNo(o.orderNo).orElseThrow()
    private fun locked(userId: String) = paymentOrderRepository.existsByUserIdAndSelfRefundIdIsNotNullAndSelfRefundSettledAtIsNull(userId)
    private fun reversals(o: PaymentOrder) = creditTransactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(o.userId)
        .filter { it.type == CreditTxType.PURCHASE_REVERSAL && it.paymentOrderId == o.id }

    @Test
    fun `an eligible order is refunded, credits removed and the account unlocked in one call`(output: CapturedOutput) {
        val o = paidOrder()

        val resp = post(o.userId, o.orderNo)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("status")).isEqualTo("REFUNDED")
        assertThat(resp.body?.get("refundPending")).isEqualTo(false)
        assertThat(reload(o).status).isEqualTo(PaymentOrderStatus.REFUNDED)
        assertThat(reload(o).selfRefundSettledAt).isNotNull()
        assertThat(walletService.balance(o.userId)).isEqualTo(0)
        assertThat(reversals(o)).hasSize(1)
        assertThat(gateway.createRefundCalls).isEqualTo(1)
        assertThat(locked(o.userId)).isFalse()
        assertThat(output.all).doesNotContain("[payment-alert]")
    }

    @Test
    fun `each reason it cannot be refunded answers 409 with that code and changes nothing`() {
        val spent = paidOrder()
        walletService.debit(spent.userId, 25, CreditTxType.PERK_SPEND)
        assertThat(post(spent.userId, spent.orderNo).let { it.statusCode to it.body?.get("code") })
            .isEqualTo(HttpStatus.CONFLICT to "SPENT")

        val limitUser = newUser()
        paidOrder(limitUser).also {
            it.status = PaymentOrderStatus.REFUNDED
            paymentOrderRepository.save(it)
            jdbc.setSelfRefund(it.id, LocalDateTime.now().minusDays(10), "re_earlier", LocalDateTime.now().minusDays(10))
        }
        val second = paidOrder(limitUser)
        assertThat(post(limitUser, second.orderNo).body?.get("code")).isEqualTo("LIMIT")

        val old = paidOrder().also { it.completedAt = LocalDateTime.now().minusDays(15); paymentOrderRepository.save(it) }
        assertThat(post(old.userId, old.orderNo).body?.get("code")).isEqualTo("NOT_ELIGIBLE")

        listOf(spent, second, old).forEach { assertThat(reload(it).selfRefundRequestedAt).isNull() }
        assertThat(gateway.createRefundCalls).isEqualTo(0)
    }

    @Test
    fun `a stale button cannot refund while the player is in a room`() {
        val o = paidOrder()
        roomService.createRoom(o.userId, "Buyer", null, RoomConfigRequest())

        val resp = post(o.userId, o.orderNo)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(resp.body?.get("code")).isEqualTo("IN_ROOM")
        assertThat(gateway.createRefundCalls).isEqualTo(0)
    }

    @Test
    fun `two concurrent requests refund once`() {
        val o = paidOrder()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map { pool.submit<ResponseEntity<Map<*, *>>> { start.await(); post(o.userId, o.orderNo) } }
        start.countDown()
        val statuses = results.map { it.get(30, TimeUnit.SECONDS).statusCode }
        pool.shutdown()

        assertThat(statuses).contains(HttpStatus.OK)
        assertThat(reversals(o)).hasSize(1)
        assertThat(walletService.balance(o.userId)).isEqualTo(0)
        assertThat(gateway.retrieveCharge(o.stripeChargeId ?: error("no charge")).refunds).hasSize(1)
    }

    @Test
    fun `a Stripe error leaves no lock and a retry refunds`() {
        val o = paidOrder()
        gateway.failCreateRefund = true

        val failed = post(o.userId, o.orderNo)

        assertThat(failed.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(failed.body?.get("code")).isEqualTo("REFUND_UNAVAILABLE")
        assertThat(reload(o).selfRefundRequestedAt).isNull()
        assertThat(locked(o.userId)).isFalse()

        gateway.failCreateRefund = false
        assertThat(post(o.userId, o.orderNo).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(reload(o).status).isEqualTo(PaymentOrderStatus.REFUNDED)
    }

    @Test
    fun `a pending refund reverses the credits but keeps the account locked, and unlocks when it succeeds`() {
        val o = paidOrder()
        gateway.refundStatusOnCreate = "pending"

        val resp = post(o.userId, o.orderNo)

        assertThat(resp.body?.get("refundPending")).isEqualTo(true)
        assertThat(walletService.balance(o.userId)).isEqualTo(0)
        assertThat(locked(o.userId)).isTrue()
        assertThat(post(o.userId, o.orderNo).body?.get("code")).isEqualTo("PENDING")

        val chargeId = o.stripeChargeId ?: error("no charge")
        gateway.setRefundStatus(chargeId, reload(o).selfRefundId ?: error("no refund id"), "succeeded")
        charges.reconcile(chargeId, "webhook")

        assertThat(locked(o.userId)).isFalse()
        assertThat(reload(o).selfRefundSettledAt).isNotNull()
    }

    @Test
    fun `a failed self refund unlocks, gives credits back and alerts`(output: CapturedOutput) {
        val o = paidOrder()
        gateway.refundStatusOnCreate = "pending"
        post(o.userId, o.orderNo)
        val refundId = reload(o).selfRefundId ?: error("no refund id")

        val chargeId = o.stripeChargeId ?: error("no charge")
        gateway.setRefundStatus(chargeId, refundId, "failed")
        charges.reconcile(chargeId, "webhook")

        assertThat(locked(o.userId)).isFalse()
        assertThat(reload(o).selfRefundRequestedAt).isNull()
        assertThat(walletService.balance(o.userId)).isEqualTo(credits)
        assertThat(output.all).contains("[payment-alert] self-service refund $refundId of order ${o.orderNo} failed")
        assertThat(policy.viewsFor(o.userId, listOf(reload(o)))[o.id]).isEqualTo(SelfRefundView(true, null))
    }

    @Test
    fun `another player's order and an unknown order are 404`() {
        val o = paidOrder()
        assertThat(post(newUser(), o.orderNo).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(o.userId, "no-such-order").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(reload(o).selfRefundRequestedAt).isNull()
    }

    @Test
    fun `a guest cannot refund`() {
        assertThat(post("guest:someone", "anything").statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a request mark left without a refund id does not lock the account and the button resumes it`() {
        val o = paidOrder()
        assertThat(store.mark(o.orderNo, o.userId, LocalDateTime.now().withNano(0))).isTrue() // the process died before Stripe was called

        assertThat(locked(o.userId)).isFalse()
        assertThat(policy.viewsFor(o.userId, listOf(reload(o)))[o.id]).isEqualTo(SelfRefundView(true, null))
        assertThat(post(o.userId, o.orderNo).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(reload(o).status).isEqualTo(PaymentOrderStatus.REFUNDED)
    }

    @Test
    fun `a second tap that fails at Stripe does not clear the first tap's mark`() {
        val o = paidOrder()
        assertThat(store.mark(o.orderNo, o.userId, LocalDateTime.now().withNano(0))).isTrue() // tap 1 is still waiting for Stripe
        val marked = reload(o).selfRefundRequestedAt
        gateway.failCreateRefund = true

        assertThat(post(o.userId, o.orderNo).statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)

        assertThat(reload(o).selfRefundRequestedAt).isEqualTo(marked)
    }

    @Test
    fun `the request time is stored at millisecond precision and is the attempt key`() {
        val o = paidOrder()
        post(o.userId, o.orderNo)
        val requestedAt = reload(o).selfRefundRequestedAt ?: error("no mark")

        assertThat(requestedAt.nano % 1_000_000).isZero()
        val millis = requestedAt.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertThat(gateway.createRefundKeys).containsExactly("self-refund-${o.orderNo}-$millis")
    }

    @Test
    fun `a Stripe blip after the refund exists reports the refund as in progress, not failed`() {
        val o = paidOrder()
        gateway.failRetrieveCharge = true

        val resp = post(o.userId, o.orderNo)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body?.get("refundPending")).isEqualTo(true)
        assertThat(reload(o).selfRefundId).isNotNull()
    }
}
