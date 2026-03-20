package com.werewolf.game.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole

data class EliminationModifier(
    val cancelled: Boolean = false,
    val additionalKills: List<String> = emptyList(),
    val extraEvents: List<DomainEvent> = emptyList(),
)

interface RoleHandler {
    val role: PlayerRole

    /** Which action types this handler accepts in a given phase + sub-phase. */
    fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType>

    /** Validate and execute the action. Caller is responsible for advancing the night sub-phase. */
    fun handle(action: GameActionRequest, context: GameContext): GameActionResult

    /** Ordered night sub-phases owned by this role. Empty = no night action. */
    fun nightSubPhases(): List<NightSubPhase> = emptyList()

    /** Hook called when game enters DAY — override to produce day-start events. */
    fun onDayEnter(context: GameContext): List<DomainEvent> = emptyList()

    /** Hook called before elimination — return modifier to cancel or add effects. */
    fun onEliminationPending(context: GameContext, targetId: String): EliminationModifier? = null
}
