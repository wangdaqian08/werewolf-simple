package com.werewolf.integration

import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.SelfRefundStore
import com.werewolf.repository.UserRepository
import com.werewolf.support.PaymentIntegrationTest
import com.werewolf.support.setSelfRefund
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID

/** The self-refund writes are single conditional UPDATEs: the SQL itself refuses what the rule refuses. */
@PaymentIntegrationTest
class SelfRefundStoreIntegrationTest {
    @Autowired lateinit var store: SelfRefundStore
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val now: LocalDateTime = LocalDateTime.now().withNano(0)

    private fun newUser(): String = "google:store-${UUID.randomUUID()}".also { userRepository.save(User(userId = it, nickname = "S")) }

    private fun order(userId: String = newUser(), completedDaysAgo: Long = 2, change: (PaymentOrder) -> Unit = {}): PaymentOrder {
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Pack", credits = 330, priceCents = 499))
        }
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(), userId = userId, productId = product.id ?: error("no product"),
                credits = 330, amountCents = 499, currency = "aud",
            ).also {
                it.status = PaymentOrderStatus.COMPLETED
                it.completedAt = now.minusDays(completedDaysAgo)
                change(it)
            },
        )
    }

    private fun mark(o: PaymentOrder) = store.mark(o.orderNo, o.userId, now)
    private fun requestedAt(o: PaymentOrder) =
        jdbc.queryForObject("SELECT self_refund_requested_at FROM payment_orders WHERE id = ?", LocalDateTime::class.java, o.id)

    @Test
    fun `marks an eligible order once`() {
        val o = order()
        assertThat(mark(o)).isTrue()
        assertThat(mark(o)).isFalse()
        assertThat(requestedAt(o)).isEqualTo(now)
        assertThat(store.unfinishedMark(o.id ?: error("no id"))).isEqualTo(now)
    }

    @Test
    fun `does not mark another player's, an old, a flagged or a refunded order`() {
        val other = order()
        assertThat(store.mark(other.orderNo, newUser(), now)).isFalse()
        assertThat(mark(order(completedDaysAgo = 15))).isFalse()
        assertThat(mark(order { it.needsReview = true })).isFalse()
        assertThat(mark(order { it.status = PaymentOrderStatus.REFUNDED })).isFalse()
    }

    @Test
    fun `does not mark once the player used up the self-refund limit`() {
        val userId = newUser()
        order(userId).also { jdbc.setSelfRefund(it.id, now.minusDays(10), "re_earlier", now.minusDays(10)) }
        assertThat(mark(order(userId))).isFalse()
    }

    @Test
    fun `clearMark only drops the mark with the same time and no refund id`() {
        val o = order().also { mark(it) }
        val id = o.id ?: error("no id")
        assertThat(store.clearMark(id, now.minusSeconds(1))).isZero()
        store.storeRefundId(id, "re_1")
        assertThat(store.clearMark(id, now)).isZero()
        assertThat(requestedAt(o)).isEqualTo(now)
    }

    @Test
    fun `settle and drop only touch the open refund with that id, once`() {
        val o = order().also { mark(it) }
        val id = o.id ?: error("no id")
        store.storeRefundId(id, "re_1")
        assertThat(store.storeRefundId(id, "re_2")).isZero()
        assertThat(store.openRefund(id)?.refundId).isEqualTo("re_1")

        assertThat(store.dropFailed(id, "re_other")).isZero()
        assertThat(store.markSettled(id, "re_1", now)).isEqualTo(1)
        assertThat(store.markSettled(id, "re_1", now)).isZero()
        assertThat(store.dropFailed(id, "re_1")).isZero()
        assertThat(store.openRefund(id)).isNull()
        assertThat(store.state(id)).isEqualTo(PaymentOrderStatus.COMPLETED to true)
    }
}
