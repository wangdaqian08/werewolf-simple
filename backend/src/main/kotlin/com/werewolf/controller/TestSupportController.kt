package com.werewolf.controller

import com.werewolf.service.TestSupportService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * e2e-only cleanup surface for integration tests: real-backend Playwright
 * specs call DELETE /api/test-support/rooms/{roomCode} in afterAll so a
 * locally-reused backend doesn't accumulate rows between runs (CI backends
 * are per-shard H2 create-drop and die with the process anyway).
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
}
