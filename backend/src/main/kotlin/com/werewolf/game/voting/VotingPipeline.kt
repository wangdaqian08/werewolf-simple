package com.werewolf.game.voting

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.phase.DayRevealAdvancer
import com.werewolf.game.phase.HardModeCounterplay
import com.werewolf.game.phase.WinCheckTrigger
import com.werewolf.game.phase.WinConditionChecker
import com.werewolf.game.role.RoleHandler
import com.werewolf.game.timer.HostTimerService
import com.werewolf.model.*
import com.werewolf.repository.EliminationHistoryRepository
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.VoteRepository
import com.werewolf.service.ActionLogService
import com.werewolf.service.GameContextLoader
import com.werewolf.service.RewardSettlementService
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
    private val hostTimerService: HostTimerService,
    private val dayRevealAdvancer: DayRevealAdvancer,
    private val rewardSettlementService: RewardSettlementService,
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

        // PK re-vote: the tied players stand on the PK stage and do not vote.
        val pk = if (context.game.subPhase == VotingSubPhase.RE_VOTING.name) context.game.pkCandidates() else emptySet()
        if (request.actorUserId in pk) return GameActionResult.Rejected("PK candidates cannot vote")
        if (pk.isNotEmpty() && target != null && target !in pk)
            return GameActionResult.Rejected("Vote for one of the PK candidates")

        voteRepository.save(
            Vote(
                gameId = context.gameId,
                voteContext = VoteContext.ELIMINATION,
                dayNumber = context.game.dayNumber,
                voterUserId = request.actorUserId,
                targetUserId = target,
                sheriffVote = request.actorUserId == context.game.sheriffUserId,
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
        val tally: Map<String, Double> = TallyCalculator.calculateWeightedTally(votes)

        val eliminated = TallyCalculator.findTopCandidate(tally)
        val wasRevote = context.game.subPhase == VotingSubPhase.RE_VOTING.name

        context.game.subPhase = VotingSubPhase.VOTE_RESULT.name
        gameRepository.save(context.game)

        // Prepare events to send after transaction commit
        val eventsToSend = mutableListOf<DomainEvent>()
        eventsToSend.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.VOTE_RESULT.name))
        eventsToSend.add(DomainEvent.VoteTally(context.gameId, eliminated, tally))

        // Record vote result in action log
        actionLogService.recordVoteResult(
            context.gameId, context.game.dayNumber, votes, tally,
            context.game.sheriffUserId, eliminated,
        )

        // Collect events from elimination process
        if (eliminated != null) {
            collectEliminationEvents(context, eliminated, eventsToSend)
        } else if (!wasRevote) {
            // First-round tie — PK re-vote between the tied players. An all-abstain
            // round has no tied players, so its re-vote stays open to everyone.
            context.game.pkCandidateUserIds = TallyCalculator.topCandidates(tally).takeIf { it.isNotEmpty() }?.joinToString(",")
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

        // Normal path: DAY_VOTING/VOTE_RESULT
        if (context.game.phase == GamePhase.DAY_VOTING) {
            if (context.game.subPhase != VotingSubPhase.VOTE_RESULT.name)
                return GameActionResult.Rejected("Not in VOTE_RESULT sub-phase")
            goToNight(context)
            return GameActionResult.Success()
        }

        // Skip-voting path: DAY_DISCUSSION when daySkipVoting=true (wolf self-destructed)
        if (context.game.phase == GamePhase.DAY_DISCUSSION) {
            if (!context.game.daySkipVoting)
                return GameActionResult.Rejected("Voting was not skipped — cannot advance to night from DAY_DISCUSSION")
            // The night result must be revealed first: the reveal applies last night's kills.
            if (context.game.subPhase != DaySubPhase.RESULT_REVEALED.name)
                return GameActionResult.Rejected("Reveal the night result before going to night")
            hostTimerService.cancel(context.gameId)
            goToNight(context)
            return GameActionResult.Success()
        }

        return GameActionResult.Rejected("Not in voting phase")
    }

    /**
     * The private on-death shot shared by every [PlayerRole.shootsOnDeath] role
     * (HUNTER and BLACK_WOLF_KING). Only [Game.pendingShooterUserId] may act, while
     * the day sits on its normal post-death sub-phase: nothing public marks a
     * pending shot, and a pass broadcasts nothing. A shooter killed by a shot is
     * the next pending shooter (a chain); a room has at most one of each shooter,
     * so a chain is at most two shots.
     */
    @Transactional
    fun handleHunterShoot(request: GameActionRequest, context: GameContext): GameActionResult {
        // Two windows share this handler (mirrors handleBadge):
        //   (A) Voting elimination: DAY_VOTING after the tally (VOTE_RESULT / BADGE_HANDOVER).
        //   (B) Night-death reveal:  DAY_DISCUSSION after the reveal (RESULT_REVEALED / BADGE_HANDOVER).
        // The host moving on (vote start / night start) clears the pending shooter.
        val game = context.game
        if (!isShotWindow(game.phase, game.subPhase) || request.actorUserId != game.pendingShooterUserId)
            return GameActionResult.Rejected("You have no shot to take")
        val fromVoting = game.phase == GamePhase.DAY_VOTING

        when (request.actionType) {
            ActionType.HUNTER_PASS -> {
                game.pendingShooterUserId = null
                gameRepository.save(game)
            }

            ActionType.HUNTER_SHOOT -> {
                val target = request.targetUserId
                    ?: return GameActionResult.Rejected("Target required")
                val targetPlayer = gamePlayerRepository.findByGameIdAndUserId(context.gameId, target)
                    .orElse(null) ?: return GameActionResult.Rejected("Target not found")
                if (!targetPlayer.alive) return GameActionResult.Rejected("Target is already dead")

                actionLogService.recordHunterShot(context.gameId, game.dayNumber, request.actorUserId, target)
                stompPublisher.broadcastGameAfterCommit(
                    context.gameId,
                    DomainEvent.HunterShot(context.gameId, request.actorUserId, target)
                )
                if (fromVoting) votingShot(context, target, targetPlayer) else nightDeathShot(context, target, targetPlayer)
            }

            else -> return GameActionResult.Rejected("Unknown action: ${request.actionType}")
        }
        return GameActionResult.Success()
    }

    /** A shot fired after a vote-out (or in its chain). */
    private fun votingShot(context: GameContext, target: String, targetPlayer: GamePlayer) {
        // Record in elimination history (a chained shot overwrites the first:
        // the action log keeps every shot). `hunterShotUserId` doubles as
        // the deferred-kill signal for BADGE_PASS / BADGE_DESTROY when
        // the target is the sheriff (see below) — only a chain's last shot
        // can hit a sheriff who does not shoot back.
        eliminationHistoryRepository.findByGameIdAndDayNumber(context.gameId, context.game.dayNumber).ifPresent {
            it.hunterShotUserId = target
            it.hunterShotRole = targetPlayer.role
            eliminationHistoryRepository.save(it)
        }

        // A shot shooter fires next. It dies at once, even as sheriff.
        val nextShooter = target.takeIf { targetPlayer.role.shootsOnDeath }

        // Sheriff target: defer the kill (国标 rule — the dying sheriff
        // performs the badge handover BEFORE being marked dead).
        // handleBadge.BADGE_PASS / BADGE_DESTROY commits the kill once
        // the heir is chosen.
        // A shot that ends the game kills at once: no handover for a lost cause.
        if (nextShooter == null && target == context.game.sheriffUserId && projectedWinner(context, target) == null) {
            context.game.pendingShooterUserId = null
            context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
            gameRepository.save(context.game)
            stompPublisher.broadcastGameAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.BADGE_HANDOVER.name)
            )
            return
        }

        // Otherwise (non-sheriff, shooter or game-ending target): kill immediately.
        targetPlayer.alive = false
        targetPlayer.diedDay = context.game.dayNumber
        gamePlayerRepository.save(targetPlayer)
        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.PlayerEliminated(context.gameId, target))
        afterHunterAct(context, nextShooter)
    }

    @Transactional
    fun handleBadge(request: GameActionRequest, context: GameContext): GameActionResult {
        // Two entry points share this handler:
        //   (A) Voting elimination: DAY_VOTING + VotingSubPhase.BADGE_HANDOVER
        //       — sheriff voted out → after handover, continue elimination via
        //         afterElimination() and land on VotingSubPhase.VOTE_RESULT.
        //   (B) Night kill reveal:  DAY_DISCUSSION + DaySubPhase.BADGE_HANDOVER
        //       — sheriff killed at night → after handover, land back on
        //         DaySubPhase.RESULT_REVEALED so host can advance to voting.
        val fromVoting = context.game.phase == GamePhase.DAY_VOTING &&
                context.game.subPhase == VotingSubPhase.BADGE_HANDOVER.name
        val fromNightReveal = context.game.phase == GamePhase.DAY_DISCUSSION &&
                context.game.subPhase == DaySubPhase.BADGE_HANDOVER.name
        if (!fromVoting && !fromNightReveal)
            return GameActionResult.Rejected("Not in BADGE_HANDOVER sub-phase")

        val postPhase = if (fromVoting) GamePhase.DAY_VOTING else GamePhase.DAY_DISCUSSION
        // Where the day lands after this handover. Voting elimination always
        // returns to VOTE_RESULT; the night-reveal path defers to the advancer.
        // Resolved only after the sheriff reassign/destroy is persisted.
        fun resolvePostSubPhase(): String =
            if (fromVoting) VotingSubPhase.VOTE_RESULT.name
            else dayRevealAdvancer.nextSubPhase(context.gameId).name

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
                // A sheriff shot by the hunter is still alive until the handover ends.
                if (target == actor.userId)
                    return GameActionResult.Rejected("Cannot pass the badge to yourself")

                // Reassign the badge, then resolve where the day goes next. The
                // advancer reads the same managed Game instance, so it sees the
                // new sheriffUserId without an intermediate save.
                context.game.sheriffUserId = targetPlayer.userId
                val postSubPhase = resolvePostSubPhase()
                context.game.subPhase = postSubPhase
                gameRepository.save(context.game)
                actionLogService.recordBadgeHandover(context.gameId, context.game.dayNumber, actor.userId, target)

                if (fromVoting) commitDeferredHunterShotIfAny(context, actor.userId)

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
                // Destroy the badge, then resolve where the day goes next.
                context.game.sheriffUserId = null
                val postSubPhase = resolvePostSubPhase()
                context.game.subPhase = postSubPhase
                gameRepository.save(context.game)
                actionLogService.recordBadgeHandover(context.gameId, context.game.dayNumber, actor.userId, null)
                if (fromVoting) commitDeferredHunterShotIfAny(context, actor.userId)
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

        if (fromVoting) afterElimination(context)
        return GameActionResult.Success()
    }

    /**
     * Commit the deferred hunter-shot kill on the dying sheriff, if there is
     * one. We discriminate by elimHistory.hunterShotUserId == actor — that's
     * the signal HUNTER_SHOOT left behind when it deferred the kill. Other
     * paths into BADGE_HANDOVER (revealed-idiot sheriff, voted-out sheriff)
     * either have the actor already dead (vote-out) or must stay alive
     * (revealed idiot), and this guard leaves them untouched.
     */
    private fun commitDeferredHunterShotIfAny(context: GameContext, actorUserId: String) {
        val hunterShotUserId = eliminationHistoryRepository
            .findByGameIdAndDayNumber(context.gameId, context.game.dayNumber)
            .orElse(null)
            ?.hunterShotUserId
        if (hunterShotUserId != actorUserId) return
        gamePlayerRepository.findByGameIdAndUserId(context.gameId, actorUserId).ifPresent { p ->
            if (!p.alive) return@ifPresent // already dead — nothing to commit
            p.alive = false
            p.diedDay = context.game.dayNumber
            gamePlayerRepository.save(p)
            stompPublisher.broadcastGameAfterCommit(
                context.gameId,
                DomainEvent.PlayerEliminated(context.gameId, actorUserId),
            )
        }
    }

    /** Check win condition; if no winner, stay in VOTE_RESULT for host to proceed to night. */
    private fun afterElimination(context: GameContext) {
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
            roster = updatedContext.players,
        )
        if (winner != null) {
            endGame(updatedContext, winner)
        }
        // else: sub-phase stays at VOTE_RESULT — host calls VOTING_CONTINUE to proceed to night
    }

    /**
     * After a vote-out shot (no deferred badge): check win; otherwise open the
     * private shot of [nextShooter] (a chained shot), hand over a dead sheriff's
     * badge, else PAUSE on VOTE_RESULT for the host to advance — never auto-jump
     * to night.
     *
     * The hunter's victim (and the dying hunter) need a last-words window, just
     * like every other voting-elimination day-death: normal exile and the
     * sheriff badge-handover both land on VOTE_RESULT, where the host clicks
     * 进入夜晚 (VOTING_CONTINUE → continueToNight).
     */
    private fun afterHunterAct(context: GameContext, nextShooter: String?) {
        context.game.pendingShooterUserId = null
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
            roster = updatedContext.players,
        )
        if (winner != null) {
            endGame(updatedContext, winner)
            return
        }
        context.game.pendingShooterUserId = nextShooter
        // A dead sheriff (the voted-out shooter, or a chained shooter) hands the badge over.
        val sheriff = context.game.sheriffUserId
        val sheriffDead = sheriff != null && updatedContext.players.find { it.userId == sheriff }?.alive == false
        val next = if (sheriffDead) VotingSubPhase.BADGE_HANDOVER else VotingSubPhase.VOTE_RESULT
        context.game.subPhase = next.name
        gameRepository.save(context.game)
        stompPublisher.broadcastGameAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, next.name),
        )
    }

    /**
     * A shot fired during the day reveal by a wolf-killed shooter (or in its
     * chain). The shooter is already dead (applyNightKills flipped them at
     * reveal), so — unlike the voting path — there is no 国标 ordering to
     * preserve: the shot kills the target immediately, then the advancer routes
     * to BADGE_HANDOVER (when the target was the sheriff) or RESULT_REVEALED.
     */
    private fun nightDeathShot(context: GameContext, target: String, targetPlayer: GamePlayer) {
        targetPlayer.alive = false
        targetPlayer.diedDay = context.game.dayNumber
        gamePlayerRepository.save(targetPlayer)
        stompPublisher.broadcastGameAfterCommit(context.gameId, DomainEvent.PlayerEliminated(context.gameId, target))

        context.game.pendingShooterUserId = null
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            // Morning, before any vote: HARD_MODE's logical win waits for the vote.
            trigger = WinCheckTrigger.POST_NIGHT,
            counterplay = buildCounterplay(updatedContext),
            roster = updatedContext.players,
        )
        if (winner != null) {
            endGame(updatedContext, winner)
            return
        }
        context.game.pendingShooterUserId = target.takeIf { targetPlayer.role.shootsOnDeath }
        val nextSubPhase = dayRevealAdvancer.nextSubPhase(context.gameId)
        context.game.subPhase = nextSubPhase.name
        gameRepository.save(context.game)
        stompPublisher.broadcastGameAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, nextSubPhase.name)
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
        rewardSettlementService.settle(context.gameId, winner)

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
                roster = updatedContext.players,
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
                roster = updatedContext.players,
            )
            if (winner != null) {
                endGame(updatedContext, winner, events)
            }
            return
        }

        player.alive = false
        player.diedDay = context.game.dayNumber
        gamePlayerRepository.save(player)

        // Record elimination
        val history = EliminationHistory(
            gameId = context.gameId,
            dayNumber = context.game.dayNumber,
            eliminatedUserId = targetId,
            eliminatedRole = player.role,
        )
        eliminationHistoryRepository.save(history)

        events.add(DomainEvent.PlayerEliminated(context.gameId, targetId))

        // The game ends the moment a side wins: no hunter shot, no badge handover.
        val updatedContext = contextLoader.load(context.gameId)
        val winner = winConditionChecker.check(
            alivePlayers = updatedContext.alivePlayers,
            mode = updatedContext.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(updatedContext),
            roster = updatedContext.players,
        )
        if (winner != null) {
            endGame(updatedContext, winner, events)
            return
        }

        // A shooter (hunter / black wolf king) gets a private shot: the day
        // looks the same as any other vote-out.
        if (player.role.shootsOnDeath) {
            context.game.pendingShooterUserId = targetId
            gameRepository.save(context.game)
        }

        // Sheriff needs to pass the badge
        if (targetId == context.game.sheriffUserId) {
            context.game.subPhase = VotingSubPhase.BADGE_HANDOVER.name
            gameRepository.save(context.game)
            events.add(DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_VOTING, VotingSubPhase.BADGE_HANDOVER.name))
            return
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

    /** The winner if [deadUserId] died now, or null. */
    private fun projectedWinner(context: GameContext, deadUserId: String): WinnerSide? {
        val alive = context.alivePlayers.filterNot { it.userId == deadUserId }
        return winConditionChecker.check(
            alivePlayers = alive,
            mode = context.room.winCondition,
            trigger = WinCheckTrigger.POST_VOTE,
            counterplay = buildCounterplay(context, alive),
            roster = context.players,
        )
    }

    private fun buildCounterplay(context: GameContext, alive: List<GamePlayer> = context.alivePlayers): HardModeCounterplay =
        HardModeCounterplay.of(alive, context.allNightPhases)

    companion object {
        /** Where a pending shot may be taken: after the tally, or after the night reveal. */
        fun isShotWindow(phase: GamePhase, subPhase: String?): Boolean = when (phase) {
            GamePhase.DAY_VOTING -> subPhase == VotingSubPhase.VOTE_RESULT.name || subPhase == VotingSubPhase.BADGE_HANDOVER.name
            GamePhase.DAY_DISCUSSION -> subPhase == DaySubPhase.RESULT_REVEALED.name || subPhase == DaySubPhase.BADGE_HANDOVER.name
            else -> false
        }
    }
}
