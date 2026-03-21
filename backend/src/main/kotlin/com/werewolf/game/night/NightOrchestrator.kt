package com.werewolf.game.night

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.NightPhaseRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class NightOrchestrator(
    private val handlers: List<RoleHandler>,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val winConditionChecker: WinConditionChecker,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
) {
    /**
     * Ordered night sub-phases for this game based on which roles are active.
     * Werewolves are always included; other roles follow the room config.
     */
    fun nightSequence(context: GameContext): List<NightSubPhase> {
        val activeRoles = mutableSetOf(PlayerRole.WEREWOLF)
        if (context.room.hasSeer) activeRoles.add(PlayerRole.SEER)
        if (context.room.hasWitch) activeRoles.add(PlayerRole.WITCH)
        if (context.room.hasGuard) activeRoles.add(PlayerRole.GUARD)
        if (context.room.hasIdiot) activeRoles.add(PlayerRole.IDIOT)

        return handlers
            .filter { it.role in activeRoles }
            .flatMap { it.nightSubPhases() }
    }

    fun firstSubPhase(context: GameContext): NightSubPhase =
        nightSequence(context).firstOrNull() ?: NightSubPhase.WEREWOLF_PICK

    /**
     * Initialise a new night phase and transition the game to NIGHT.
     * Called from GamePhasePipeline (first night) and VotingPipeline (subsequent nights).
     */
    @Transactional
    fun initNight(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null) {
        val context = contextLoader.load(gameId)
        val firstSubPhase = firstSubPhase(context)

        val nightPhase = NightPhase(
            gameId = gameId,
            dayNumber = newDayNumber,
            subPhase = firstSubPhase,
            prevGuardTargetUserId = previousGuardTarget,
        )
        nightPhaseRepository.save(nightPhase)

        val game = context.game
        game.phase = GamePhase.NIGHT
        game.subPhase = null
        game.dayNumber = newDayNumber
        gameRepository.save(game)

        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.NIGHT, firstSubPhase.name))
    }

    /**
     * Called after a night role action completes.
     * Advances to the next sub-phase in the sequence, or resolves night kills and moves to DAY.
     */
    @Transactional
    fun advance(gameId: Int, completedSubPhase: NightSubPhase) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return

        val sequence = nightSequence(context)
        val currentIdx = sequence.indexOf(completedSubPhase)
        if (currentIdx < 0) return

        val nextSubPhase = sequence.getOrNull(currentIdx + 1)

        if (nextSubPhase == null) {
            resolveNightKills(context, nightPhase)
        } else {
            nightPhase.subPhase = nextSubPhase
            nightPhaseRepository.save(nightPhase)
            stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, nextSubPhase))
        }
    }

    @Transactional
    fun resolveNightKills(context: GameContext, nightPhase: NightPhase) {
        val gameId = context.gameId
        val kills = mutableListOf<String>()

        val wolfTarget = nightPhase.wolfTargetUserId
        if (wolfTarget != null) {
            val antidoteSaved = nightPhase.witchAntidoteUsed
            val guardSaved = nightPhase.guardTargetUserId == wolfTarget
            if (!antidoteSaved && !guardSaved) {
                kills.add(wolfTarget)
            }
        }

        val poisonTarget = nightPhase.witchPoisonTargetUserId
        if (poisonTarget != null) {
            kills.add(poisonTarget)
        }

        // Apply kills
        for (killId in kills.distinct()) {
            gamePlayerRepository.findByGameIdAndUserId(gameId, killId).ifPresent { player ->
                player.alive = false
                gamePlayerRepository.save(player)
            }
        }

        nightPhase.subPhase = NightSubPhase.COMPLETE
        nightPhaseRepository.save(nightPhase)

        stompPublisher.broadcastGame(gameId, DomainEvent.NightResult(gameId, kills.distinct()))

        // Check win condition
        val updatedContext = contextLoader.load(gameId)
        val winner = winConditionChecker.check(updatedContext.alivePlayers)

        if (winner != null) {
            endGame(updatedContext, winner)
        } else {
            val game = updatedContext.game
            game.phase = GamePhase.DAY
            game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            gameRepository.save(game)
            stompPublisher.broadcastGame(
                gameId,
                DomainEvent.PhaseChanged(gameId, GamePhase.DAY, DaySubPhase.RESULT_HIDDEN.name)
            )
        }
    }

    private fun endGame(context: GameContext, winner: WinnerSide) {
        val game = context.game
        game.winner = winner
        game.phase = GamePhase.GAME_OVER
        game.endedAt = LocalDateTime.now()
        gameRepository.save(game)
        stompPublisher.broadcastGame(context.gameId, DomainEvent.GameOver(context.gameId, winner))
    }
}
