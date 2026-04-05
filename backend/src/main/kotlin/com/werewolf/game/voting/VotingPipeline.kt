package com.werewolf.game.voting

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.VoteRepository
import com.werewolf.service.GameContextLoader
import com.werewolf.service.StompPublisher
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
) {
    @Transactional
    fun submitVote(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
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

        stompPublisher.broadcastGame(context.gameId, DomainEvent.VoteSubmitted(context.gameId, request.actorUserId))
        return GameActionResult.Success()
    }

    @Transactional
    fun unvote(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase !in setOf(VotingSubPhase.VOTING.name, VotingSubPhase.RE_VOTING.name))
            return GameActionResult.Rejected("Voting is not open")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        val myVote = votes.firstOrNull { it.voterUserId == request.actorUserId }
            ?: return GameActionResult.Rejected("No vote to retract")

        voteRepository.delete(myVote)
        stompPublisher.broadcastGame(context.gameId, DomainEvent.VoteSubmitted(context.gameId, request.actorUserId))
        return GameActionResult.Success()
    }

    @Transactional
    fun revealTally(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal tally")
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase !in setOf(VotingSubPhase.VOTING.name, VotingSubPhase.RE_VOTING.name))
            return GameActionResult.Rejected("Not in a voting sub-phase")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        val tally: Map<String, Int> = votes
            .mapNotNull { it.targetUserId }
            .groupingBy { it }
            .eachCount()

        val maxVotes = tally.values.maxOrNull() ?: 0
        val topCandidates = tally.filterValues { it == maxVotes }.keys.toList()
        val eliminated = if (topCandidates.size == 1 && maxVotes > 0) topCandidates.first() else null
        val wasRevote = context.game.subPhase == VotingSubPhase.RE_VOTING.name

        context.game.subPhase = VotingSubPhase.VOTE_RESULT.name
        gameRepository.save(context.game)

        // Prepare events to send after transaction commit
        val eventsToSend = mutableListOf<DomainEvent>()
        eventsToSend.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.VOTE_RESULT.name))
        eventsToSend.add(DomainEvent.VoteTally(context.gameId, eliminated, tally))

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
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase != VotingSubPhase.VOTE_RESULT.name)
            return GameActionResult.Rejected("Not in VOTE_RESULT sub-phase")
        goToNight(context)
        return GameActionResult.Success()
    }

    @Transactional
    fun handleHunterShoot(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase != VotingSubPhase.HUNTER_SHOOT.name)
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

                // Record in elimination history
                eliminationHistoryRepository.findByGameIdAndDayNumber(context.gameId, context.game.dayNumber)
                    .ifPresent { history ->
                        history.hunterShotUserId = target
                        history.hunterShotRole = targetPlayer.role
                        eliminationHistoryRepository.save(history)
                    }

                stompPublisher.broadcastGame(
                    context.gameId,
                    DomainEvent.HunterShot(context.gameId, actor.userId, target)
                )
                stompPublisher.broadcastGame(
                    context.gameId,
                    DomainEvent.PlayerEliminated(context.gameId, target, targetPlayer.role)
                )

                // If hunted player was the sheriff, need badge handover
                if (target == context.game.sheriffUserId) {
                    context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
                    gameRepository.save(context.game)
                    stompPublisher.broadcastGame(
                        context.gameId,
                        DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.BADGE_HANDOVER.name)
                    )
                    return GameActionResult.Success()
                }

                // No badge handover needed — check win then go to night
                afterHunterAct(context)
            }

            ActionType.HUNTER_PASS -> {
                // Hunter chooses not to shoot; check if hunter was sheriff (badge handover needed)
                if (actor.userId == context.game.sheriffUserId) {
                    context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
                    gameRepository.save(context.game)
                    stompPublisher.broadcastGame(
                        context.gameId,
                        DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.BADGE_HANDOVER.name)
                    )
                    return GameActionResult.Success()
                }
                afterHunterAct(context)
            }

            else -> return GameActionResult.Rejected("Unknown action: ${request.actionType}")
        }
        return GameActionResult.Success()
    }

    @Transactional
    fun handleBadge(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.VOTING)
            return GameActionResult.Rejected("Not in voting phase")
        if (context.game.subPhase != VotingSubPhase.BADGE_HANDOVER.name)
            return GameActionResult.Rejected("Not in BADGE_HANDOVER sub-phase")

        val actor = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Actor not found")
        if (actor.userId != context.game.sheriffUserId)
            return GameActionResult.Rejected("Only the current sheriff can hand over the badge")

        when (request.actionType) {
            ActionType.BADGE_PASS -> {
                val target = request.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                val targetPlayer = context.alivePlayerById(target)
                    ?: return GameActionResult.Rejected("Target not found or dead")

                context.game.sheriffUserId = targetPlayer.userId
                gameRepository.save(context.game)

                // Update sheriff flags
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, actor.userId).ifPresent {
                    it.sheriff = false; gamePlayerRepository.save(it)
                }
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, target).ifPresent {
                    it.sheriff = true; gamePlayerRepository.save(it)
                }
                stompPublisher.broadcastGame(
                    context.gameId,
                    DomainEvent.BadgeHandover(context.gameId, actor.userId, target)
                )
            }

            ActionType.BADGE_DESTROY -> {
                context.game.sheriffUserId = null
                gameRepository.save(context.game)
                gamePlayerRepository.findByGameIdAndUserId(context.gameId, actor.userId).ifPresent {
                    it.sheriff = false; gamePlayerRepository.save(it)
                }
                stompPublisher.broadcastGame(
                    context.gameId,
                    DomainEvent.BadgeHandover(context.gameId, actor.userId, null)
                )
            }

            else -> return GameActionResult.Rejected("Unknown action: ${request.actionType}")
        }

        afterElimination(context)
        return GameActionResult.Success()
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun eliminateByVote(context: GameContext, targetId: String) {
        val player = gamePlayerRepository.findByGameIdAndUserId(context.gameId, targetId)
            .orElse(null) ?: return

        // Check onEliminationPending hook: allows role handlers to veto or modify elimination
        val modifier = handlers.find { it.role == player.role }?.onEliminationPending(context, targetId)
        if (modifier?.cancelled == true) {
            modifier.extraEvents.forEach { stompPublisher.broadcastGame(context.gameId, it) }
            afterElimination(context)
            return
        }

        // Idiot reveal: first elimination survives but permanently loses voting right
        if (player.role == PlayerRole.IDIOT && !player.idiotRevealed) {
            player.canVote = false
            player.idiotRevealed = true
            gamePlayerRepository.save(player)
            stompPublisher.broadcastGame(
                context.gameId,
                DomainEvent.IdiotRevealed(context.gameId, targetId)
            )
            // No elimination — day phase ends normally without HUNTER or BADGE_HANDOVER
            afterElimination(context)
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

        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PlayerEliminated(context.gameId, targetId, player.role)
        )

        // Hunter gets to shoot
        if (player.role == PlayerRole.HUNTER) {
            context.game.subPhase = VotingSubPhase.HUNTER_SHOOT.name
            gameRepository.save(context.game)
            stompPublisher.broadcastGame(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.HUNTER_SHOOT.name)
            )
            return
        }

        // Sheriff needs to pass the badge
        if (targetId == context.game.sheriffUserId) {
            context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
            gameRepository.save(context.game)
            stompPublisher.broadcastGame(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.BADGE_HANDOVER.name)
            )
            return
        }

        afterElimination(context)
    }

    /** Check win condition; if no winner, stay in VOTE_RESULT for host to proceed to night. */
    private fun afterElimination(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)
        if (winner != null) {
            endGame(updatedContext, winner)
        }
        // else: sub-phase stays at VOTE_RESULT — host calls VOTING_CONTINUE to proceed to night
    }

    /** After hunter acts (no badge needed): check win, then go directly to night. */
    private fun afterHunterAct(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)
        if (winner != null) {
            endGame(updatedContext, winner)
        } else {
            goToNight(updatedContext)
        }
    }

    /** Clear first-round votes and open a second vote round (open to all living candidates). */
    private fun initiateRevote(context: GameContext) {
        val existingVotes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        voteRepository.deleteAll(existingVotes)

        context.game.subPhase = VotingSubPhase.RE_VOTING.name
        gameRepository.save(context.game)
        stompPublisher.broadcastGame(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.RE_VOTING.name)
        )
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
            val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)
            if (winner != null) {
                events.add(DomainEvent.GameOver(context.gameId, winner))
            }
            return
        }

        // Idiot reveal: first elimination survives but permanently loses voting right
        if (player.role == PlayerRole.IDIOT && !player.idiotRevealed) {
            player.canVote = false
            player.idiotRevealed = true
            gamePlayerRepository.save(player)
            events.add(DomainEvent.IdiotRevealed(context.gameId, targetId))
            // After elimination: check win condition
            val updatedContext = contextLoader.load(context.gameId)
            val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)
            if (winner != null) {
                events.add(DomainEvent.GameOver(context.gameId, winner))
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
            events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.HUNTER_SHOOT.name))
            return
        }

        // Sheriff needs to pass the badge
        if (targetId == context.game.sheriffUserId) {
            context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
            gameRepository.save(context.game)
            events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.BADGE_HANDOVER.name))
            return
        }

        // After elimination: check win condition
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(updatedContext.alivePlayers, updatedContext.room.winCondition)
        if (winner != null) {
            events.add(DomainEvent.GameOver(context.gameId, winner))
        }
    }

    private fun processRevoteWithEventCollection(context: GameContext, events: MutableList<DomainEvent>) {
        val existingVotes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.ELIMINATION, context.game.dayNumber
        )
        voteRepository.deleteAll(existingVotes)

        context.game.subPhase = VotingSubPhase.RE_VOTING.name
        gameRepository.save(context.game)
        events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.VOTING, VotingSubPhase.RE_VOTING.name))
    }

    private fun processGoToNightWithEventCollection(context: GameContext, events: MutableList<DomainEvent>) {
        val currentGuardTarget = context.allNightPhases
            .firstOrNull { it.dayNumber == context.game.dayNumber }
            ?.guardTargetUserId

        val newDayNumber = context.game.dayNumber + 1
        // nightOrchestrator.initNight handles its own transaction and event broadcasting
        // We don't need to collect its events here
        nightOrchestrator.initNight(context.gameId, newDayNumber, currentGuardTarget)
    }
}
