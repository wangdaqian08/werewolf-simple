package com.werewolf.model

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Credit wallet, one per user. Balance changes only via the atomic
 * conditional UPDATEs in WalletRepository (never read-modify-write),
 * with every change journaled to credit_transactions.
 */
@Entity
@Table(name = "wallets")
class Wallet(
    @Id
    @Column(name = "user_id", length = 128)
    val userId: String,

    @Column(nullable = false)
    var balance: Int = 0,

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    var updatedAt: LocalDateTime? = null,
)

@Entity
@Table(name = "credit_transactions")
class CreditTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: CreditTxType,

    // signed: positive = credit, negative = debit
    @Column(nullable = false)
    val amount: Int,

    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Int,

    @Column(name = "game_id")
    val gameId: Int? = null,

    @Column(name = "perk_activation_id")
    val perkActivationId: Int? = null,

    @Column(name = "payment_order_id")
    val paymentOrderId: Int? = null,

    @Column(length = 200)
    val note: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,
)

@Entity
@Table(name = "perks")
class Perk(
    @Id
    @Column(name = "perk_code", length = 40)
    val perkCode: String,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, length = 500)
    val description: String,

    @Column(name = "price_credits", nullable = false)
    val priceCredits: Int,

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "max_per_game", nullable = false)
    val maxPerGame: Int = 1,

    // Free-form parameters for future perks (e.g. wolf-avoid probability)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params")
    val params: Map<String, Any>? = null,
)

@Entity
@Table(name = "perk_activations")
class PerkActivation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "room_id", nullable = false)
    val roomId: Int,

    // NULL until the game starts; bound in GameService.startGame
    @Column(name = "game_id")
    var gameId: Int? = null,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Column(name = "perk_code", nullable = false, length = 40)
    val perkCode: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: PerkActivationStatus = PerkActivationStatus.ACTIVE,

    @Column(name = "price_paid", nullable = false)
    val pricePaid: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    /** Set once (idempotently) when the perk actually took effect in-game. */
    @Column(name = "triggered_at")
    var triggeredAt: LocalDateTime? = null,

    /** Set when the terminal status (CONSUMED/REFUNDED) was decided — by game-end settlement or a pre-game refund. */
    @Column(name = "settled_at")
    var settledAt: LocalDateTime? = null,
)

@Entity
@Table(name = "products")
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "product_key", nullable = false, length = 40, unique = true)
    val productKey: String,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false)
    val credits: Int,

    @Column(name = "bonus_credits", nullable = false)
    val bonusCredits: Int = 0,

    @Column(name = "price_cents", nullable = false)
    val priceCents: Int,

    @Column(nullable = false, length = 3)
    val currency: String = "usd",

    @Column(nullable = false)
    val active: Boolean = true,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,
)

@Entity
@Table(name = "payment_orders")
class PaymentOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int? = null,

    @Column(name = "order_no", nullable = false, length = 40, unique = true)
    val orderNo: String,

    @Column(name = "user_id", nullable = false, length = 128)
    val userId: String,

    @Column(name = "product_id", nullable = false)
    val productId: Int,

    // credits + bonus_credits snapshot at order time
    @Column(nullable = false)
    val credits: Int,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Int,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: PaymentOrderStatus = PaymentOrderStatus.CREATED,

    @Column(name = "stripe_session_id", length = 255, unique = true)
    var stripeSessionId: String? = null,

    @Column(name = "stripe_payment_intent_id", length = 255)
    var stripePaymentIntentId: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    val createdAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
)

/** Stripe webhook event dedup: insert-first, duplicate key means already handled. */
@Entity
@Table(name = "payment_events")
class PaymentEvent(
    @Id
    @Column(name = "stripe_event_id", length = 255)
    val stripeEventId: String,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "received_at", nullable = false, updatable = false)
    @CreationTimestamp
    val receivedAt: LocalDateTime? = null,
)

/** One settlement per game: insert-first idempotency guard for game rewards. */
@Entity
@Table(name = "game_settlements")
class GameSettlement(
    @Id
    @Column(name = "game_id")
    val gameId: Int,

    @Column(name = "settled_at", nullable = false, updatable = false)
    @CreationTimestamp
    val settledAt: LocalDateTime? = null,
)
