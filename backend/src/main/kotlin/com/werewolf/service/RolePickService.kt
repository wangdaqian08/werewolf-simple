package com.werewolf.service

import com.werewolf.game.RoleDealer
import com.werewolf.model.*
import com.werewolf.repository.PerkActivationRepository
import com.werewolf.repository.PerkRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RolePickView(
    val choice: RolePickChoice?,
    val frozenCredits: Int,
    val prices: Map<RolePickChoice, Int>,
    val seats: Map<RolePickChoice, Int>,
)

/**
 * Role-pick card ("点将卡"): a private pre-game purchase of a camp. Owner-only data —
 * returned solely in the response to the owner's own request, never broadcast.
 */
@Service
class RolePickService(
    private val perkRepository: PerkRepository,
    private val perkActivationRepository: PerkActivationRepository,
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val walletService: WalletService,
    private val perkService: PerkService,
) {
    private val log = LoggerFactory.getLogger(RolePickService::class.java)

    @Transactional(readOnly = true)
    fun myPick(userId: String, roomId: Int): RolePickView {
        val room = roomRepository.findById(roomId).orElse(null) ?: throw RoomNotFoundException("Room not found")
        val player = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).orElse(null)
            ?: throw PlayerNotInRoomException("Player not in room")
        return view(room, userId, player.rolePick)
    }

    @Transactional
    fun choose(userId: String, roomId: Int, choice: RolePickChoice): RolePickView {
        val room = roomRepository.findByIdForUpdate(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Role cards can only be chosen before the game starts")
        if (room.config?.perksAllowed == false) throw PerksDisabledException("Perks are disabled in this room")
        val player = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).orElse(null)
            ?: throw PlayerNotInRoomException("Player not in room")
        if (player.status == ReadyStatus.READY) throw RolePickLockedException("Cancel ready before changing your role card")

        val current = player.rolePick
        if (choice == current) return view(room, userId, current)

        val seats = seats(room)
        val code = choice.perkCode
        val perk = code?.let { perkRepository.findById(it).orElse(null)?.takeIf { p -> p.active } }
        if (code != null && (perk == null || (seats[choice] ?: 0) == 0))
            throw RolePickUnavailableException("This role card is not available in this room")

        // refundActivation clears the persistence context: nothing loaded above may be mutated after it.
        perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .filter { it.userId == userId && it.gameId == null && isRolePickCode(it.perkCode) }
            .forEach { perkService.refundActivation(it) }

        if (perk != null && code != null) {
            val activation = perkActivationRepository.save(
                PerkActivation(roomId = roomId, userId = userId, perkCode = code, pricePaid = perk.priceCredits),
            )
            walletService.debit(
                userId, perk.priceCredits, CreditTxType.PERK_SPEND,
                perkActivationId = activation.id, note = code,
            ) ?: throw InsufficientCreditsException("Insufficient credits")
        }

        val fresh = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).orElse(null)
            ?: throw PlayerNotInRoomException("Player not in room")
        fresh.rolePick = choice
        roomPlayerRepository.save(fresh)
        log.info("[role-pick] room={} user={} choice={}", roomId, userId, choice)
        return view(roomRepository.findById(roomId).orElse(null) ?: room, userId, choice)
    }

    private fun seats(room: Room) = RoleDealer.campSeats(RoleDealer.roleList(room, room.totalPlayers))

    private fun view(room: Room, userId: String, choice: RolePickChoice?): RolePickView {
        val roomId = room.roomId ?: error("room has no id")
        val frozen = perkActivationRepository.findByRoomIdAndStatus(roomId, PerkActivationStatus.ACTIVE)
            .filter { it.userId == userId && it.gameId == null }.sumOf { it.pricePaid }
        val prices = RolePickChoice.entries.mapNotNull { c ->
            val price = c.perkCode?.let { perkRepository.findById(it).orElse(null) }?.takeIf { it.active }?.priceCredits
            price?.let { c to it }
        }.toMap()
        return RolePickView(choice, frozen, prices, seats(room))
    }
}

class RolePickLockedException(message: String) : RuntimeException(message)
class RolePickUnavailableException(message: String) : RuntimeException(message)
