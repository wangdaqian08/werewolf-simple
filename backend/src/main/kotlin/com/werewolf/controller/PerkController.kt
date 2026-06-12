package com.werewolf.controller

import com.werewolf.service.InsufficientCreditsException
import com.werewolf.service.PerkNotFoundException
import com.werewolf.service.PerkService
import com.werewolf.service.PerkTakenException
import com.werewolf.service.PerksDisabledException
import com.werewolf.service.PlayerNotInRoomException
import com.werewolf.service.RoomNotFoundException
import com.werewolf.service.RoomNotOpenException
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class PerkActionRequest(val roomId: Int, val perkCode: String)

@RestController
class PerkController(private val perkService: PerkService) {

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
}
