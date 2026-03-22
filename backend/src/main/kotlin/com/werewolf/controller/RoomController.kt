package com.werewolf.controller

import com.werewolf.auth.UserClaims
import com.werewolf.dto.ClaimSeatRequest
import com.werewolf.dto.CreateRoomRequest
import com.werewolf.dto.JoinRoomRequest
import com.werewolf.dto.SetReadyRequest
import com.werewolf.service.*
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/room")
class RoomController(private val roomService: RoomService) {

    @PostMapping("/create")
    fun createRoom(
        @RequestBody body: CreateRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val (userId, nickname, avatarUrl) = authentication.userClaims()
        return ResponseEntity.ok(roomService.createRoom(userId, nickname, avatarUrl, body.config))
    }

    @PostMapping("/join")
    fun joinRoom(
        @RequestBody body: JoinRoomRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        if (body.roomCode.isBlank())
            return ResponseEntity.badRequest().body(mapOf("error" to "roomCode must not be blank"))
        val (userId, nickname, avatarUrl) = authentication.userClaims()
        return try {
            ResponseEntity.ok(roomService.joinRoom(userId, nickname, avatarUrl, body.roomCode))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomNotOpenException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomFullException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @PostMapping("/ready")
    fun setReady(
        @RequestBody body: SetReadyRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val (userId, _, _) = authentication.userClaims()
        return try {
            roomService.setReady(userId, body.roomId, body.ready)
            ResponseEntity.ok(mapOf("success" to true))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomNotOpenException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PlayerNotInRoomException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @PostMapping("/seat")
    fun claimSeat(
        @RequestBody body: ClaimSeatRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val (userId, _, _) = authentication.userClaims()
        return try {
            roomService.claimSeat(userId, body.roomId, body.seatIndex)
            ResponseEntity.ok(mapOf("success" to true))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomNotOpenException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PlayerNotInRoomException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: SeatTakenException) {
            ResponseEntity.badRequest().body(mapOf("success" to false, "error" to e.message))
        }
    }

    @GetMapping("/{roomId}")
    fun getRoom(@PathVariable roomId: Int): ResponseEntity<Any> =
        try {
            ResponseEntity.ok(roomService.getRoom(roomId))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class UserTriple(val userId: String, val nickname: String, val avatarUrl: String?)

    private fun Authentication.userClaims(): UserTriple {
        val userId = principal as String
        val claims = details as? UserClaims
        return UserTriple(userId, claims?.nickname ?: userId, claims?.avatarUrl)
    }
}
