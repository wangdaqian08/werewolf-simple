package com.werewolf.service

import com.werewolf.model.CreditTxType
import com.werewolf.model.GamePlayer
import com.werewolf.model.PerkActivation
import com.werewolf.model.PerkActivationStatus
import com.werewolf.model.PlayerRole
import com.werewolf.model.RoomStatus
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** First-night immunity perk: wolf attack on the holder fails on night 1. */
const val PERK_NIGHT1_IMMUNITY = "NIGHT1_IMMUNITY"

/**
 * Perk purchase/lifecycle. Activation is charge-at-click and strictly
 * first-come-first-served: the pessimistic room lock serializes concurrent
 * activations, so at most one player per room holds a given perk (the
 * Postgres partial unique index ux_one_active_perk_per_room is the prod
 * backstop). Rejection is synchronous — there is no pending state.
 *
 * Lifecycle: ACTIVE for the whole game (with [markNight1Triggered] recording
 * whether the perk actually took effect) | VOID = bound but inapplicable
 * (holder dealt a wolf role) — refunded by settlement at game end | terminal
 * CONSUMED/REFUNDED set only by PerkSettlementService at game end, except the
 * pre-game REFUNDED paths (withdraw / kick / sweep of never-started rooms).
 */
@Service
class PerkService(
    private val perkRepository: PerkRepository,
    private val perkActivationRepository: PerkActivationRepository,
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val walletService: WalletService,
    private val stompPublisher: StompPublisher,
) {
    private val log = LoggerFactory.getLogger(PerkService::class.java)

    @Transactional(readOnly = true)
    fun catalog() = perkRepository.findByActiveTrue().map {
        mapOf(
            "perkCode" to it.perkCode,
            "name" to it.name,
            "description" to it.description,
            "priceCredits" to it.priceCredits,
        )
    }

    @Transactional
    fun activate(userId: String, roomId: Int, perkCode: String) {
        // Pessimistic lock serializes all perk activations for this room —
        // the FCFS check below is race-free even on H2 (no partial index).
        val room = roomRepository.findByIdForUpdate(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Perks can only be activated before the game starts")
        if (room.config?.perksAllowed == false)
            throw PerksDisabledException("Perks are disabled in this room")
        if (roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).isEmpty)
            throw PlayerNotInRoomException("Player not in room")

        val perk = perkRepository.findById(perkCode).orElse(null)?.takeIf { it.active }
            ?: throw PerkNotFoundException("Unknown perk: $perkCode")

        val existing = perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
        if (existing.any { it.perkCode == perkCode })
            throw PerkTakenException("Perk already taken by another player")

        val activation = perkActivationRepository.save(
            PerkActivation(roomId = roomId, userId = userId, perkCode = perkCode, pricePaid = perk.priceCredits),
        )
        walletService.debit(
            userId, perk.priceCredits, CreditTxType.PERK_SPEND,
            perkActivationId = activation.id, note = perkCode,
        ) ?: throw InsufficientCreditsException("Insufficient credits")

        log.info("[perk] activate room={} user={} perk={} price={}", roomId, userId, perkCode, perk.priceCredits)
        broadcastPerkUpdate(roomId)
    }

    @Transactional
    fun withdraw(userId: String, roomId: Int, perkCode: String) {
        val room = roomRepository.findByIdForUpdate(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Perks can only be withdrawn before the game starts")

        val activation = perkActivationRepository
            .findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .firstOrNull { it.userId == userId && it.perkCode == perkCode }
            ?: throw PerkNotFoundException("No active perk to withdraw")

        refund(activation)
        broadcastPerkUpdate(roomId)
    }

    /**
     * Bind every live activation in [roomId] to the started game; activations
     * held by a player dealt a wolf role are VOIDED — bound but inapplicable.
     * The refund happens at game end via PerkSettlementService (refunding now
     * would leak the wolf identity via the balance change; at game end roles
     * are public).
     */
    @Transactional
    fun onGameStart(roomId: Int, gameId: Int, players: List<GamePlayer>) {
        val roleByUser = players.associate { it.userId to it.role }
        perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE).forEach { activation ->
            activation.gameId = gameId
            if (roleByUser[activation.userId] == PlayerRole.WEREWOLF) {
                activation.status = PerkActivationStatus.VOID
                log.info("[perk] void (wolf role) game={} user={} perk={}", gameId, activation.userId, activation.perkCode)
            }
            perkActivationRepository.save(activation)
        }
    }

    /**
     * Users holding live first-night immunity for [gameId]. Includes CONSUMED
     * so re-computations of the night-1 kill list (host reveal, state polls)
     * stay consistent after settlement marks the activation CONSUMED at game end.
     */
    @Transactional(readOnly = true)
    fun night1ImmuneUserIds(gameId: Int): Set<String> =
        perkActivationRepository.findByGameId(gameId)
            .filter { it.perkCode == PERK_NIGHT1_IMMUNITY }
            .filter { it.status == PerkActivationStatus.ACTIVE || it.status == PerkActivationStatus.CONSUMED }
            .map { it.userId }
            .toSet()

    /** Idempotent: only flips triggered_at from NULL, only for ACTIVE holders. */
    @Transactional
    fun markNight1Triggered(gameId: Int, userIds: Set<String>) {
        if (userIds.isEmpty()) return
        perkActivationRepository.markTriggered(gameId, PERK_NIGHT1_IMMUNITY, userIds)
    }

    /** Refund all of [userId]'s live activations in [roomId] (kick / leave). */
    @Transactional
    fun refundActiveForUser(roomId: Int, userId: String) {
        perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .filter { it.userId == userId }
            .forEach { refund(it) }
    }

    /**
     * Exactly-once: the wallet credit is gated on settleIfLive's conditional
     * UPDATE (same pattern as PerkSettlementService) — 0 rows = the activation
     * is already terminal (a concurrent refund/settle won), so no credit.
     * NOTE: settleIfLive clears the persistence context — [activation] is
     * detached after the gate; only plain fields may be read from it.
     */
    private fun refund(activation: PerkActivation) {
        val id = activation.id ?: error("activation has no id")
        if (perkActivationRepository.settleIfLive(id, PerkActivationStatus.REFUNDED) == 0) return
        walletService.credit(
            activation.userId, activation.pricePaid, CreditTxType.REFUND,
            perkActivationId = id, note = activation.perkCode,
        )
        log.info("[perk] refund room={} user={} perk={} amount={}",
            activation.roomId, activation.userId, activation.perkCode, activation.pricePaid)
    }

    private fun broadcastPerkUpdate(roomId: Int) {
        val perkNames = perkRepository.findAll().associate { it.perkCode to it.name }
        val activations = perkActivationRepository
            .findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .map {
                mapOf(
                    "userId" to it.userId,
                    "perkCode" to it.perkCode,
                    "perkName" to (perkNames[it.perkCode] ?: it.perkCode),
                )
            }
        stompPublisher.broadcastRoomAfterCommit(
            roomId,
            mapOf("type" to "PERK_UPDATE", "payload" to mapOf("perkActivations" to activations)),
        )
    }
}

class PerkNotFoundException(message: String) : RuntimeException(message)
class PerkTakenException(message: String) : RuntimeException(message)
class PerksDisabledException(message: String) : RuntimeException(message)
class InsufficientCreditsException(message: String) : RuntimeException(message)
