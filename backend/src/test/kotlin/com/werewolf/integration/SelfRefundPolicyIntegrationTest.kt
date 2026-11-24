package com.werewolf.integration

import com.werewolf.dto.RoomConfigRequest
import com.werewolf.model.CreditTransaction
import com.werewolf.model.CreditTxType
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Perk
import com.werewolf.model.PerkActivation
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.RoomService
import com.werewolf.service.SelfRefundBlock
import com.werewolf.service.SelfRefundPolicy
import com.werewolf.service.SelfRefundView
import com.werewolf.support.PaymentIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

/** Which of a player's orders can be refunded from 充值記錄, and why not (spec D1, D2, D10). */
@PaymentIntegrationTest
class SelfRefundPolicyIntegrationTest {
    @Autowired lateinit var policy: SelfRefundPolicy
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired lateinit var perkRepository: PerkRepository
    @Autowired lateinit var perkActivationRepository: PerkActivationRepository
    @Autowired lateinit var roomRepository: RoomRepository
    @Autowired lateinit var roomService: RoomService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun newUser(): String = "google:policy-${UUID.randomUUID()}".also {
        userRepository.save(User(userId = it, nickname = "P"))
    }

    private fun order(userId: String, completedDaysAgo: Long = 2, status: PaymentOrderStatus = PaymentOrderStatus.COMPLETED): PaymentOrder {
        val product = productRepository.findByProductKeyAndActiveTrue("test-pack").orElseGet {
            productRepository.save(Product(productKey = "test-pack", name = "Pack", credits = 330, priceCents = 499))
        }
        return paymentOrderRepository.save(
            PaymentOrder(
                orderNo = UUID.randomUUID().toString(), userId = userId,
                productId = product.id ?: error("no product id"), credits = 330, amountCents = 499, currency = "aud",
            ).also {
                it.status = status
                it.completedAt = LocalDateTime.now().minusDays(completedDaysAgo)
            },
        )
    }

    /** A ledger row stamped [hoursAgo] in the past. */
    private fun tx(userId: String, type: CreditTxType, amount: Int, hoursAgo: Long, activationId: Int? = null) {
        val row = creditTransactionRepository.save(
            CreditTransaction(userId = userId, type = type, amount = amount, balanceAfter = 0, perkActivationId = activationId),
        )
        jdbc.update("UPDATE credit_transactions SET created_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now().minusHours(hoursAgo)), row.id)
    }

    /** A role-pick card activation in someone else's room, so [userId] is not in a room. */
    private fun activation(userId: String): Int {
        val host = newUser()
        val room = roomService.createRoom(host, "Host", null, RoomConfigRequest())
        val perk = perkRepository.findById("ROLE_PICK_WOLF").orElseGet {
            perkRepository.save(Perk(perkCode = "ROLE_PICK_WOLF", name = "Wolf", description = "", priceCredits = 30))
        }
        return perkActivationRepository.save(
            PerkActivation(roomId = room.roomId.toInt(), userId = userId, perkCode = perk.perkCode, pricePaid = 30),
        ).id ?: error("no activation id")
    }

    private fun view(o: PaymentOrder): SelfRefundView? = policy.viewsFor(o.userId, listOf(o))[o.id]

    private val eligible = SelfRefundView(eligible = true, reason = null)

    @Test
    fun `a fresh order can be refunded`() {
        val o = order(newUser())
        assertThat(view(o)).isEqualTo(eligible)
    }

    @Test
    fun `a spend after the purchase blocks it, a spend before does not`() {
        val before = order(newUser())
        tx(before.userId, CreditTxType.PERK_SPEND, -25, hoursAgo = 72)
        assertThat(view(before)).isEqualTo(eligible)

        val after = order(newUser())
        tx(after.userId, CreditTxType.PERK_SPEND, -25, hoursAgo = 10)
        assertThat(view(after)).isEqualTo(SelfRefundView(false, SelfRefundBlock.SPENT))
    }

    @Test
    fun `game rewards and a role-pick charge returned in full are not spending`() {
        val o = order(newUser())
        tx(o.userId, CreditTxType.GAME_REWARD, 20, hoursAgo = 20)
        val a = activation(o.userId)
        tx(o.userId, CreditTxType.PERK_SPEND, -30, hoursAgo = 10, activationId = a)
        tx(o.userId, CreditTxType.REFUND, 30, hoursAgo = 9, activationId = a)
        assertThat(view(o)).isEqualTo(eligible)
    }

    @Test
    fun `a player in a waiting room is told to leave it first`() {
        val o = order(newUser())
        roomService.createRoom(o.userId, "P", null, RoomConfigRequest())
        assertThat(view(o)).isEqualTo(SelfRefundView(false, SelfRefundBlock.IN_ROOM))
    }

    @Test
    fun `one self refund per 30 days`() {
        val userId = newUser()
        val earlier = order(userId, completedDaysAgo = 12).also {
            it.selfRefundRequestedAt = LocalDateTime.now().minusDays(10)
            it.selfRefundId = "re_earlier"
            it.selfRefundSettledAt = LocalDateTime.now().minusDays(10)
            it.status = PaymentOrderStatus.REFUNDED
            paymentOrderRepository.save(it)
        }
        val o = order(userId)
        assertThat(view(o)).isEqualTo(SelfRefundView(false, SelfRefundBlock.LIMIT))

        earlier.selfRefundRequestedAt = LocalDateTime.now().minusDays(31)
        paymentOrderRepository.save(earlier)
        assertThat(view(o)).isEqualTo(eligible)
    }

    @Test
    fun `an order whose refund is requested but not confirmed shows PENDING, even once REFUNDED`() {
        val o = order(newUser()).also {
            it.selfRefundRequestedAt = LocalDateTime.now()
            it.selfRefundId = "re_pending"
            it.status = PaymentOrderStatus.REFUNDED
            paymentOrderRepository.save(it)
        }
        assertThat(view(o)).isEqualTo(SelfRefundView(false, SelfRefundBlock.PENDING))
    }

    @Test
    fun `older than 14 days, refunded, flagged or unpaid orders get no view`() {
        val userId = newUser()
        val old = order(userId, completedDaysAgo = 15)
        val refunded = order(userId, status = PaymentOrderStatus.REFUNDED)
        val flagged = order(userId).also { it.needsReview = true; paymentOrderRepository.save(it) }
        val unpaid = order(userId, status = PaymentOrderStatus.CREATED).also { it.completedAt = null; paymentOrderRepository.save(it) }

        assertThat(policy.viewsFor(userId, listOf(old, refunded, flagged, unpaid))).isEmpty()
    }

    @Autowired lateinit var props: com.werewolf.config.PaymentProperties

    @Test
    fun `the window comes from self-refund-days`() {
        val oneDay = SelfRefundPolicy(props.copy(selfRefundDays = 1), creditTransactionRepository, paymentOrderRepository, roomRepository)
        val o = order(newUser(), completedDaysAgo = 2)
        assertThat(oneDay.viewsFor(o.userId, listOf(o))).isEmpty()
        assertThat(view(o)).isEqualTo(eligible)
    }

    @Test
    fun `the order list carries the view`() {
        val o = order(newUser())
        @Suppress("UNCHECKED_CAST")
        val orders = paymentServiceOrders(o.userId)
        assertThat(orders.single()["refund"]).isEqualTo(mapOf("eligible" to true, "reason" to null))
    }

    @Autowired lateinit var paymentService: com.werewolf.service.PaymentService

    @Suppress("UNCHECKED_CAST")
    private fun paymentServiceOrders(userId: String) =
        paymentService.listOrders(userId, 0)["orders"] as List<Map<String, Any?>>
}
