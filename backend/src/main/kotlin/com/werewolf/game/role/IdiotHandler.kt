package com.werewolf.game.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.PlayerRole
import org.springframework.stereotype.Component

@Component
class IdiotHandler : RoleHandler {
    override val role = PlayerRole.IDIOT
    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> = emptySet()
    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult =
        // TODO implement idiot action
        GameActionResult.Rejected("Idiot has no special actions")
}
