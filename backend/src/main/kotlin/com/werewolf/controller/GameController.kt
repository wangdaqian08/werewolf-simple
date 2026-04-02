package com.werewolf.controller

import com.werewolf.dto.GameActionRequestDto
import com.werewolf.dto.StartGameRequest
import com.werewolf.game.action.GameActionDispatcher
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.service.GameService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/game")
class GameController(
    private val gameService: GameService,
    private val gameActionDispatcher: GameActionDispatcher,
    private val nightOrchestrator: NightOrchestrator,
) {
    val log: Logger = LoggerFactory.getLogger(GameController::class.java)
    @PostMapping("/start")
    fun startGame(
        @RequestBody body: StartGameRequest,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String
        return when (val result = gameService.startGame(userId, body.roomId)) {
            is GameActionResult.Success -> ResponseEntity.ok(mapOf("success" to true))
            is GameActionResult.Rejected -> ResponseEntity.badRequest().body(mapOf("success" to false, "message" to result.reason))
        }
    }

    @PostMapping("/action")
    fun submitAction(
        @RequestBody body: GameActionRequestDto,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String
        val request = GameActionRequest(
            gameId = body.gameId,
            actorUserId = userId,
            actionType = body.actionType,
            targetUserId = body.targetUserId,
            payload = body.payload ?: emptyMap(),
        )
        return when (val result = gameActionDispatcher.dispatch(request)) {
            is GameActionResult.Success -> ResponseEntity.ok(mapOf("success" to true))
            is GameActionResult.Rejected -> ResponseEntity.badRequest().body(mapOf("success" to false, "message" to result.reason))
        }
    }

    @GetMapping("/{gameId}/state")
    fun getGameState(
        @PathVariable gameId: Int,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String
        return ResponseEntity.ok(gameService.getGameState(gameId, userId))
    }

    // DEBUG ONLY: Manually advance night from WAITING to WEREWOLF_PICK
    @PostMapping("/{gameId}/debug/advance-night")
    fun debugAdvanceNight(
        @PathVariable gameId: Int,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val userId = authentication.principal as String
        log.info("[DEBUG] Manual night advance requested by $userId for game $gameId")
        nightOrchestrator.advanceFromWaiting(gameId)
        return ResponseEntity.ok(mapOf("success" to true, "message" to "Night advanced manually"))
    }
}
