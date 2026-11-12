package com.werewolf.game.role

import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.GravekeeperResult
import com.werewolf.model.NightPhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.EliminationHistoryRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Order(6)
@Component
class GravekeeperHandler(private val eliminationHistoryRepository: EliminationHistoryRepository) : RoleHandler {

    override val role = PlayerRole.GRAVEKEEPER

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.GRAVEKEEPER_RESULT.name)
            setOf(ActionType.GRAVEKEEPER_CONFIRM)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.GRAVEKEEPER_RESULT)

    /**
     * Stores what a living gravekeeper learns tonight: the camp of yesterday's vote-out.
     * Only a vote-out writes elimination_history, so a shot, duel, take, declare-out or idiot
     * reveal reads NONE. The camp is the real role's: a learned mechanical wolf is still a wolf.
     */
    override fun onNightInit(context: GameContext, nightPhase: NightPhase) {
        if (context.alivePlayers.none { it.role == PlayerRole.GRAVEKEEPER }) return
        val exile = if (nightPhase.dayNumber > 1)
            eliminationHistoryRepository.findByGameIdAndDayNumber(context.gameId, nightPhase.dayNumber - 1).orElse(null)
        else null
        val exiledRole = exile?.eliminatedRole
        nightPhase.gravekeeperResult = when {
            exiledRole == null -> GravekeeperResult.NONE
            exiledRole.isWolf -> GravekeeperResult.WOLF
            else -> GravekeeperResult.GOOD
        }
        nightPhase.gravekeeperExiledUserId = if (exiledRole == null) null else exile.eliminatedUserId
    }

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.GRAVEKEEPER) return GameActionResult.Rejected("Not the gravekeeper")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.GRAVEKEEPER_RESULT)
            return GameActionResult.Rejected("Not in GRAVEKEEPER_RESULT sub-phase")
        return GameActionResult.Success()
    }
}
