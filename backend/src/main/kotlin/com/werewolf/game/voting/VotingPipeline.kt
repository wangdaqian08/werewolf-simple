package com.werewolf.game.voting

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.VoteRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

@Service
class VotingPipeline(
    private val handlers: List<RoleHandler>,
    private val voteRepository: VoteRepository,
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val eliminationHistoryRepository: EliminationHistoryRepository,
    private val winConditionChecker: WinConditionChecker,
    private val stompPublisher: StompPublisher,
    private val contextLoader: GameContextLoader,
    private val nightOrchestrator: NightOrchestrator,
    private val actionLogService: ActionLogService,
) {
    private val log = LoggerFactory.getLogger(VotingPipeline::class.java)

    @Transactional
    fun submitVote(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.DAY_VOTING) {
            // DIAGNOSTIC: compare ctx vs fresh DB read to expose stale-context vs uncommitted-tx
            val freshGame = gameRepository.findById(context.gameId).orElse(null)
            log.warn(
                "[submitVote] phase mismatch: ctx.phase={} ctx.subPhase={} freshDb.phase={} freshDb.subPhase={} actor={} game={}",
                context.game.phase, context.game.subPhase,
                freshGame?.phase, freshGame?.subPhase,
                request.actorUserId, context.gameId,
            )
            return GameActionResult.Rejected("Not in voting phase")
        }
        if (context.game.subPhase !in setOf(VotingSubPhase.VOTING.name, VotingSubPhase.RE_VOTING.name))
            return GameActionResult.Rejected("Voting is not open")

        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (!actor.alive) return GameActionResult.Rejected("Dead players cannot vote")
        if (!actor.canVote) return GameActionResult.Rejected("You have lost your voting right")

        val alreadyVoted = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        ).any { it.voterUserId == request.actorUserId }
        if (alreadyVoted) return GameActionResult.Rejected("Already voted this round")

        val target = request.targetUserId
        if (target != null && context.alivePlayerById(target) == null)
            return GameActionResult.Rejected("Target not found or dead")

        voteRepository.save(
            Vote(
                gameId = context.gameId,
                voteContext = VoteContext.ELIMINATION,
                dayNumber = context.game.dayNumber,
                voterUserId = request.actorUserId,
                targetUserId = target,
            )
        )

        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.VoteSubmitted(context.gameId, request.actorUserId))
        return GameActionResult.Success()
    }

    @Transactional
    fun unvote(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.DAY_VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase !in setOf(VotingSubPhase.VOTING.name, VotingSubPhase.RE_VOTING.name))
            return GameActionResult.Rejected("Voting is not open")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        val myVote = votes.firstOrNull { it.voterUserId == request.actorUserId }
            ?: return GameActionResult.Rejected("No vote to retract")

        voteRepository.delete(myVote)
        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.VoteSubmitted(context.gameId, request.actorUserId))
        return GameActionResult.Success()
    }

    @Transactional
    fun revealTally(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal tally")
        if (context.game.phase != GamePhase.DAY_VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase !in setOf(VotingSubPhase.VOTING.name, VotingSubPhase.RE_VOTING.name))
            return GameActionResult.Rejected("Not in a voting sub-phase")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        val tally: Map<String, Double> = TallyCalculator.calculateWeightedTally(
            votes,
            context.game.sheriffUserId
        )

        val eliminated = TallyCalculator.findTopCandidate(tally)
        val wasRevote = context.game.subPhase == VotingSubPhase.RE_VOTING.name

        context.game.subPhase = VotingSubPhase.VOTE_RESULT.name
        gameRepository.save(context.game)

        // Prepare events to send after transaction commit
        val eventsToSend = mutableListOf<DomainEvent>()
        eventsToSend.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTE_RESULT.name))
        eventsToSend.add(DomainEvent.VoteTally(context.gameId, eliminated, tally))

        // Record vote result in action log
        val eliminatedRole = eliminated?.let {
            gamePlayerRepository.findByGameIdAndUserId(context.gameId, it).orElse(null)?.role
        }
        actionLogService.recordVoteResult(
            context.gameId, context.game.dayNumber, votes, tally,
            context.game.sheriffUserId, eliminated, eliminatedRole
        )

        // Collect events from elimination process
        if (eliminated != null) {
            collectEliminationEvents(context, eliminated, eventsToSend)
        } else if (!wasRevote) {
            // First-round tie — give players a second vote (open to all living candidates)
            processRevoteWithEventCollection(context, eventsToSend)
        } else {
            // Second-round tie — no elimination, skip to night
            processGoToNightWithEventCollection(context, eventsToSend)
        }

        // Broadcast all events after transaction commit
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCommit() {
                    eventsToSend.forEach { stompPublisher.broadcastGame(context.gameId, it) }
                }
            })
        } else {
            eventsToSend.forEach { stompPublisher.broadcastGame(context.gameId, it) }
        }

        return GameActionResult.Success()
    }

    @Transactional
    fun continueToNight(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can advance")
        if (context.game.phase != GamePhase.DAY_VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase != VotingSubPhase.VOTE_RESULT.name)
            return GameActionResult.Rejected("Not in VOTE_RESULT sub-phase")
        goToNight(context)
        return GameActionResult.Success()
    }

    @Transactional
    fun handleHunterShoot(request: GameActionRequest, context: GameContext): GameActionResult {
        // Accept HUNTER_SHOOT under either parent phase:
        //   - DAY_VOTING/HUNTER_SHOOT: hunter voted out (existing path).
        //   - DAY_DISCUSSION/HUNTER_SHOOT: hunter killed at night (Phase B).
        // The post-action routing differs between the two — see afterHunterAct
        // and afterHunterActNightRoute.
        val nightRoute = context.game.phase == GamePhase.DAY_DISCUSSION &&
            context.game.subPhase == DaySubPhase.HUNTER_SHOOT.name
        val voteRoute = context.game.phase == GamePhase.DAY_VOTING &&
            context.game.subPhase == VotingSubPhase.HUNTER_SHOOT.name
        if (!nightRoute && !voteRoute)
            return GameActionResult.Rejected("Not in HUNTER_SHOOT sub-phase")

        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.role != PlayerRole.HUNTER)
            return GameActionResult.Rejected("Only the hunter can act here")

        when (request.actionType) {
            ActionType.HUNTER_SHOOT -> {
                val target = request.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                val targetPlayer = gamePlayerRepository.findByGameIdAndUserId(context.gameId, target)
                    .orElse(null) ?: return GameActionResult.Rejected("Target not found")
                if (!targetPlayer.alive) return GameActionResult.Rejected("Target is already dead")

                targetPlayer.alive = false
                gamePlayerRepository.save(targetPlayer)
                actionLogService.recordHunterShot(context.gameId, context.game.dayNumber, actor.userId, target)

                // Record in elimination history
                eliminationHistoryRepository.findByGameIdAndDayNumber(context.gameId, context.game.dayNumber)
                    .ifPresent { history ->
                        history.hunterShotUserId = target
                        history.hunterShotRole = targetPlayer.role
                        eliminationHistoryRepository.save(history)
                    }

                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.HunterShot(context.gameId, actor.userId, target)
                )
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.PlayerEliminated(context.gameId, target, targetPlayer.role)
                )

                // If hunted player was the sheriff, need badge handover
                if (target == context.game.sheriffUserId) {
                    transitionToBadgeHandover(context, nightRoute)
                    return GameActionResult.Success()
                }

                // No badge handover needed — check win then go to next phase
                if (nightRoute) afterHunterActNightRoute(context) else afterHunterAct(context)
            }

            ActionType.HUNTER_PASS -> {
                // Hunter chooses not to shoot; check if hunter was sheriff (badge handover needed)
                if (actor.userId == context.game.sheriffUserId) {
                    transitionToBadgeHandover(context, nightRoute)
                    return GameActionResult.Success()
                }
                if (nightRoute) afterHunterActNightRoute(context) else afterHunterAct(context)
            }

            else -> return GameActionResult.Rejected("Unknown action: ${request.actionType}")
        }
        return GameActionResult.Success()
    }

    @Transactional
    fun handleBadge(request: GameActionRequest, context: GameContext): GameActionResult {
        // Accept BADGE_HANDOVER under either parent phase:
        //   - DAY_VOTING/BADGE_HANDOVER: sheriff voted out (existing path).
        //   - DAY_DISCUSSION/BADGE_HANDOVER: sheriff killed at night (Phase B).
        // After handover the post-resolution destination differs (VOTE_RESULT
        // vs RESULT_REVEALED), so we capture which path we're on up front.
        val nightRoute = context.game.phase == GamePhase.DAY_DISCUSSION &&
            context.game.subPhase == DaySubPhase.BADGE_HANDOVER.name
        val voteRoute = context.game.phase == GamePhase.DAY_VOTING &&
            context.game.subPhase == VotingSubPhase.BADGE_HANDOVER.name
        if (!nightRoute && !voteRoute)
            return GameActionResult.Rejected("Not in BADGE_HANDOVER sub-phase")

        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.userId != context.game.sheriffUserId)
            return GameActionResult.Rejected("Only the current sheriff can hand over the badge")

        val (postPhase, postSubPhase) = if (nightRoute) {
            GamePhase.DAY_DISCUSSION to DaySubPhase.RESULT_REVEALED.name
        } else {
            GamePhase.DAY_VOTING to VotingSubPhase.VOTE_RESULT.name
        }

        when (request.actionType) {
            ActionType.BADGE_PASS -> {
                val target = request.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                val targetPlayer = context.alivePlayerById(target)
                    ?: return GameActionResult.Rejected("Target not found or dead")

                // Update sheriff
                context.game.sheriffUserId = targetPlayer.userId
                context.game.phase = postPhase
                context.game.subPhase = postSubPhase
                gameRepository.save(context.game)

                // Update sheriff flags
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, actor.userId).ifPresent {
                    it.sheriff = false; gamePlayerRepository.save(it)
                }
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, target).ifPresent {
                    it.sheriff = true; gamePlayerRepository.save(it)
                }
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.BadgeHandover(context.gameId, actor.userId, target)
                )
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, postPhase, postSubPhase)
                )
            }

            ActionType.BADGE_DESTROY -> {
                // Update sheriff
                context.game.sheriffUserId = null
                context.game.phase = postPhase
                context.game.subPhase = postSubPhase
                gameRepository.save(context.game)
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, actor.userId).ifPresent {
                    it.sheriff = false; gamePlayerRepository.save(it)
                }
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.BadgeHandover(context.gameId, actor.userId, null)
                )
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, postPhase, postSubPhase)
                )
            }

            else -> return GameActionResult.Rejected("Unknown action: ${request.actionType}")
        }

        // Post-action win check + downstream transition. Vote-out path runs
        // afterElimination (existing behavior — stays in VOTE_RESULT for the
        // host's continue-to-night click). Night-route path doesn't need
        // afterElimination (kills already applied during revealNightResult);
        // game just resumes on RESULT_REVEALED.
        if (!nightRoute) {
            afterElimination(context)
        }
        return GameActionResult.Success()
    }

    /**
     * Transition to BADGE_HANDOVER under the appropriate parent phase
     * (DAY_DISCUSSION when invoked from the night-kill path, DAY_VOTING when
     * invoked from the vote-out hunter-shoot path).
     */
    private fun transitionToBadgeHandover(context: GameContext, nightRoute: Boolean) {
        val (parent, sub) = if (nightRoute) {
            GamePhase.DAY_DISCUSSION to DaySubPhase.BADGE_HANDOVER.name
        } else {
            GamePhase.DAY_VOTING to VotingSubPhase.BADGE_HANDOVER.name
        }
        context.game.phase = parent
        context.game.subPhase = sub
        gameRepository.save(context.game)
        stompPublisher.broadcastGameAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, parent, sub),
        )
    }

    /**
     * After hunter shoots (or passes) under DAY_DISCUSSION/HUNTER_SHOOT (the
     * Phase B night-kill path): check post-shot win condition; if no winner,
     * transition to DAY_DISCUSSION/RESULT_REVEALED so the host can proceed
     * with the day vote.
     *
     * (The DAY_VOTING/HUNTER_SHOOT path uses afterHunterAct, which goes
     * straight to NIGHT — that's the vote-out flow's contract.)
     */
    private fun afterHunterActNightRoute(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = buildCounterplay(updatedContext),
        )
        if (winner != null) {
            endGame(updatedContext, winner)
            return
        }
        val game = updatedContext.game
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        gameRepository.save(game)
        stompPublisher.broadcastGameAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name),
        )
    }

    /** Check win condition; if no winner, stay in VOTE_RESULT for host to proceed to night. */
    private fun afterElimination(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
        )
        if (winner != null) {
            endGame(updatedContext, winner)
        }
        // else: sub-phase stays at VOTE_RESULT — host calls VOTING_CONTINUE to proceed to night
    }

    /** After hunter acts (no badge needed): check win, then go directly to night. */
    private fun afterHunterAct(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
        )
        if (winner != null) {
            endGame(updatedContext, winner)
        } else {
            goToNight(updatedContext)
        }
    }

    private fun goToNight(context: GameContext) {
        val currentGuardTarget = context.allNightPhases
            .firstOrNull { it.dayNumber == context.game.dayNumber }
            ?.guardTargetUserId

        val newDayNumber = context.game.dayNumber + 1
        nightOrchestrator.initNight(context.gameId, newDayNumber, currentGuardTarget)
    }

    private fun endGame(context: GameContext, winner: WinnerSide, events: MutableList<DomainEvent>? = null) {
        val game = context.game
        game.winner = winner
        game.phase = GamePhase.GAME_OVER
        game.endedAt = LocalDateTime.now()
        gameRepository.save(game)

        // Broadcast GameOver after transaction commit if in transaction context
        val gameOverEvent = DomainEvent.GameOver(context.gameId, winner)
        if (events != null) {
            // Add to events list for later broadcasting after commit
            events.add(gameOverEvent)
        } else if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCommit() {
                    stompPublisher.broadcastGame(context.gameId, gameOverEvent)
                }
            })
        } else {
            stompPublisher.broadcastGame(context.gameId, gameOverEvent)
        }
    }

    // ── Event collection helpers for transaction-safe broadcasting ─────────────

    private fun collectEliminationEvents(context: GameContext, targetId: String, events: MutableList<DomainEvent>) {
        val player = gamePlayerRepository.findByGameIdAndUserId(context.gameId, targetId)
            .orElse(null) ?: return

        // Check onEliminationPending hook: allows role handlers to veto or modify elimination
        val modifier = handlers.find { it.role == player.role }?.onEliminationPending(context, targetId)
        if (modifier?.cancelled == true) {
            modifier.extraEvents.forEach { events.add(it) }
            // After elimination: check win condition
            val updatedContext = contextLoader.load(context.gameId)
            val winner = winConditionChecker.check(
                alivePlayers = updatedContext.alivePlayers,
                mode = updatedContext.room.winCondition,
                trigger = WinCheckTrigger.POST_VOTE,
                counterplay = buildCounterplay(updatedContext),
            )
            if (winner != null) {
                endGame(updatedContext, winner, events)
            }
            return
        }

        // Idiot reveal: first elimination survives but permanently loses voting right
        if (player.role == PlayerRole.IDIOT && !player.idiotRevealed) {
            player.canVote = false
            player.idiotRevealed = true
            gamePlayerRepository.save(player)
            actionLogService.recordIdiotReveal(context.gameId, context.game.dayNumber, targetId)
            events.add(DomainEvent.IdiotRevealed(context.gameId, targetId))
            // After elimination: check win condition
            val updatedContext = contextLoader.load(context.gameId)
            val winner = winConditionChecker.check(
                alivePlayers = updatedContext.alivePlayers,
                mode = updatedContext.room.winCondition,
                trigger = WinCheckTrigger.POST_VOTE,
                counterplay = buildCounterplay(updatedContext),
            )
            if (winner != null) {
                endGame(updatedContext, winner, events)
            }
            return
        }

        player.alive = false
        gamePlayerRepository.save(player)

        // Record elimination
        val history = EliminationHistory(
            gameId = context.gameId,
            dayNumber = context.game.dayNumber,
            eliminatedUserId = targetId,
            eliminatedRole = player.role,
        )
        eliminationHistoryRepository.save(history)

        events.add(DomainEvent.PlayerEliminated(context.gameId, targetId, player.role))

        // Hunter gets to shoot
        if (player.role == PlayerRole.HUNTER) {
            context.game.subPhase = VotingSubPhase.HUNTER_SHOOT.name
            gameRepository.save(context.game)
            events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.HUNTER_SHOOT.name))
            return
        }

        // Sheriff needs to pass the badge
        if (targetId == context.game.sheriffUserId) {
            context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
            gameRepository.save(context.game)
            events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.BADGE_HANDOVER.name))
            return
        }

        // After elimination: check win condition
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
        )
        if (winner != null) {
            endGame(updatedContext, winner, events)
        }
    }

    private fun processRevoteWithEventCollection(context: GameContext, events: MutableList<DomainEvent>) {
        val existingVotes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        voteRepository.deleteAll(existingVotes)

        context.game.subPhase = VotingSubPhase.RE_VOTING.name
        gameRepository.save(context.game)
        events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.RE_VOTING.name))
    }

    private fun processGoToNightWithEventCollection(context: GameContext, events: MutableList<DomainEvent>) {
        val currentGuardTarget = context.allNightPhases
            .firstOrNull { it.dayNumber == context.game.dayNumber }
            ?.guardTargetUserId

        val newDayNumber = context.game.dayNumber + 1
        nightOrchestrator.initNight(context.gameId, newDayNumber, currentGuardTarget)
    }

    /**
     * Computes HARD_MODE counterplay flags from the just-loaded GameContext.
     * Read-your-writes on EliminationHistory is guaranteed because callers run inside
     * the same @Transactional where any hunter-shot row was just saved — JPA auto-flushes
     * before the query.
     */
    private fun buildCounterplay(context: GameContext): HardModeCounterplay {
        val alive = context.alivePlayers
        val hasGuard = alive.any { it.role == PlayerRole.GUARD }

        val hasWitchWithPotions = run {
            if (alive.none { it.role == PlayerRole.WITCH }) return@run false
            val antidoteUnused = context.allNightPhases.none { it.witchAntidoteUsed }
            val poisonUnused = context.allNightPhases.none { it.witchPoisonTargetUserId != null }
            antidoteUnused || poisonUnused
        }

        val hasHunterWithBullet = run {
            if (alive.none { it.role == PlayerRole.HUNTER }) return@run false
            val hunterHasShot = eliminationHistoryRepository
                .findByGameId(context.gameId)
                .any { it.hunterShotUserId != null }
            !hunterHasShot
        }

        return HardModeCounterplay(hasGuard, hasWitchWithPotions, hasHunterWithBullet)
    }
}
