package com.werewolf.game.role

import com.werewolf.audio.RoleRegistry
import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.model.RoleDelayConfig
import com.werewolf.repository.NightPhaseRepository
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Order(1)
@Component
class WerewolfHandler(
    private val nightPhaseRepository: NightPhaseRepository,
    private val audioService: com.werewolf.service.AudioService
) : RoleHandler {

    override val role = PlayerRole.WEREWOLF

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.WEREWOLF_PICK.name)
            setOf(ActionType.WOLF_KILL, ActionType.WOLF_SELECT)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.WEREWOLF_PICK)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        if (action.actionType != ActionType.WOLF_KILL && action.actionType != ActionType.WOLF_SELECT)
            return GameActionResult.Rejected("Unknown action: ${action.actionType}")

        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.WEREWOLF) return GameActionResult.Rejected("Not a werewolf")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.WEREWOLF_PICK)
            return GameActionResult.Rejected("Not in WEREWOLF_PICK sub-phase")

        val target = action.targetUserId
            ?: return GameActionResult.Rejected("Target required")
        if (context.alivePlayerById(target) == null)
            return GameActionResult.Rejected("Target not found or dead")

        nightPhase.wolfTargetUserId = target
        nightPhaseRepository.save(nightPhase)

        return if (action.actionType == ActionType.WOLF_SELECT)
            GameActionResult.Success(events = listOf(DomainEvent.WolfSelectionChanged(context.gameId, target)))
        else
            GameActionResult.Success()
    }

    override fun onEliminationPending(context: GameContext, targetId: String): EliminationModifier? = null

    /**
     * Get audio configuration for this role
     * Allows handlers to access role-specific audio settings
     */
    fun getAudioConfig() = RoleRegistry.getAudioConfig(role)

    /**
     * Get default delay time for dead role simulation
     */
    fun getDefaultDelayMs() = RoleRegistry.getDefaultDelayMs(role) ?: 5000L

    /**
     * 获取角色的延迟配置
     * 从 Room.config 中读取配置化延迟
     */
    fun getDelayConfig(context: GameContext): RoleDelayConfig {
        val gameConfig = context.room.config ?: return RoleDelayConfig.getDefaultForRole(role)
        return gameConfig.getDelayForRole(role)
    }
}
