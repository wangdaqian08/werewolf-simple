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
import org.springframework.context.annotation.Lazy
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
    @Lazy private val nightWaitingScheduler: NightWaitingScheduler,
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
     *
     * @param withWaiting When true (no-sheriff first night), starts in WAITING sub-phase
     *   and schedules an automatic 5-second transition to WEREWOLF_PICK.
     */
    @Transactional
    fun initNight(gameId: Int, newDayNumber: Int, previousGuardTarget: String? = null, withWaiting: Boolean = false) {
        val context = contextLoader.load(gameId)
        val initialSubPhase = if (withWaiting) NightSubPhase.WAITING else firstSubPhase(context)

        val nightPhase = NightPhase(
            gameId = gameId,
            dayNumber = newDayNumber,
            subPhase = initialSubPhase,
            prevGuardTargetUserId = previousGuardTarget,
        )
        nightPhaseRepository.save(nightPhase)

        val game = context.game
        game.phase = GamePhase.NIGHT
        game.subPhase = null
        game.dayNumber = newDayNumber
        gameRepository.save(game)

        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.NIGHT, initialSubPhase.name))

        if (withWaiting) {
            nightWaitingScheduler.scheduleAdvance(gameId)
        }
    }

    /**
     * Checks if the current night sub-phase is stuck (role has no alive players) and advances if needed.
     * Call this on backend startup or when loading a game context to recover from restarts.
     */
    @Transactional
    fun recoverStuckNightPhase(gameId: Int) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return

        // Only recover if game is in NIGHT phase and not COMPLETE
        if (context.game.phase != GamePhase.NIGHT || nightPhase.subPhase == NightSubPhase.COMPLETE) {
            return
        }

        val currentSubPhase = nightPhase.subPhase
        val roleForCurrentPhase = getRoleForSubPhase(currentSubPhase)

        if (roleForCurrentPhase != null) {
            val hasAlivePlayersForRole = context.alivePlayers.any { it.role == roleForCurrentPhase }
            if (!hasAlivePlayersForRole) {
                // Current role has no alive players - advance to next sub-phase
                advance(gameId, currentSubPhase)
            }
        }
    }

    /**
     * Advances the night from WAITING to the first real sub-phase (WEREWOLF_PICK).
     * Called automatically by [NightWaitingScheduler] after the 5-second countdown.
     */
    @Transactional
    fun advanceFromWaiting(gameId: Int) {
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        if (nightPhase.subPhase != NightSubPhase.WAITING) return // idempotent guard
        val firstRealSubPhase = firstSubPhase(context)
        nightPhase.subPhase = firstRealSubPhase
        nightPhaseRepository.save(nightPhase)
        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, firstRealSubPhase))
    }

    /**
     * Advances to a specific sub-phase after a scheduled delay.
     * Used when a role has no alive players and we want to wait 20 seconds before moving to the next role.
     */
    @Transactional
    fun advanceToSubPhase(gameId: Int, targetSubPhase: NightSubPhase?) {
        if (targetSubPhase == null) return
        val context = contextLoader.load(gameId)
        val nightPhase = context.nightPhase ?: return
        nightPhase.subPhase = targetSubPhase
        nightPhaseRepository.save(nightPhase)
        stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, targetSubPhase))
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
            // Check if the next role has any alive players
            val roleForNextPhase = getRoleForSubPhase(nextSubPhase)
            val hasAlivePlayersForRole = roleForNextPhase?.let { role ->
                context.alivePlayers.any { it.role == role }
            } ?: true // If we can't determine the role, assume we should advance

            if (roleForNextPhase != null && !hasAlivePlayersForRole) {
                // All players with this role are dead - wait 20 seconds before advancing
                nightWaitingScheduler.scheduleAdvance(gameId, 20_000, nextSubPhase)
            } else {
                // At least one player with this role is alive - advance immediately
                nightPhase.subPhase = nextSubPhase
                nightPhaseRepository.save(nightPhase)
                stompPublisher.broadcastGame(gameId, DomainEvent.NightSubPhaseChanged(gameId, nextSubPhase))
            }
        }
    }

    private fun getRoleForSubPhase(subPhase: NightSubPhase): PlayerRole? {
        return when (subPhase) {
            NightSubPhase.WEREWOLF_PICK -> PlayerRole.WEREWOLF
            NightSubPhase.SEER_PICK, NightSubPhase.SEER_RESULT -> PlayerRole.SEER
            NightSubPhase.WITCH_ACT -> PlayerRole.WITCH
            NightSubPhase.GUARD_PICK -> PlayerRole.GUARD
            else -> null
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
        val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)

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

            // Fire onDayEnter hooks — allows role handlers to produce day-start events
            val activeRoles = updatedContext.alivePlayers.map { it.role }.toSet()
            handlers.filter { it.role in activeRoles }.forEach { handler ->
                val events = handler.onDayEnter(updatedContext)
                events.forEach { stompPublisher.broadcastGame(gameId, it) }
            }
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
