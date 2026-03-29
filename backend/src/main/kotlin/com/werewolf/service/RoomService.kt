package com.werewolf.service

import com.werewolf.auth.AuthService
import com.werewolf.dto.RoomConfigDto
import com.werewolf.dto.RoomConfigRequest
import com.werewolf.dto.RoomDto
import com.werewolf.dto.RoomPlayerDto
import com.werewolf.model.*
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RoomService(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val stompPublisher: StompPublisher,
) {
    @Transactional
    fun createRoom(userId: String, nickname: String, avatarUrl: String?, cfg: RoomConfigRequest): RoomDto {
        authService.loginOrRegister(userId, nickname, avatarUrl)

        val room = roomRepository.save(
            Room(
                roomCode = generateCode(),
                hostUserId = userId,
                totalPlayers = cfg.totalPlayers,
                hasSeer = PlayerRole.SEER in cfg.roles,
                hasWitch = PlayerRole.WITCH in cfg.roles,
                hasHunter = PlayerRole.HUNTER in cfg.roles,
                hasGuard = PlayerRole.GUARD in cfg.roles,
                hasIdiot = PlayerRole.IDIOT in cfg.roles,
                hasSheriff = cfg.hasSheriff,
                winCondition = cfg.winCondition,
            )
        )
        val roomId = room.roomId ?: error("Failed to persist room")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = userId, host = true))

        return buildRoomDto(room)
    }

    @Transactional
    fun joinRoom(userId: String, nickname: String, avatarUrl: String?, roomCode: String): RoomDto {
        authService.loginOrRegister(userId, nickname, avatarUrl)

        val room = roomRepository.findByRoomCode(roomCode).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING)
            throw RoomNotOpenException("Room is not open")

        val roomId = room.roomId ?: error("Room has no ID")
        if (!roomPlayerRepository.findByRoomIdAndUserId(roomId, userId).isPresent) {
            val count = roomPlayerRepository.findByRoomId(roomId).size
            if (count >= room.totalPlayers) throw RoomFullException("Room is full")
            roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = userId))
        }

        return buildRoomDto(room)
    }

    @Transactional
    fun setReady(userId: String, roomId: Int, ready: Boolean) {
        val room = roomRepository.findById(roomId).orElse(null) ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING) throw RoomNotOpenException("Room is not open")

        val affected = if (ready) {
            roomPlayerRepository.setReadyIfSeated(roomId, userId)
        } else {
            roomPlayerRepository.updateStatus(roomId, userId, ReadyStatus.NOT_READY)
        }
        if (affected == 0) throw PlayerNotInRoomException("Player not in room or seat not yet claimed")

        stompPublisher.broadcastRoom(roomId, mapOf("type" to "ROOM_UPDATE",
            "payload" to mapOf("players" to buildRoomDto(room).players)))
    }

    @Transactional
    fun claimSeat(userId: String, roomId: Int, seatIndex: Int) {
        val room = roomRepository.findById(roomId).orElse(null) ?: throw RoomNotFoundException("Room not found")
        if (room.status != RoomStatus.WAITING) throw RoomNotOpenException("Room is not open")

        if (roomPlayerRepository.existsByRoomIdAndSeatIndex(roomId, seatIndex))
            throw SeatTakenException("Seat $seatIndex is already taken")

        val affected = roomPlayerRepository.updateSeatIndex(roomId, userId, seatIndex)
        if (affected == 0) throw PlayerNotInRoomException("Player not in room")

        stompPublisher.broadcastRoom(roomId, mapOf("type" to "ROOM_UPDATE",
            "payload" to mapOf("players" to buildRoomDto(room).players)))
    }

    @Transactional(readOnly = true)
    fun getRoom(roomId: Int): RoomDto {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: throw RoomNotFoundException("Room not found")
        return buildRoomDto(room)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRoomDto(room: Room): RoomDto {
        val players = roomPlayerRepository.findByRoomId(room.roomId ?: error("Room has no ID"))
        val userMap = userRepository.findAllById(players.map { it.userId }).associateBy { it.userId }

        val playerDtos = players.map { rp ->
            val user = userMap[rp.userId]
            RoomPlayerDto(
                userId = rp.userId,
                nickname = user?.nickname ?: rp.userId,
                avatar = user?.avatarUrl,
                seatIndex = rp.seatIndex,
                status = rp.status.name,
                isHost = rp.host,
            )
        }

        val roles = buildList {
            add(PlayerRole.WEREWOLF)
            add(PlayerRole.VILLAGER)
            if (room.hasSeer) add(PlayerRole.SEER)
            if (room.hasWitch) add(PlayerRole.WITCH)
            if (room.hasHunter) add(PlayerRole.HUNTER)
            if (room.hasGuard) add(PlayerRole.GUARD)
            if (room.hasIdiot) add(PlayerRole.IDIOT)
        }

        return RoomDto(
            roomId = room.roomId.toString(),
            roomCode = room.roomCode,
            hostId = room.hostUserId,
            status = room.status.name,
            players = playerDtos,
            config = RoomConfigDto(totalPlayers = room.totalPlayers, roles = roles, hasSheriff = room.hasSheriff, winCondition = room.winCondition),
        )
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }
}

class RoomNotFoundException(message: String) : RuntimeException(message)
class RoomNotOpenException(message: String) : RuntimeException(message)
class RoomFullException(message: String) : RuntimeException(message)
class PlayerNotInRoomException(message: String) : RuntimeException(message)
class SeatTakenException(message: String) : RuntimeException(message)
