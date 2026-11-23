package com.werewolf.service

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrder
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.repository.CreditTransactionRepository
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.RoomRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/** Why an order inside the refund window cannot be refunded from the app right now. */
enum class SelfRefundBlock { SPENT, IN_ROOM, LIMIT, PENDING }

data class SelfRefundView(val eligible: Boolean, val reason: SelfRefundBlock?)

/**
 * The change-of-mind refund rule (spec D1, D2, D10): within self-refund-days of payment, no credits
 * spent since (a role-pick charge returned in full does not count), not in a room, and at most
 * self-refund-max-per-window self refunds in self-refund-window-days.
 */
@Service
class SelfRefundPolicy(
    private val props: PaymentProperties,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val paymentOrderRepository: PaymentOrderRepository,
    private val roomRepository: RoomRepository,
) {
    /** A view for each of [orders] that is inside the window or has a refund in progress, keyed by order id. */
    fun viewsFor(userId: String, orders: List<PaymentOrder>): Map<Int, SelfRefundView> {
        val now = LocalDateTime.now()
        val windowStart = now.minusDays(props.selfRefundDays)
        val candidates = orders.filter { pending(it) || refundable(it, windowStart) }
        if (candidates.isEmpty()) return emptyMap()

        val earliest = candidates.mapNotNull { it.completedAt }.minOrNull() ?: now
        val spends = creditTransactionRepository.unreturnedSpendTimesSince(userId, earliest)
        val inRoom = roomRepository.findActiveRoomsForUser(userId).isNotEmpty()
        val overLimit = paymentOrderRepository.countByUserIdAndSelfRefundIdIsNotNullAndSelfRefundRequestedAtAfter(
            userId, now.minusDays(props.selfRefundWindowDays),
        ) >= props.selfRefundMaxPerWindow

        return candidates.associate { order ->
            val completedAt = order.completedAt
            val reason = when {
                pending(order) -> SelfRefundBlock.PENDING
                completedAt != null && spends.any { it > completedAt } -> SelfRefundBlock.SPENT
                overLimit -> SelfRefundBlock.LIMIT
                inRoom -> SelfRefundBlock.IN_ROOM
                else -> null
            }
            (order.id ?: error("order not persisted")) to SelfRefundView(reason == null, reason)
        }
    }

    private fun pending(o: PaymentOrder) = o.selfRefundId != null && o.selfRefundSettledAt == null

    private fun refundable(o: PaymentOrder, windowStart: LocalDateTime): Boolean {
        val completedAt = o.completedAt ?: return false
        return o.status == PaymentOrderStatus.COMPLETED && !o.needsReview && !completedAt.isBefore(windowStart)
    }
}
