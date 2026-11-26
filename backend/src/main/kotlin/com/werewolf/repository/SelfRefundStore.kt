package com.werewolf.repository

import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDateTime

data class OpenSelfRefund(val orderNo: String, val refundId: String)

/**
 * The self-refund columns of payment_orders. Every write is one SQL statement that carries
 * all its conditions, so it is atomic on its own (no transaction, no row lock), and JPA
 * never writes these columns (see PaymentOrder).
 */
@Component
class SelfRefundStore(private val jdbc: JdbcClient, private val props: PaymentProperties) {

    /**
     * Marks [orderNo] as asked for at [at] if it still may be: paid, not flagged, inside the
     * refund window, not marked yet, and the player is under the self-refund limit. True if
     * this call made the mark. A second open mark of the same player is refused by the V44
     * index with a DuplicateKeyException.
     */
    fun mark(orderNo: String, userId: String, at: LocalDateTime): Boolean = jdbc.sql(
        """
        UPDATE payment_orders SET self_refund_requested_at = :at
         WHERE order_no = :orderNo AND user_id = :userId
           AND status = :completed AND needs_review = FALSE AND completed_at >= :windowStart
           AND self_refund_requested_at IS NULL
           AND (SELECT COUNT(*) FROM payment_orders o
                 WHERE o.user_id = :userId AND o.self_refund_id IS NOT NULL
                   AND o.self_refund_requested_at > :limitStart) < :maxPerWindow
        """,
    )
        .param("at", at).param("orderNo", orderNo).param("userId", userId)
        .param("completed", PaymentOrderStatus.COMPLETED.name)
        .param("windowStart", at.minusDays(props.selfRefundDays))
        .param("limitStart", at.minusDays(props.selfRefundWindowDays))
        .param("maxPerWindow", props.selfRefundMaxPerWindow)
        .update() == 1

    /** The request time of a mark that has no refund id yet (a concurrent tap, or a crash). */
    fun unfinishedMark(orderId: Int): LocalDateTime? = jdbc.sql(
        "SELECT self_refund_requested_at FROM payment_orders " +
            "WHERE id = :id AND self_refund_requested_at IS NOT NULL AND self_refund_id IS NULL",
    ).param("id", orderId).query(LocalDateTime::class.java).list().firstOrNull()

    /** Drops the mark made at [at], unless a refund id got stored meanwhile. */
    fun clearMark(orderId: Int, at: LocalDateTime): Int = jdbc.sql(
        "UPDATE payment_orders SET self_refund_requested_at = NULL " +
            "WHERE id = :id AND self_refund_requested_at = :at AND self_refund_id IS NULL",
    ).param("id", orderId).param("at", at).update()

    fun storeRefundId(orderId: Int, refundId: String): Int = jdbc.sql(
        "UPDATE payment_orders SET self_refund_id = :refundId WHERE id = :id AND self_refund_id IS NULL",
    ).param("id", orderId).param("refundId", refundId).update()

    fun openRefund(orderId: Int): OpenSelfRefund? = jdbc.sql(
        "SELECT order_no, self_refund_id FROM payment_orders " +
            "WHERE id = :id AND self_refund_id IS NOT NULL AND self_refund_settled_at IS NULL",
    ).param("id", orderId).query { rs, _ -> OpenSelfRefund(rs.getString(1), rs.getString(2)) }.list().firstOrNull()

    /** Stripe confirmed [refundId]: the account unlocks. */
    fun markSettled(orderId: Int, refundId: String, at: LocalDateTime): Int = jdbc.sql(
        "UPDATE payment_orders SET self_refund_settled_at = :at " +
            "WHERE id = :id AND self_refund_id = :refundId AND self_refund_settled_at IS NULL",
    ).param("id", orderId).param("refundId", refundId).param("at", at).update()

    /** Stripe failed or canceled [refundId]: the request is dropped, so the player may ask again. */
    fun dropFailed(orderId: Int, refundId: String): Int = jdbc.sql(
        "UPDATE payment_orders SET self_refund_requested_at = NULL, self_refund_id = NULL " +
            "WHERE id = :id AND self_refund_id = :refundId AND self_refund_settled_at IS NULL",
    ).param("id", orderId).param("refundId", refundId).update()

    /** The order's status and whether its self refund is settled, read from the DB (not a JPA cache). */
    fun state(orderId: Int): Pair<PaymentOrderStatus, Boolean> = jdbc.sql(
        "SELECT status, self_refund_settled_at FROM payment_orders WHERE id = :id",
    ).param("id", orderId).query { rs, _ ->
        PaymentOrderStatus.valueOf(rs.getString(1)) to (rs.getTimestamp(2) != null)
    }.single()
}
