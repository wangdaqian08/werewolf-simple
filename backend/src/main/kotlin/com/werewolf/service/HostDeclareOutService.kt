package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.voting.VotingPipeline
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Handles `HOST_DECLARE_OUT`: the host declares a player out because they had to
 * leave. The player dies at once, publicly ("判定出局"), role hidden; no shot, a
 * sheriff's badge is destroyed. Daytime only — the night loop cannot be released.
 * The one dead target allowed is the sheriff who left during BADGE_HANDOVER: the
 * host destroys the badge for them so the day can go on.
 *
 * Per memory no-bang-bang-in-production: NEVER use `!!` in this file.
 */
@Service
class HostDeclareOutService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val userRepository: UserRepository,
    private val stompPublisher: StompPublisher,
    private val actionLogService: ActionLogService,
    private val nightOrchestrator: NightOrchestrator,
    private val winConditionChecker: WinConditionChecker,
    private val rewardSettlementService: RewardSettlementService,
    private val sheriffService: SheriffService,
    private val votingPipeline: VotingPipeline,
) {
    private val log = LoggerFactory.getLogger(HostDeclareOutService::class.java)

    private fun inWindow(game: Game): Boolean = when (game.phase) {
        GamePhase.ROLE_REVEAL, GamePhase.SHERIFF_ELECTION, GamePhase.DAY_VOTING -> true
        // Before the reveal the host reveals first: last night's deaths are still hidden.
        GamePhase.DAY_DISCUSSION -> game.subPhase != DaySubPhase.RESULT_HIDDEN.name
        else -> false
    }

    private fun inBadgeHandover(game: Game): Boolean =
        game.subPhase == DaySubPhase.BADGE_HANDOVER.name &&
                (game.phase == GamePhase.DAY_DISCUSSION || game.phase == GamePhase.DAY_VOTING)

    @Transactional
    fun declareOut(request: GameActionRequest, context: GameContext): GameActionResult {
        val game = context.game
        if (request.actorUserId != game.hostUserId)
            return GameActionResult.Rejected("Only the host can declare a player out")
        val targetId = request.targetUserId ?: return GameActionResult.Rejected("Target required")
        if (targetId == game.hostUserId)
            return GameActionResult.Rejected("The host cannot declare themselves out")
        if (!inWindow(game))
            return GameActionResult.Rejected("A player can only be declared out during the day")
        val target = context.playerById(targetId) ?: return GameActionResult.Rejected("Target not found")
        val nickname = userRepository.findById(targetId).orElse(null)?.nickname ?: targetId

        // A sheriff who left mid-handover: destroy the badge for them (BADGE_DESTROY's own flow).
        if (inBadgeHandover(game) && targetId == game.sheriffUserId) {
            val result = votingPipeline.handleBadge(
                request.copy(actorUserId = targetId, actionType = ActionType.BADGE_DESTROY, targetUserId = null),
                context,
            )
            if (result !is GameActionResult.Success) return result
            if (!target.alive) {
                target.removedByHost = true
                gamePlayerRepository.save(target)
            }
            record(context, target, nickname)
            stompPublisher.broadcastGameAfterCommit(
                context.gameId, DomainEvent.PlayerDeclaredOut(context.gameId, targetId, target.seatIndex, nickname),
            )
            return GameActionResult.Success()
        }
        if (!target.alive) return GameActionResult.Rejected("Target is already dead")

        val newlyConfirmed = !target.confirmedRole
        target.alive = false
        target.diedDay = game.dayNumber
        target.removedByHost = true
        target.confirmedRole = true // ROLE_REVEAL: START_NIGHT waits for every confirmation
        val wasSheriff = game.sheriffUserId == targetId
        if (wasSheriff) {
            target.sheriff = false
            game.sheriffUserId = null
        }
        gamePlayerRepository.save(target)
        record(context, target, nickname)

        val events = mutableListOf<DomainEvent>(
            DomainEvent.PlayerDeclaredOut(context.gameId, targetId, target.seatIndex, nickname),
        )
        if (wasSheriff) events.add(DomainEvent.BadgeHandover(context.gameId, targetId, null))
        if (newlyConfirmed) events.add(DomainEvent.RoleConfirmed(context.gameId, targetId))

        // During the sheriff campaign last night's victims are still alive in the DB.
        val lastNight = context.allNightPhases.firstOrNull { it.dayNumber == game.dayNumber }
        val pendingNightKills = if (game.phase == GamePhase.SHERIFF_ELECTION && lastNight != null)
            nightOrchestrator.computePendingKills(context.gameId, lastNight) else emptyList()
        val alive = context.players.filter { it.alive && it.userId !in pendingNightKills }
        val winner = winConditionChecker.check(
            alivePlayers = alive,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = HardModeCounterplay.of(alive, context.allNightPhases, knightDuelUsed = context.game.knightDuelTargetUserId != null),
            roster = context.players,
        )
        if (winner != null) {
            // The game ends before the reveal, so last night's deaths become real now.
            if (pendingNightKills.isNotEmpty()) {
                nightOrchestrator.applyNightKills(context.gameId, pendingNightKills)
                actionLogService.recordNightDeaths(context.gameId, game.dayNumber, pendingNightKills)
            }
            game.winner = winner
            game.phase = GamePhase.GAME_OVER
            game.endedAt = LocalDateTime.now()
            gameRepository.save(game)
            rewardSettlementService.settle(context.gameId, winner)
            events.add(DomainEvent.GameOver(context.gameId, winner))
        } else {
            when (game.phase) {
                GamePhase.SHERIFF_ELECTION -> sheriffService.onPlayerDeclaredOut(context, targetId)
                GamePhase.DAY_VOTING -> votingPipeline.onPlayerDeclaredOut(context, targetId)
                else -> {}
            }
            // Clients refetch state on PhaseChanged (counts, ROLE_REVEAL confirmations).
            events.add(DomainEvent.PhaseChanged(context.gameId, game.phase, game.subPhase))
        }
        gameRepository.save(game)
        events.forEach { stompPublisher.broadcastGameAfterCommit(context.gameId, it) }

        log.info("[declareOut] game={} target={} phase={} subPhase={} wasSheriff={} winner={}",
            context.gameId, targetId, game.phase, game.subPhase, wasSheriff, winner)
        return GameActionResult.Success()
    }

    private fun record(context: GameContext, target: GamePlayer, nickname: String) =
        actionLogService.recordDeclaredOut(context.gameId, context.game.dayNumber, target.userId, nickname, target.seatIndex)
}
