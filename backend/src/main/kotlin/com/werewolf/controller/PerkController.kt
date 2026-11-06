package com.werewolf.controller

import com.werewolf.service.InsufficientCreditsException
import com.werewolf.service.PerkNotFoundException
import com.werewolf.service.PerkService
import com.werewolf.service.PerkTakenException
import com.werewolf.service.PerksDisabledException
import com.werewolf.service.PlayerNotInRoomException
import com.werewolf.service.RolePickLockedException
import com.werewolf.service.RolePickService
import com.werewolf.service.RolePickUnavailableException
import com.werewolf.service.RoomNotFoundException
import com.werewolf.service.RoomNotOpenException
import com.werewolf.model.RolePickChoice
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class PerkActionRequest(val roomId: Int, val perkCode: String)
data class RolePickRequest(val roomId: Int, val choice: RolePickChoice)

@RestController
class PerkController(private val perkService: PerkService, private val rolePickService: RolePickService) {

    @GetMapping("/api/perks")
    fun catalog(): ResponseEntity<Any> = ResponseEntity.ok(perkService.catalog())

    @GetMapping("/api/perks/my")
    fun myActivations(authentication: Authentication): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return ResponseEntity.ok(perkService.myActivations(userId))
    }

    @PostMapping("/api/room/perk/activate")
    fun activate(
        @RequestBody body: PerkActionRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return try {
            perkService.activate(userId, body.roomId, body.perkCode)
            ResponseEntity.ok(mapOf("success" to true))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomNotOpenException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PerksDisabledException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PlayerNotInRoomException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PerkNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PerkTakenException) {
            ResponseEntity.badRequest().body(mapOf("success" to false, "error" to e.message))
        } catch (e: InsufficientCreditsException) {
            ResponseEntity.badRequest().body(mapOf("success" to false, "error" to e.message))
        }
    }

    @PostMapping("/api/room/perk/withdraw")
    fun withdraw(
        @RequestBody body: PerkActionRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val userId = authentication.principal as String
        return try {
            perkService.withdraw(userId, body.roomId, body.perkCode)
            ResponseEntity.ok(mapOf("success" to true))
        } catch (e: RoomNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: RoomNotOpenException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: PerkNotFoundException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        }
    }

    @GetMapping("/api/room/{roomId}/role-pick")
    fun myRolePick(@PathVariable roomId: Int, authentication: Authentication): ResponseEntity<Any> =
        rolePickResponse { rolePickService.myPick(authentication.principal as String, roomId) }

    @PostMapping("/api/room/role-pick")
    fun chooseRolePick(@RequestBody body: RolePickRequest, authentication: Authentication): ResponseEntity<Any> =
        rolePickResponse { rolePickService.choose(authentication.principal as String, body.roomId, body.choice) }

    // Every domain failure is a 400 {"error": message, "code": CODE}; the client translates the code
    // (the message is English, for logs). Unparseable bodies are already 400 from Spring.
    private fun rolePickResponse(block: () -> Any): ResponseEntity<Any> = try {
        ResponseEntity.ok(block())
    } catch (e: RuntimeException) {
        val code = when (e) {
            is RoomNotFoundException -> "ROOM_NOT_FOUND"
            is RoomNotOpenException -> "ROOM_NOT_OPEN"
            is PerksDisabledException -> "PERKS_DISABLED"
            is PlayerNotInRoomException -> "NOT_IN_ROOM"
            is InsufficientCreditsException -> "INSUFFICIENT_CREDITS"
            is RolePickLockedException -> "ROLE_PICK_LOCKED"
            is RolePickUnavailableException -> "ROLE_PICK_UNAVAILABLE"
            else -> throw e
        }
        ResponseEntity.badRequest().body(mapOf("error" to e.message, "code" to code))
    }
}
