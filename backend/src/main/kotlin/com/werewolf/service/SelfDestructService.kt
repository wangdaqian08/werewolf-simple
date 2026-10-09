package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.model.*
import com.werewolf.repository.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * Handles `WOLF_SELF_DESTRUCT` action: a werewolf publicly kills themselves during
 * SHERIFF_ELECTION, DAY_DISCUSSION, or DAY_VOTING to end the day without a vote.
 * A White Wolf King (白狼王) may name a target (`targetUserId`) to take with it: the
 * target dies too, a taken hunter gets no shot, and a taken sheriff hands the badge over.
 *
 * Per memory no-bang-bang-in-production: NEVER use `!!` in this file. Use `?: error("...")`.
 */
@Service
class SelfDestructService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val userRepository: UserRepository,
    private val stompPublisher: StompPublisher,
    private val actionLogService: ActionLogService,
    private val nightOrchestrator: NightOrchestrator,
    private val winConditionChecker: WinConditionChecker,
    private val rewardSettlementService: RewardSettlementService,
    private val dayRevealAdvancer: DayRevealAdvancer,
) {
    private val log = LoggerFactory.getLogger(SelfDestructService::class.java)

    private val allowedPhases = setOf(
        GamePhase.SHERIFF_ELECTION,
        GamePhase.DAY_DISCUSSION,
        GamePhase.DAY_VOTING,
    )

    @Transactional
    fun selfDestruct(request: GameActionRequest, context: GameContext): GameActionResult {
        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Player not found")
        if (!actor.alive) return GameActionResult.Rejected("Dead players cannot act")
        if (!actor.role.isWolf)
            return GameActionResult.Rejected("Only werewolves can self-destruct")
        if (context.game.phase !in allowedPhases)
            return GameActionResult.Rejected("Self-destruct not allowed in phase ${context.game.phase}")

        // Validate the optional take before changing anything.
        if (request.targetUserId != null) {
            if (actor.role != PlayerRole.WHITE_WOLF_KING)
                return GameActionResult.Rejected("Only the White Wolf King can take a player")
            if (request.targetUserId == actor.userId)
                return GameActionResult.Rejected("The White Wolf King cannot take itself")
            context.alivePlayerById(request.targetUserId)
                ?: return GameActionResult.Rejected("Target not found or dead")
        }
        // House rule: the last wolf may still self-destruct, but takes no one.
        val isLastWolf = context.alivePlayers.none { it.role.isWolf && it.userId != actor.userId }
        val takenUserId = if (isLastWolf) null else request.targetUserId

        val user = userRepository.findById(request.actorUserId).orElse(null)
        val nickname = user?.nickname ?: request.actorUserId

        // Mark wolf dead. If they were the sheriff, also burn the badge so the
        // ⭐ disappears from every player slot (game.sheriffUserId alone is not
        // enough — PlayerSlot reads GamePlayer.sheriff).
        val wolfPlayer = gamePlayerRepository.findByGameIdAndUserId(context.gameId, request.actorUserId)
            .orElse(null) ?: return GameActionResult.Rejected("Player not found in DB")
        val takenPlayer = takenUserId?.let {
            gamePlayerRepository.findByGameIdAndUserId(context.gameId, it).orElse(null)
                ?: return GameActionResult.Rejected("Target not found in DB")
        }
        wolfPlayer.alive = false
        wolfPlayer.diedDay = context.game.dayNumber
        val wasSheriff = context.game.sheriffUserId == request.actorUserId
        if (wasSheriff) {
            wolfPlayer.sheriff = false
            context.game.sheriffUserId = null
        }
        gamePlayerRepository.save(wolfPlayer)

        // The taken player just dies: no hunter shot. A taken sheriff keeps the
        // badge for now so they can hand it over (routed below).
        if (takenPlayer != null) {
            takenPlayer.alive = false
            takenPlayer.diedDay = context.game.dayNumber
            gamePlayerRepository.save(takenPlayer)
        }

        // Set daySkipVoting flag + record who self-destructed (and whom they took)
        // for the day death banner. All three are cleared at night-init.
        context.game.daySkipVoting = true
        context.game.selfDestructUserId = request.actorUserId
        context.game.selfDestructTakenUserId = takenPlayer?.userId

        // Phase transition
        when (context.game.phase) {
            GamePhase.SHERIFF_ELECTION -> {
                // Abort election → DAY_DISCUSSION/RESULT_HIDDEN
                context.game.phase = GamePhase.DAY_DISCUSSION
                context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            }
            GamePhase.DAY_DISCUSSION -> {
                // Stay in current sub-phase — flag change enables host's "进入夜晚" button
            }
            GamePhase.DAY_VOTING -> {
                // Standard 自爆 rule: the day ends with NO vote. Don't leave the
                // game on a DAY_VOTING vote-result screen (reported as "still goes
                // to voting"); the night deaths were already revealed before voting
                // began, so route to DAY_DISCUSSION/RESULT_REVEALED. The host's
                // single 进入夜晚 button (daySkipVoting=true) then advances straight
                // to night — identical to a self-destruct during discussion.
                context.game.phase = GamePhase.DAY_DISCUSSION
                context.game.subPhase = DaySubPhase.RESULT_REVEALED.name
            }
            else -> {
                // allowedPhases guard above prevents other phases reaching here
            }
        }

        // A taken sheriff hands the badge over next (DayRevealAdvancer: badge first).
        // While the night result is still hidden, the host's reveal does this routing.
        val tookSheriff = takenPlayer != null && takenPlayer.userId == context.game.sheriffUserId
        if (tookSheriff && context.game.phase == GamePhase.DAY_DISCUSSION &&
            context.game.subPhase == DaySubPhase.RESULT_REVEALED.name
        ) {
            context.game.subPhase = dayRevealAdvancer.nextSubPhase(context.gameId).name
        }

        gameRepository.save(context.game)

        // Record event
        val takenNickname = takenPlayer?.let { userRepository.findById(it.userId).orElse(null)?.nickname ?: it.userId }
        actionLogService.recordSelfDestruct(
            context.gameId, context.game.dayNumber, request.actorUserId, nickname, actor.seatIndex,
            takenPlayer?.userId, takenNickname, takenPlayer?.seatIndex,
        )

        // Broadcast after commit
        val eventsToSend = mutableListOf<DomainEvent>()
        eventsToSend.add(DomainEvent.WolfSelfDestructed(context.gameId, request.actorUserId, actor.seatIndex, nickname))
        if (wasSheriff) {
            eventsToSend.add(DomainEvent.BadgeHandover(context.gameId, request.actorUserId, null))
        }
        eventsToSend.add(DomainEvent.PhaseChanged(context.gameId, context.game.phase, context.game.subPhase))

        // Win-condition check (last wolf dying → villager win)
        val freshAlivePlayers = context.players.filter {
            it.userId != request.actorUserId && it.userId != takenUserId && it.alive
        }
        val winner = winConditionChecker.check(
            alivePlayers = freshAlivePlayers,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = com.werewolf.game.phase.HardModeCounterplay(
                hasGuard = freshAlivePlayers.any { it.role == PlayerRole.GUARD },
                hasWitchWithPotions = freshAlivePlayers.any { it.role == PlayerRole.WITCH },
                hasHunterWithBullet = freshAlivePlayers.any { it.role == PlayerRole.HUNTER },
            ),
        )
        if (winner != null) {
            context.game.winner = winner
            context.game.phase = GamePhase.GAME_OVER
            context.game.endedAt = LocalDateTime.now()
            gameRepository.save(context.game)
            rewardSettlementService.settle(context.gameId, winner)
            eventsToSend.add(DomainEvent.GameOver(context.gameId, winner))
        }

        broadcastAllAfterCommit(context.gameId, eventsToSend)

        log.info("[selfDestruct] game={} actor={} taken={} phase={} subPhase={} wasSheriff={} winner={}",
            context.gameId, request.actorUserId, takenUserId, context.game.phase, context.game.subPhase, wasSheriff, winner)

        return GameActionResult.Success()
    }

    private fun broadcastAllAfterCommit(gameId: Int, events: List<DomainEvent>) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    events.forEach { stompPublisher.broadcastGame(gameId, it) }
                }
            })
        } else {
            events.forEach { stompPublisher.broadcastGame(gameId, it) }
        }
    }
}
