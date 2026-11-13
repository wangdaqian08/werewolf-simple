package com.werewolf.controller

import com.werewolf.model.GameTask

import com.werewolf.service.TestSupportService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * e2e-only cleanup surface for integration tests: real-backend Playwright
 * specs call DELETE /api/test-support/rooms/{roomCode} in afterAll so a
 * locally-reused backend doesn't accumulate rows between runs (CI backends
 * are per-shard H2 create-drop and die with the process anyway).
 *
 * POST /api/test-support/wallet/grant tops up a wallet so specs can buy perks.
 *
 * POST /api/test-support/games/{gameId}/swap-roles swaps two players' dealt roles
 * during ROLE_REVEAL so a spec can pin a role (e.g. host = KNIGHT) deterministically.
 *
 * POST /api/test-support/games/{gameId}/set-task pins one seat's 单局任务 while the game
 * runs; only a task that seat's role can draw, so a spec can never stage an impossible one.
 *
 * Registered only under the e2e/test profiles and never alongside prod —
 * pinned by TestSupportControllerProfileTest.
 */
@RestController
@RequestMapping("/api/test-support")
@Profile("(e2e | test) & !prod")
class TestSupportController(private val testSupportService: TestSupportService) {

    @DeleteMapping("/rooms/{roomCode}")
    fun deleteRoom(@PathVariable roomCode: String): ResponseEntity<Map<String, Int>> {
        val deleted = testSupportService.deleteRoomCascade(roomCode)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(deleted)
    }

    @PostMapping("/wallet/grant")
    fun grantCredits(@RequestBody body: GrantCreditsRequest): ResponseEntity<Map<String, Int>> =
        if (body.amount <= 0) ResponseEntity.badRequest().build()
        else ResponseEntity.ok(mapOf("balance" to testSupportService.grantCredits(body.userId, body.amount)))

    @PostMapping("/games/{gameId}/swap-roles")
    fun swapRoles(
        @PathVariable gameId: Int,
        @RequestBody body: SwapRolesRequest,
    ): ResponseEntity<Map<String, String>> {
        val roles = testSupportService.swapRoles(gameId, body.userIdA, body.userIdB)
            ?: return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(roles)
    }

    @PostMapping("/games/{gameId}/set-task")
    fun setTask(@PathVariable gameId: Int, @RequestBody body: SetTaskRequest): ResponseEntity<Map<String, String>> {
        val task = GameTask.entries.firstOrNull { it.name == body.task } ?: return ResponseEntity.badRequest().build()
        if (!testSupportService.setTask(gameId, body.userId, task)) return ResponseEntity.badRequest().build()
        return ResponseEntity.ok(mapOf(body.userId to task.name))
    }
}

data class SwapRolesRequest(val userIdA: String, val userIdB: String)

data class SetTaskRequest(val userId: String, val task: String)

data class GrantCreditsRequest(val userId: String, val amount: Int)
