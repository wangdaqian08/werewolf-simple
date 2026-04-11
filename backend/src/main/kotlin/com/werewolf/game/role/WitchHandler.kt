package com.werewolf.game.role

import com.werewolf.audio.RoleRegistry
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.model.ActionType
import com.werewolf.model.GamePhase
import com.werewolf.model.NightSubPhase
import com.werewolf.model.PlayerRole
import com.werewolf.repository.NightPhaseRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Order(3)
@Component
class WitchHandler(private val nightPhaseRepository: NightPhaseRepository) : RoleHandler {

    private val log: Logger = LoggerFactory.getLogger(WitchHandler::class.java)

    override val role = PlayerRole.WITCH

    override fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType> =
        if (phase == GamePhase.NIGHT && subPhase == NightSubPhase.WITCH_ACT.name) setOf(ActionType.WITCH_ACT)
        else emptySet()

    override fun nightSubPhases(): List<NightSubPhase> = listOf(NightSubPhase.WITCH_ACT)

    override fun handle(action: GameActionRequest, context: GameContext): GameActionResult {
        log.info("[WitchHandler] Handling WITCH_ACT action for game ${context.gameId} by ${action.actorUserId}")
        
        if (action.actionType != ActionType.WITCH_ACT) return GameActionResult.Rejected("Unknown action: ${action.actionType}")

        val actor = context.playerById(action.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.WITCH) return GameActionResult.Rejected("Not the witch")
        if (!actor.alive) return GameActionResult.Rejected("Actor is dead")

        val nightPhase = context.nightPhase
            ?: return GameActionResult.Rejected("No active night phase")
        if (nightPhase.subPhase != NightSubPhase.WITCH_ACT)
            return GameActionResult.Rejected("Not in WITCH_ACT sub-phase")

        val useAntidote = action.payload["useAntidote"] as? Boolean ?: false
        val poisonTarget = action.payload["poisonTargetUserId"] as? String

        log.info("[WitchHandler] Decisions: useAntidote=$useAntidote, poisonTarget=$poisonTarget")

        // Antidote can only be used once per game
        val antidoteEverUsed = context.allNightPhases.any { it.witchAntidoteUsed && it.id != nightPhase.id }
        if (useAntidote && antidoteEverUsed)
            return GameActionResult.Rejected("Antidote already used in a previous round")

        // Poison can only be used once per game
        val poisonEverUsed = context.allNightPhases.any { it.witchPoisonTargetUserId != null && it.id != nightPhase.id }
        if (poisonTarget != null && poisonEverUsed)
            return GameActionResult.Rejected("Poison already used in a previous round")

        // Cannot use both antidote and poison the same night
        if (useAntidote && poisonTarget != null)
            return GameActionResult.Rejected("Cannot use antidote and poison on the same night")

        if (poisonTarget != null && context.alivePlayerById(poisonTarget) == null)
            return GameActionResult.Rejected("Poison target not found or dead")

        if (useAntidote) {
            nightPhase.witchAntidoteUsed = true
            log.info("[WitchHandler] Antidote used")
        }
        if (poisonTarget != null) {
            nightPhase.witchPoisonTargetUserId = poisonTarget
            log.info("[WitchHandler] Poison used on $poisonTarget")
        }

        nightPhaseRepository.save(nightPhase)
        log.info("[WitchHandler] Witch action completed successfully")
        return GameActionResult.Success()
    }

    /**
     * Get audio configuration for this role
     */
    fun getAudioConfig() = RoleRegistry.getAudioConfig(role)

    /**
     * Get default delay time for dead role simulation
     */
    fun getDefaultDelayMs() = RoleRegistry.getDefaultDelayMs(role) ?: 5000L
}