package com.werewolf.support

import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/** Puts an order's self-refund columns in a given state (JPA cannot write them). */
fun JdbcTemplate.setSelfRefund(orderId: Int?, requestedAt: LocalDateTime?, refundId: String? = null, settledAt: LocalDateTime? = null) {
    update(
        "UPDATE payment_orders SET self_refund_requested_at = ?, self_refund_id = ?, self_refund_settled_at = ? WHERE id = ?",
        requestedAt, refundId, settledAt, orderId ?: error("order not persisted"),
    )
}
