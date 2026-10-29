package com.werewolf.controller

import com.werewolf.service.AdminPinNotConfiguredException
import com.werewolf.service.AdminService
import com.werewolf.service.InvalidGrantAmountException
import com.werewolf.service.NotAdminException
import com.werewolf.service.PlayerNotFoundException
import com.werewolf.service.WrongAdminPinException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class AdminGrantRequest(val userId: String, val amount: Int, val pin: String)

/**
 * Admin credit grants. Every endpoint re-checks the caller is the admin; the
 * frontend only hides the page from everyone else.
 *
 *   GET  /api/admin/status        → { admin }
 *   GET  /api/admin/players?q=ray → [{ userId, nickname, balance }]
 *   POST /api/admin/grant         { userId, amount, pin } → { userId, nickname, balance }
 *
 * Errors carry a `code` the frontend translates: NOT_ADMIN (403),
 * PIN_NOT_CONFIGURED (503), WRONG_PIN (403), INVALID_AMOUNT (400),
 * PLAYER_NOT_FOUND (404).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController(private val adminService: AdminService) {
    private val log = LoggerFactory.getLogger(AdminController::class.java)

    @GetMapping("/status")
    fun status(authentication: Authentication): ResponseEntity<Any> =
        ResponseEntity.ok(mapOf("admin" to adminService.isAdmin(authentication.principal as String)))

    @GetMapping("/players")
    fun players(@RequestParam(defaultValue = "") q: String, authentication: Authentication): ResponseEntity<Any> =
        handle { ResponseEntity.ok(adminService.searchPlayers(authentication.principal as String, q)) }

    @PostMapping("/grant")
    fun grant(@RequestBody body: AdminGrantRequest, authentication: Authentication): ResponseEntity<Any> {
        val adminId = authentication.principal as String
        return handle {
            val player = adminService.grant(adminId, body.userId, body.amount, body.pin)
            log.info("[admin] {} granted {} credits to {} (balance now {})", adminId, body.amount, player.userId, player.balance)
            ResponseEntity.ok(player)
        }
    }

    private fun handle(block: () -> ResponseEntity<Any>): ResponseEntity<Any> = try {
        block()
    } catch (e: NotAdminException) {
        error(403, "NOT_ADMIN", e)
    } catch (e: AdminPinNotConfiguredException) {
        error(503, "PIN_NOT_CONFIGURED", e)
    } catch (e: WrongAdminPinException) {
        error(403, "WRONG_PIN", e)
    } catch (e: InvalidGrantAmountException) {
        error(400, "INVALID_AMOUNT", e)
    } catch (e: PlayerNotFoundException) {
        error(404, "PLAYER_NOT_FOUND", e)
    }

    private fun error(status: Int, code: String, e: RuntimeException): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to e.message, "code" to code))
}
