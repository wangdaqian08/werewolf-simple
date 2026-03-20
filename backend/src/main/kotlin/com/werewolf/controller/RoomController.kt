package com.werewolf.controller

import com.werewolf.model.Room
import com.werewolf.model.RoomPlayer
import com.werewolf.model.RoomStatus
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

data class CreateRoomRequest(
    val totalPlayers: Int = 6,
    val hasSeer: Boolean = true,
    val hasWitch: Boolean = true,
    val hasHunter: Boolean = true,
    val hasGuard: Boolean = false,
)

data class JoinRoomRequest(val roomCode: String)

@RestController
@RequestMapping("/api/room")
class RoomController(
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
) {
    @PostMapping("/create")
    @Transactional
    fun createRoom(
        @RequestBody body: CreateRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String

        val code = generateCode()
        val room = Room(
            roomCode = code,
            hostUserId = userId,
            totalPlayers = body.totalPlayers,
            hasSeer = body.hasSeer,
            hasWitch = body.hasWitch,
            hasHunter = body.hasHunter,
            hasGuard = body.hasGuard,
        )
        roomRepository.save(room)

        roomPlayerRepository.save(RoomPlayer(roomId = room.roomId!!, userId = userId, host = true))

        return ResponseEntity.ok(
            mapOf(
                "roomId" to room.roomId,
                "roomCode" to room.roomCode,
                "hostUserId" to userId,
            )
        )
    }

    @PostMapping("/join")
    @Transactional
    fun joinRoom(
        @RequestBody body: JoinRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String

        val room = roomRepository.findByRoomCode(body.roomCode).orElse(null)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Room not found"))
        if (room.status != RoomStatus.WAITING)
            return ResponseEntity.badRequest().body(mapOf("error" to "Room is not open"))

        val existing = roomPlayerRepository.findByRoomIdAndUserId(room.roomId!!, userId)
        if (existing.isPresent)
            return ResponseEntity.ok(mapOf("roomId" to room.roomId, "roomCode" to room.roomCode, "rejoined" to true))

        val count = roomPlayerRepository.findByRoomId(room.roomId!!).size
        if (count >= room.totalPlayers)
            return ResponseEntity.badRequest().body(mapOf("error" to "Room is full"))

        roomPlayerRepository.save(RoomPlayer(roomId = room.roomId!!, userId = userId))

        return ResponseEntity.ok(
            mapOf(
                "roomId" to room.roomId,
                "roomCode" to room.roomCode,
                "playerCount" to count + 1,
            )
        )
    }

    @GetMapping("/{roomId}")
    fun getRoom(@PathVariable roomId: Int): ResponseEntity<Map<String, Any?>> {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Room not found"))
        val players = roomPlayerRepository.findByRoomId(roomId)
        return ResponseEntity.ok(
            mapOf(
                "roomId" to room.roomId,
                "roomCode" to room.roomCode,
                "status" to room.status.name,
                "totalPlayers" to room.totalPlayers,
                "players" to players.map { mapOf("userId" to it.userId, "isHost" to it.host) },
            )
        )
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }
}
