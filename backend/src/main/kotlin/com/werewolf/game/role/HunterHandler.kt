package com.werewolf.game.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.VotingSubPhase
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Order(11)
@Component
class HunterHandler : RoleHandler {

    override val role = PlayerRole.HUNTER

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.VOTING && subPhase == VotingSubPhase.HUNTER_SHOOT.name)
            setOf(ActionType.HUNTER_SHOOT, ActionType.HUNTER_SKIP)
        else emptySet()

    // Hunter has no night action — shot is triggered by elimination during voting
    override fun nightSubPhases() = emptyList<com.werewolf.model.NightSubPhase>()

    // Hunter shot is handled by VotingPipeline directly for DB access
    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult =
        GameActionResult.Rejected("Hunter actions are routed through VotingPipeline")
}
