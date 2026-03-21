package com.werewolf.controller

import com.werewolf.dto.*
import com.werewolf.model.PlayerRole
import com.werewolf.model.Room
import com.werewolf.model.RoomPlayer
import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import com.werewolf.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/room")
class RoomController(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val userRepository: UserRepository,
) {
    @PostMapping("/create")
    @Transactional
    fun createRoom(
        @RequestBody body: CreateRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        val cfg = body.config

        val room = Room(
            roomCode = generateCode(),
            hostUserId = userId,
            totalPlayers = cfg.totalPlayers,
            hasSeer = PlayerRole.SEER in cfg.roles,
            hasWitch = PlayerRole.WITCH in cfg.roles,
            hasHunter = PlayerRole.HUNTER in cfg.roles,
            hasGuard = PlayerRole.GUARD in cfg.roles,
        )
        roomRepository.save(room)
        val roomId = room.roomId ?: error("Failed to persist room")
        roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = userId, host = true))

        return ResponseEntity.ok(buildRoomDto(room))
    }

    @PostMapping("/join")
    @Transactional
    fun joinRoom(
        @RequestBody body: JoinRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String

        val room = roomRepository.findByRoomCode(body.roomCode).orElse(null)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Room not found"))
        if (room.status != RoomStatus.WAITING)
            return ResponseEntity.badRequest().body(mapOf("error" to "Room is not open"))

        
        val roomId = room.roomId ?: error("Room has no ID")
        val existing = roomPlayerRepository.findByRoomIdAndUserId(roomId, userId)
        if (!existing.isPresent) {
            val count = roomPlayerRepository.findByRoomId(roomId).size
            if (count >= room.totalPlayers)
                return ResponseEntity.badRequest().body(mapOf("error" to "Room is full"))
            roomPlayerRepository.save(RoomPlayer(roomId = roomId, userId = userId))
        }

        return ResponseEntity.ok(buildRoomDto(room))
    }

    @GetMapping("/{roomId}")
    fun getRoom(@PathVariable roomId: Int): ResponseEntity<Any> {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Room not found"))
        return ResponseEntity.ok(buildRoomDto(room))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRoomDto(room: Room): RoomDto {
        
        val players = roomPlayerRepository.findByRoomId(room.roomId ?: error("Room has no ID"))
        val userIds = players.map { it.userId }
        val userMap = userRepository.findAllById(userIds).associateBy { it.userId }

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
            config = RoomConfigDto(totalPlayers = room.totalPlayers, roles = roles),
        )
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }
}
