package com.werewolf.repository

import com.werewolf.model.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.*

interface UserRepository : JpaRepository<User, String>

interface RoomRepository : JpaRepository<Room, Int> {
    fun findByRoomCode(roomCode: String): Optional<Room>

    /**
     * Resolve the room currently "using" [code]. Room codes are reusable: once a
     * room's game has ended, its code is free to recycle (see [findActiveByRoomCode]
     * usage in RoomService.generateCode). A code is considered in use only while a
     * room is still WAITING or has a game that has not ended — finished/closed rooms
     * holding a recycled code are ignored. At most one such room exists per code.
     */
    @Query(
        """
        SELECT r FROM Room r
        WHERE r.roomCode = :code
          AND (r.status = com.werewolf.model.RoomStatus.WAITING
               OR EXISTS (SELECT g FROM Game g WHERE g.roomId = r.roomId AND g.endedAt IS NULL))
        """,
    )
    fun findActiveByRoomCode(code: String): Optional<Room>

    /**
     * Rooms the user belongs to that are still active (WAITING or with a live
     * game) — used by the lobby's quick-rejoin lookup so a player who closed the
     * app can jump straight back in. Most-recent room first.
     */
    @Query(
        """
        SELECT r FROM Room r
        WHERE EXISTS (SELECT rp FROM RoomPlayer rp WHERE rp.roomId = r.roomId AND rp.userId = :userId)
          AND (r.status = com.werewolf.model.RoomStatus.WAITING
               OR EXISTS (SELECT g FROM Game g WHERE g.roomId = r.roomId AND g.endedAt IS NULL))
        ORDER BY r.roomId DESC
        """,
    )
    fun findActiveRoomsForUser(userId: String): List<Room>

    /**
     * Pessimistic lock on the room row — serializes perk activations per room
     * so the FCFS "one activation per perk per room" rule holds under
     * concurrency on both Postgres and H2 (tests have no partial unique index).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.roomId = :roomId")
    fun findByIdForUpdate(roomId: Int): Optional<Room>
}

interface RoomPlayerRepository : JpaRepository<RoomPlayer, Int> {
    fun findByRoomId(roomId: Int): List<RoomPlayer>
    fun findByRoomIdAndUserId(roomId: Int, userId: String): Optional<RoomPlayer>
    fun existsByRoomIdAndSeatIndex(roomId: Int, seatIndex: Int): Boolean

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.status = :status WHERE rp.roomId = :roomId AND rp.userId = :userId")
    fun updateStatus(roomId: Int, userId: String, status: ReadyStatus): Int

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.status = com.werewolf.model.ReadyStatus.READY WHERE rp.roomId = :roomId AND rp.userId = :userId AND rp.seatIndex IS NOT NULL")
    fun setReadyIfSeated(roomId: Int, userId: String): Int

    @Modifying
    @Query("UPDATE RoomPlayer rp SET rp.seatIndex = :seatIndex WHERE rp.roomId = :roomId AND rp.userId = :userId")
    fun updateSeatIndex(roomId: Int, userId: String, seatIndex: Int): Int
}

interface GameRepository : JpaRepository<Game, Int> {
    fun findByRoomIdAndEndedAtIsNull(roomId: Int): Optional<Game>
    fun findByEndedAtIsNull(): List<Game>
}

interface GamePlayerRepository : JpaRepository<GamePlayer, Int> {
    fun findByGameId(gameId: Int): List<GamePlayer>
    fun findByGameIdAndUserId(gameId: Int, userId: String): Optional<GamePlayer>
}

interface NightPhaseRepository : JpaRepository<NightPhase, Int> {
    fun findByGameIdAndDayNumber(gameId: Int, dayNumber: Int): Optional<NightPhase>
    fun findByGameId(gameId: Int): List<NightPhase>
}

interface SheriffElectionRepository : JpaRepository<SheriffElection, Int> {
    fun findByGameId(gameId: Int): Optional<SheriffElection>
}

interface SheriffCandidateRepository : JpaRepository<SheriffCandidate, Int> {
    fun findByElectionId(electionId: Int): List<SheriffCandidate>
}

interface VoteRepository : JpaRepository<Vote, Int> {
    fun findByGameIdAndVoteContextAndDayNumber(
        gameId: Int,
        voteContext: VoteContext,
        dayNumber: Int,
    ): List<Vote>

    fun findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
        gameId: Int,
        voteContext: VoteContext,
        dayNumber: Int,
        voterUserId: String,
    ): Optional<Vote>
}

interface EliminationHistoryRepository : JpaRepository<EliminationHistory, Int> {
    fun findByGameId(gameId: Int): List<EliminationHistory>
    fun findByGameIdAndDayNumber(gameId: Int, dayNumber: Int): Optional<EliminationHistory>
}

interface GameEventRepository : JpaRepository<GameEvent, Int> {
    fun findByGameIdOrderByCreatedAtAsc(gameId: Int): List<GameEvent>
}

interface WalletRepository : JpaRepository<Wallet, String> {
    /**
     * Atomic conditional debit — 0 rows updated means insufficient funds.
     * Doing the balance check inside the UPDATE makes concurrent spends safe
     * without row locks (double-spend protection).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Wallet w SET w.balance = w.balance - :amount WHERE w.userId = :userId AND w.balance >= :amount")
    fun tryDebit(userId: String, amount: Int): Int

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Wallet w SET w.balance = w.balance + :amount WHERE w.userId = :userId")
    fun credit(userId: String, amount: Int): Int

    /**
     * Scalar read straight from the DB — bypasses any stale Wallet instance in
     * the persistence context, so it sees the result of tryDebit/credit above.
     */
    @Query("SELECT w.balance FROM Wallet w WHERE w.userId = :userId")
    fun getBalance(userId: String): Int?
}

interface CreditTransactionRepository : JpaRepository<CreditTransaction, Int> {
    fun findTop20ByUserIdOrderByCreatedAtDesc(userId: String): List<CreditTransaction>
    fun findByGameIdAndType(gameId: Int, type: CreditTxType): List<CreditTransaction>
}

interface PerkRepository : JpaRepository<Perk, String> {
    fun findByActiveTrue(): List<Perk>
}

interface PerkActivationRepository : JpaRepository<PerkActivation, Int> {
    fun findByRoomIdAndStatus(roomId: Int, status: PerkActivationStatus): List<PerkActivation>
    fun findByGameId(gameId: Int): List<PerkActivation>
    fun findByStatusAndGameIdIsNullAndCreatedAtBefore(
        status: PerkActivationStatus,
        createdAtBefore: java.time.LocalDateTime,
    ): List<PerkActivation>
}

interface ProductRepository : JpaRepository<Product, Int> {
    fun findByActiveTrueOrderBySortOrderAsc(): List<Product>
    fun findByProductKeyAndActiveTrue(productKey: String): Optional<Product>
}

interface PaymentOrderRepository : JpaRepository<PaymentOrder, Int> {
    fun findByStripeSessionId(stripeSessionId: String): Optional<PaymentOrder>
    fun findByOrderNo(orderNo: String): Optional<PaymentOrder>

    /**
     * Conditional CREATED → COMPLETED transition; 0 rows updated means the
     * order was already fulfilled (or expired) — webhook fulfillment idempotency.
     */
    @Modifying
    @Query(
        "UPDATE PaymentOrder o SET o.status = com.werewolf.model.PaymentOrderStatus.COMPLETED " +
            "WHERE o.id = :orderId AND o.status = com.werewolf.model.PaymentOrderStatus.CREATED",
    )
    fun markCompletedIfCreated(orderId: Int): Int
}

interface PaymentEventRepository : JpaRepository<PaymentEvent, String> {
    /**
     * Webhook event dedup, insert-first: 1 = first delivery, 0 = duplicate
     * (Stripe retries). ON CONFLICT DO NOTHING for the same reason as
     * GameSettlementRepository.tryInsert — no exception, no poisoned tx.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "INSERT INTO payment_events (stripe_event_id, event_type, received_at) " +
            "VALUES (:eventId, :eventType, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun tryInsert(eventId: String, eventType: String): Int
}

interface GameSettlementRepository : JpaRepository<GameSettlement, Int> {
    /**
     * Insert-first idempotency guard: 1 = this caller owns the settlement,
     * 0 = already settled. ON CONFLICT DO NOTHING instead of catching a
     * duplicate-key exception because a failed INSERT poisons the surrounding
     * Postgres transaction — and settle() runs inside the game-over tx.
     * Works on H2 too (tests run MODE=PostgreSQL).
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = "INSERT INTO game_settlements (game_id, settled_at) VALUES (:gameId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun tryInsert(gameId: Int): Int
}
