package com.werewolf.game.role

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.VotingSubPhase
import com.werewolf.repository.GamePlayerRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Order(12)
@Component
class IdiotHandler(
    private val gamePlayerRepository: GamePlayerRepository,
) : RoleHandler {
    override val role = PlayerRole.IDIOT

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.DAY_VOTING && subPhase == VotingSubPhase.VOTE_RESULT.name)
            setOf(ActionType.IDIOT_REVEAL)
        else
            emptySet()

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = gamePlayerRepository.findByGameIdAndUserId(context.gameId, action.actorUserId)
            .orElse(null) ?: return GameActionResult.Rejected("Player not found")

        if (actor.role != PlayerRole.IDIOT)
            return GameActionResult.Rejected("Only the Idiot can reveal")

        actor.canVote = false
        actor.idiotRevealed = true
        gamePlayerRepository.save(actor)

        return GameActionResult.Success(
            events = listOf(DomainEvent.IdiotRevealed(context.gameId, actor.userId))
        )
    }
}
