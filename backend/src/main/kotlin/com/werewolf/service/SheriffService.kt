package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
import com.werewolf.config.GameTimingProperties
import com.werewolf.game.timer.HostTimerService
import com.werewolf.model.*
import com.werewolf.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class SheriffService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
    private val sheriffCandidateRepository: SheriffCandidateRepository,
    private val voteRepository: VoteRepository,
    private val userRepository: UserRepository,
    private val stompPublisher: StompPublisher,
    private val coroutineScope: CoroutineScope,
    private val timingProperties: GameTimingProperties,
    private val actionLogService: ActionLogService,
    private val hostTimerService: HostTimerService,
) {
    val log = LoggerFactory.getLogger(SheriffService::class.java)
    private val scheduledJobs = mutableMapOf<Int, Job>()
    // ── Action dispatcher ──────────────────────────────────────────────────────

    @Transactional
    fun handle(request: GameActionRequest, context: GameContext): GameActionResult = when (request.actionType) {
        ActionType.SHERIFF_CAMPAIGN -> signUp(request, context)
        ActionType.SHERIFF_QUIT -> quit(request, context)
        ActionType.SHERIFF_START_SPEECH -> startSpeech(request, context)
        ActionType.SHERIFF_ADVANCE_SPEECH -> advanceSpeech(request, context)
        ActionType.SHERIFF_REVEAL_RESULT -> revealResult(request, context)
        ActionType.SHERIFF_APPOINT -> appoint(request, context)
        ActionType.SHERIFF_PASS -> pass(request, context)
        ActionType.SHERIFF_CONFIRM_VOTE -> GameActionResult.Success() // vote already saved on SHERIFF_VOTE
        ActionType.SHERIFF_QUIT_CAMPAIGN -> quitCampaign(request, context)
        ActionType.SHERIFF_VOTE -> vote(request, context)
        ActionType.SHERIFF_ABSTAIN -> abstain(request, context)
        ActionType.SHERIFF_END_RESULT -> endResult(request, context)
        else -> GameActionResult.Rejected("Unknown sheriff action: ${request.actionType}")
    }

    // ── State builder (used by GameService.getGameState) ──────────────────────

    fun buildState(gameId: Int, game: Game, myPlayer: GamePlayer?, players: List<GamePlayer>): Map<String, Any?> {
        val election = sheriffElectionRepository.findByGameId(gameId).orElse(null) ?: return emptyMap()
        val electionId = election.id ?: return emptyMap()
        val candidates = sheriffCandidateRepository.findByElectionId(electionId)
        val userMap = userRepository.findAllById(candidates.map { it.userId }).associateBy { it.userId }
        val playerMap = players.associateBy { it.userId }

        val speakingOrderIds = election.speakingOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val currentSpeakerId = speakingOrderIds.getOrNull(election.currentSpeakerIdx)

        val myVoteRecord = myPlayer?.let {
            voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
                gameId, VoteContext.SHERIFF_ELECTION, game.dayNumber, it.userId
            ).orElse(null)
        }
        val myCandidate = candidates.firstOrNull { it.userId == myPlayer?.userId }

        val result = if (election.subPhase == ElectionSubPhase.RESULT || election.subPhase == ElectionSubPhase.TIED) {
            buildResult(gameId, game, candidates, userMap, playerMap)
        } else null

        // allVoted: every eligible player (alive, not a SPEECH-quitter) has cast a vote
        // Speech-quitters forfeited their vote and are excluded from eligibleVoterCount.
        val alivePlayerIds = players.filter { it.alive }.map { it.userId }.toSet()
        val ineligibleIds = candidates.filter { c ->
            c.status == CandidateStatus.RUNNING ||
            (c.status == CandidateStatus.QUIT && speakingOrderIds.contains(c.userId))
        }.map { it.userId }.toSet().intersect(alivePlayerIds)
        val totalAlivePlayers = alivePlayerIds.size
        val eligibleVoterCount = totalAlivePlayers - ineligibleIds.size
        val submittedVoteCount = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, game.dayNumber
        ).size
        val autoCountedVoters = ineligibleIds.size
        // allVoted is true when all eligible voters have submitted a vote (including voluntary abstains).
        // If eligibleVoterCount == 0 (all alive players are ineligible), voting is trivially complete.
        val allVoted = eligibleVoterCount == 0 || submittedVoteCount >= eligibleVoterCount

        // During SIGNUP, hide WHO joined the campaign — players must decide
        // without that information influencing their pick. The state still
        // exposes the requesting player's own candidacy row so the UI can show
        // Withdraw / Run-for-Sheriff correctly; everybody else sees only the
        // candidateCount + decisionProgress aggregates below.
        val candidatesOut: List<Map<String, Any?>> = if (election.subPhase == ElectionSubPhase.SIGNUP) {
            candidates.filter { it.userId == myPlayer?.userId }.map { c ->
                val user = userMap[c.userId]
                mapOf(
                    "userId" to c.userId,
                    "nickname" to (user?.nickname ?: c.userId),
                    "avatar" to user?.avatarUrl,
                    "status" to c.status.name,
                )
            }
        } else {
            candidates.map { c ->
                val user = userMap[c.userId]
                mapOf(
                    "userId" to c.userId,
                    "nickname" to (user?.nickname ?: c.userId),
                    "avatar" to user?.avatarUrl,
                    "status" to c.status.name,
                )
            }
        }

        val decisionProgress: Map<String, Int>? = if (election.subPhase == ElectionSubPhase.SIGNUP) {
            val aliveUserIds = players.filter { it.alive }.map { it.userId }.toSet()
            val decidedCount = candidates.count { it.userId in aliveUserIds }
            mapOf("decided" to decidedCount, "total" to aliveUserIds.size)
        } else null

        return mapOf(
            "subPhase" to election.subPhase.name,
            "timeRemaining" to 0,
            "candidates" to candidatesOut,
            "decisionProgress" to decisionProgress,
            "speakingOrder" to speakingOrderIds,
            "currentSpeakerId" to currentSpeakerId,
            // hasPassed: player explicitly chose not to run (QUIT but was never in the speaking order)
            "hasPassed" to (myCandidate?.status == CandidateStatus.QUIT && !speakingOrderIds.contains(myPlayer?.userId)),
            "myVote" to myVoteRecord?.targetUserId,
            "abstained" to (myVoteRecord != null && myVoteRecord.targetUserId == null),
            // canVote: RUNNING candidates and speech-quitters (QUIT in speaking order) cannot vote
            "canVote" to !(
                myCandidate?.status == CandidateStatus.RUNNING ||
                (myCandidate?.status == CandidateStatus.QUIT && speakingOrderIds.contains(myPlayer?.userId))
            ),
            "allVoted" to allVoted,
            // voteProgress: ineligible voters (RUNNING candidates + speech-quitters) count as auto-voted
            "voteProgress" to mapOf("voted" to submittedVoteCount + autoCountedVoters, "total" to totalAlivePlayers),
            "result" to result,
        )
    }

    // ── Private actions ────────────────────────────────────────────────────────

    private fun signUp(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val player = context.playerById(request.actorUserId)
            ?: return GameActionResult.Rejected("Player not found")
        if (!player.alive) return GameActionResult.Rejected("Dead players cannot run for sheriff")

        val electionId = election.id ?: error("Election has no ID")
        val priorCandidates = sheriffCandidateRepository.findByElectionId(electionId)
        val existing = priorCandidates.firstOrNull { it.userId == request.actorUserId }
        val updatedCandidates: List<SheriffCandidate> = if (existing != null) {
            existing.status = CandidateStatus.RUNNING
            sheriffCandidateRepository.save(existing)
            priorCandidates
        } else {
            val fresh = SheriffCandidate(electionId = electionId, userId = request.actorUserId)
            sheriffCandidateRepository.save(fresh)
            priorCandidates + fresh
        }
        return finishSignupDecision(election, updatedCandidates, context)
    }

    private fun pass(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val electionId = election.id ?: error("Election has no ID")
        val priorCandidates = sheriffCandidateRepository.findByElectionId(electionId)
        val existing = priorCandidates.firstOrNull { it.userId == request.actorUserId }
        val updatedCandidates: List<SheriffCandidate> = if (existing == null) {
            val fresh = SheriffCandidate(
                electionId = electionId, userId = request.actorUserId, status = CandidateStatus.QUIT
            )
            sheriffCandidateRepository.save(fresh)
            priorCandidates + fresh
        } else {
            if (existing.status == CandidateStatus.RUNNING) {
                existing.status = CandidateStatus.QUIT
                sheriffCandidateRepository.save(existing)
            }
            priorCandidates
        }
        return finishSignupDecision(election, updatedCandidates, context)
    }

    /**
     * After a signUp or pass updates the candidate list, decide whether the
     * SIGNUP sub-phase is complete. The campaign auto-advances to SPEECH once
     * every alive player has either signed up or passed. If everyone passed,
     * skip straight to DAY_DISCUSSION/RESULT_HIDDEN (same dead-end avoidance
     * as startSpeech's empty-candidates branch).
     *
     * Behavioural change (2026-05-11): replaces the host's manual 开始演讲
     * button as the SIGNUP→SPEECH trigger. Players must not see who joined
     * the campaign — only how many — so the host has nothing to base an
     * early-start decision on.
     */
    private fun finishSignupDecision(
        election: SheriffElection,
        updatedCandidates: List<SheriffCandidate>,
        context: GameContext,
    ): GameActionResult {
        val aliveUserIds = context.players.filter { it.alive }.map { it.userId }.toSet()
        val decidedUserIds = updatedCandidates.map { it.userId }.toSet()
        val allDecided = aliveUserIds.all { it in decidedUserIds }

        if (!allDecided) {
            broadcastSignupUpdate(context.gameId)
            return GameActionResult.Success()
        }

        val running = updatedCandidates.filter { it.status == CandidateStatus.RUNNING }
        if (running.isEmpty()) {
            // Nobody ran — same fall-through that startSpeech() uses to avoid
            // a RESULT screen with no winner and no votes.
            context.game.phase = GamePhase.DAY_DISCUSSION
            context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            gameRepository.save(context.game)
            broadcastAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name),
            )
            return GameActionResult.Success()
        }

        election.subPhase = ElectionSubPhase.SPEECH
        election.speakingOrder = running.map { it.userId }.shuffled().joinToString(",")
        election.currentSpeakerIdx = 0
        sheriffElectionRepository.save(election)
        broadcastAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name),
        )
        return GameActionResult.Success()
    }

    private fun quit(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val candidate = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
            .firstOrNull { it.userId == request.actorUserId }
            ?: return GameActionResult.Rejected("Not a candidate")

        candidate.status = CandidateStatus.QUIT
        sheriffCandidateRepository.save(candidate)
        broadcastSignupUpdate(context.gameId)
        return GameActionResult.Success()
    }

    private fun startSpeech(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can start speeches")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Not in SIGNUP sub-phase")

        val candidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
            .filter { it.status == CandidateStatus.RUNNING }

        if (candidates.isEmpty()) {
            // No one ran — sheriff election is over before it starts. There's
            // nothing for the host to dismiss on a RESULT screen, so transition
            // straight to DAY_DISCUSSION/RESULT_HIDDEN. Inline the same write
            // that endResult() performs.
            context.game.phase = GamePhase.DAY_DISCUSSION
            context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            gameRepository.save(context.game)
            broadcastAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name),
            )
            return GameActionResult.Success()
        }

        election.subPhase = ElectionSubPhase.SPEECH
        election.speakingOrder = candidates.map { it.userId }.shuffled().joinToString(",")
        election.currentSpeakerIdx = 0
        sheriffElectionRepository.save(election)
        broadcastAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name)
        )
        return GameActionResult.Success()
    }

    private fun advanceSpeech(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can advance speeches")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SPEECH)
            return GameActionResult.Rejected("Not in SPEECH sub-phase")

        // Cancel any running timer for the departing candidate before advancing
        hostTimerService.cancel(context.gameId)

        val order = election.speakingOrder?.split(",") ?: emptyList()
        val candidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
        val candidateStatusMap = candidates.associateBy { it.userId }.mapValues { it.value.status }

        // Find the next running candidate, skipping those who have quit
        var nextIdx = election.currentSpeakerIdx + 1
        while (nextIdx < order.size) {
            val nextUserId = order[nextIdx]
            if (candidateStatusMap[nextUserId] == CandidateStatus.RUNNING) {
                break
            }
            nextIdx++
        }

        if (nextIdx >= order.size) {
            // All candidates have spoken (or quit) - move to voting
            election.subPhase = ElectionSubPhase.VOTING
            sheriffElectionRepository.save(election)
            broadcastAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.VOTING.name)
            )
        } else {
            election.currentSpeakerIdx = nextIdx
            sheriffElectionRepository.save(election)
            broadcastAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name)
            )
        }
        return GameActionResult.Success()
    }

    private fun revealResult(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can reveal result")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.VOTING)
            return GameActionResult.Rejected("Not in VOTING sub-phase")

        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.SHERIFF_ELECTION, context.game.dayNumber
        )
        val tally = votes.mapNotNull { it.targetUserId }.groupingBy { it }.eachCount()
        val maxVotes = tally.values.maxOrNull() ?: 0
        val topCandidates = tally.filterValues { it == maxVotes }.keys.toList()
        if (topCandidates.isEmpty()) {
            // No effective votes (all abstained or no votes cast)
            val runningCandidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
                .filter { it.status == CandidateStatus.RUNNING }
            if (runningCandidates.isNotEmpty()) {
                val aliveIds = context.players.filter { it.alive }.map { it.userId }.toSet()
                val runningIds = runningCandidates.map { it.userId }.toSet()
                if (runningIds == aliveIds) {
                    // Every alive player ran → nobody was eligible to vote → no sheriff.
                    // Mirror the dead-end avoidance already used by finishSignupDecision/quitCampaign.
                    context.game.phase = GamePhase.DAY_DISCUSSION
                    context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
                    gameRepository.save(context.game)
                    broadcastAfterCommit(context.gameId,
                        DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name))
                } else {
                    // Running candidates exist but got zero votes → TIED → host appoints
                    election.subPhase = ElectionSubPhase.TIED
                    sheriffElectionRepository.save(election)
                    broadcastAfterCommit(context.gameId,
                        DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.TIED.name))
                }
            } else {
                // No running candidates at all → RESULT with auto-advance to night
                election.subPhase = ElectionSubPhase.RESULT
                sheriffElectionRepository.save(election)
                broadcastAfterCommit(context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
                // Sheriff is shown on the RESULT screen until the host clicks
                // 显示结果 (SHERIFF_END_RESULT) — no auto-timer.
            }
            return GameActionResult.Success()
        }
        if (topCandidates.size == 1) {
            val winnerUserId = topCandidates.first()
            election.subPhase = ElectionSubPhase.RESULT
            election.electedSheriffUserId = winnerUserId
            sheriffElectionRepository.save(election)
            electSheriff(winnerUserId, context)
            actionLogService.recordSheriffResult(
                context.gameId, context.game.dayNumber, winnerUserId, votes, tally,
            )
            broadcastAfterCommit(context.gameId, DomainEvent.SheriffElected(context.gameId, winnerUserId))
            broadcastAfterCommit(context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
            // Schedule auto-advance to day discussion (60s)
            // Sheriff is shown on the RESULT screen until the host clicks
            // 显示结果 (SHERIFF_END_RESULT) — no auto-timer.
        } else {
            election.subPhase = ElectionSubPhase.TIED
            sheriffElectionRepository.save(election)
            broadcastAfterCommit(context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.TIED.name))
        }
        return GameActionResult.Success()
    }

    private fun appoint(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can appoint sheriff")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.TIED)
            return GameActionResult.Rejected("Not in TIED sub-phase")

        val targetUserId = request.targetUserId
            ?: return GameActionResult.Rejected("targetUserId required")
        val candidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
        if (candidates.none { it.userId == targetUserId && it.status == CandidateStatus.RUNNING })
            return GameActionResult.Rejected("Target is not a running candidate")

        election.subPhase = ElectionSubPhase.RESULT
        election.electedSheriffUserId = targetUserId
        sheriffElectionRepository.save(election)
        electSheriff(targetUserId, context)

        // Persist the appoint outcome with whatever sheriff-election votes were
        // already cast so the action log shows the host's pick was a tie-break.
        val priorVotes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            context.gameId, VoteContext.SHERIFF_ELECTION, context.game.dayNumber,
        )
        val priorTally = priorVotes.mapNotNull { it.targetUserId }.groupingBy { it }.eachCount()
        actionLogService.recordSheriffResult(
            context.gameId, context.game.dayNumber, targetUserId, priorVotes, priorTally,
        )

        broadcastAfterCommit(context.gameId, DomainEvent.SheriffElected(context.gameId, targetUserId))
        broadcastAfterCommit(context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
        // Sheriff is shown on the RESULT screen until the host clicks 显示结果
        // (SHERIFF_END_RESULT) — no auto-timer.
        return GameActionResult.Success()
    }

    private fun electSheriff(winnerUserId: String, context: GameContext) {
        context.game.sheriffUserId = winnerUserId
        gameRepository.save(context.game)
        gamePlayerRepository.findByGameIdAndUserId(context.gameId, winnerUserId).ifPresent {
            it.sheriff = true; gamePlayerRepository.save(it)
        }
    }

    private fun quitCampaign(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SPEECH)
            return GameActionResult.Rejected("Not in SPEECH sub-phase")

        val candidate = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
            .firstOrNull { it.userId == request.actorUserId }
            ?: return GameActionResult.Rejected("Not a candidate")

        candidate.status = CandidateStatus.QUIT
        sheriffCandidateRepository.save(candidate)

        // If no running candidates remain in the speaking order, skip to night
        val speakingOrderIds = election.speakingOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val allCandidates = sheriffCandidateRepository.findByElectionId(election.id)
        val anyRunningLeft = allCandidates.any { it.status == CandidateStatus.RUNNING && speakingOrderIds.contains(it.userId) }
        if (!anyRunningLeft) {
            hostTimerService.cancel(context.gameId)
            // No running candidates remain — mirrors startSpeech's empty-candidates
            // branch: advance straight to DAY_DISCUSSION/RESULT_HIDDEN instead of
            // landing on SHERIFF_ELECTION/RESULT. The RESULT screen would show an
            // empty winner card and empty tally (no sheriff was elected), leaving
            // the host with nothing to dismiss — a dead-end (game 18 / room 22,
            // 2026-05-09). endResult() performs the identical write; inline it here.
            context.game.phase = GamePhase.DAY_DISCUSSION
            context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
            gameRepository.save(context.game)
            broadcastAfterCommit(
                context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name),
            )
            return GameActionResult.Success()
        }

        broadcastAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SPEECH.name)
        )
        return GameActionResult.Success()
    }

    private fun vote(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.VOTING)
            return GameActionResult.Rejected("Not in VOTING sub-phase")

        val candidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
        val speakingOrderIds = election.speakingOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val myCandidate = candidates.firstOrNull { it.userId == request.actorUserId }
        if (myCandidate?.status == CandidateStatus.RUNNING)
            return GameActionResult.Rejected("Candidates cannot vote for sheriff")
        if (myCandidate?.status == CandidateStatus.QUIT && speakingOrderIds.contains(request.actorUserId))
            return GameActionResult.Rejected("You quit the campaign and cannot vote")

        val targetUserId = request.targetUserId
            ?: return GameActionResult.Rejected("targetUserId required for SHERIFF_VOTE")
        if (targetUserId == request.actorUserId)
            return GameActionResult.Rejected("Cannot vote for yourself")
        if (candidates.none { it.userId == targetUserId && it.status == CandidateStatus.RUNNING })
            return GameActionResult.Rejected("Target is not a running candidate")

        upsertVote(context.gameId, context.game.dayNumber, request.actorUserId, targetUserId)
        broadcastVotingUpdate(context.gameId)
        return GameActionResult.Success()
    }

    private fun abstain(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.VOTING)
            return GameActionResult.Rejected("Not in VOTING sub-phase")

        val candidates = sheriffCandidateRepository.findByElectionId(election.id ?: error("Election has no ID"))
        val speakingOrderIds = election.speakingOrder?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val myCandidate = candidates.firstOrNull { it.userId == request.actorUserId }
        if (myCandidate?.status == CandidateStatus.RUNNING)
            return GameActionResult.Rejected("Candidates cannot vote for sheriff")
        if (myCandidate?.status == CandidateStatus.QUIT && speakingOrderIds.contains(request.actorUserId))
            return GameActionResult.Rejected("You quit the campaign and cannot vote")

        upsertVote(context.gameId, context.game.dayNumber, request.actorUserId, null)
        broadcastVotingUpdate(context.gameId)
        return GameActionResult.Success()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun upsertVote(gameId: Int, dayNumber: Int, voterUserId: String, targetUserId: String?) {
        val existing = voteRepository.findByGameIdAndVoteContextAndDayNumberAndVoterUserId(
            gameId, VoteContext.SHERIFF_ELECTION, dayNumber, voterUserId
        ).orElse(null)
        if (existing != null) {
            existing.targetUserId = targetUserId
            voteRepository.save(existing)
        } else {
            voteRepository.save(
                Vote(
                    gameId = gameId,
                    voteContext = VoteContext.SHERIFF_ELECTION,
                    dayNumber = dayNumber,
                    voterUserId = voterUserId,
                    targetUserId = targetUserId,
                )
            )
        }
    }

    private fun broadcastSignupUpdate(gameId: Int) {
        broadcastAfterCommit(
            gameId,
            DomainEvent.PhaseChanged(gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.SIGNUP.name)
        )
    }

    private fun broadcastVotingUpdate(gameId: Int) {
        broadcastAfterCommit(
            gameId,
            DomainEvent.PhaseChanged(gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.VOTING.name)
        )
    }

    private fun buildResult(
        gameId: Int,
        game: Game,
        candidates: List<SheriffCandidate>,
        userMap: Map<String, User>,
        playerMap: Map<String, GamePlayer>,
    ): Map<String, Any?> {
        val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, game.dayNumber
        )
        val voterUserMap = userRepository.findAllById(votes.map { it.voterUserId }).associateBy { it.userId }
        val sheriffUser = game.sheriffUserId?.let { userMap[it] ?: voterUserMap[it] }

        val tally = candidates.filter { it.status == CandidateStatus.RUNNING }.map { c ->
            val votersForC = votes.filter { it.targetUserId == c.userId }
            mapOf(
                "candidateId" to c.userId,
                "nickname" to (userMap[c.userId]?.nickname ?: c.userId),
                "seatIndex" to playerMap[c.userId]?.seatIndex,
                "votes" to votersForC.size,
                "voters" to votersForC.map { v ->
                    val vUser = voterUserMap[v.voterUserId]
                    mapOf(
                        "userId" to v.voterUserId,
                        "nickname" to (vUser?.nickname ?: v.voterUserId),
                        "avatar" to vUser?.avatarUrl,
                        "seatIndex" to (playerMap[v.voterUserId]?.seatIndex ?: 0),
                    )
                },
            )
        }

        val abstainVotes = votes.filter { it.targetUserId == null }
        return mapOf(
            "sheriffId" to game.sheriffUserId,
            "sheriffNickname" to (sheriffUser?.nickname ?: game.sheriffUserId ?: ""),
            "sheriffAvatar" to sheriffUser?.avatarUrl,
            "tally" to tally,
            "abstainCount" to abstainVotes.size,
            "abstainVoters" to abstainVotes.map { v ->
                val vUser = voterUserMap[v.voterUserId]
                mapOf(
                    "userId" to v.voterUserId,
                    "nickname" to (vUser?.nickname ?: v.voterUserId),
                    "avatar" to vUser?.avatarUrl,
                    "seatIndex" to (playerMap[v.voterUserId]?.seatIndex ?: 0),
                )
            },
        )
    }

    /**
     * Host action: dismiss the SHERIFF_ELECTION/RESULT screen and advance to
     * DAY_DISCUSSION/RESULT_HIDDEN. Bound to the 显示结果 button on the host's
     * sheriff RESULT view; replaces the old 60s auto-timer.
     *
     * Lands on RESULT_HIDDEN, not RESULT_REVEALED — the host still needs to
     * click REVEAL_NIGHT_RESULT next to flip deaths into RESULT_REVEALED and
     * apply the deferred N1 kills. This preserves the traditional 狼人杀
     * cadence: sheriff election runs BEFORE the death announcement so N1
     * victims could 上警 and use 挡刀 / 撕牌 tactics.
     */
    private fun endResult(request: GameActionRequest, context: GameContext): GameActionResult {
        if (request.actorUserId != context.game.hostUserId)
            return GameActionResult.Rejected("Only host can dismiss the sheriff result")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.RESULT)
            return GameActionResult.Rejected("Not in sheriff RESULT sub-phase")
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Game is not in SHERIFF_ELECTION phase")

        context.game.phase = GamePhase.DAY_DISCUSSION
        context.game.subPhase = DaySubPhase.RESULT_HIDDEN.name
        gameRepository.save(context.game)
        broadcastAfterCommit(
            context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_HIDDEN.name),
        )
        return GameActionResult.Success()
    }

    /**
     * Cancel any scheduled auto-advance jobs for the given game. The auto-
     * advance timer at SHERIFF/RESULT was removed in favour of the
     * SHERIFF_END_RESULT host action, so this is now a no-op left in place
     * for callers that defensively call it (e.g. GamePhasePipeline.startNight,
     * a few integration tests).
     */
    fun cancelScheduledJob(gameId: Int) {
        // intentionally empty — timer was removed
    }

    /**
     * Broadcast a game event so it lands AFTER the surrounding @Transactional commits.
     *
     * Sheriff-election state (`election.subPhase`, candidate status, votes) lives in
     * SheriffElection / SheriffCandidate / Vote rows that this service writes inside
     * `@Transactional handle()`. If we publish the PhaseChanged STOMP frame
     * immediately, fast clients (e.g. CI shard 3, observed 2026-04-30) read
     * `/api/game/{id}/state` in their own short read tx before the outer write tx
     * commits, get the *prior* sub-phase, and the test loop spins until timeout.
     *
     * Same shape PR #67 fixed for `GamePhasePipeline.dayAdvance`. When called
     * outside an active tx (e.g. from a coroutine that already committed), fall
     * through to an immediate broadcast.
     */
    private fun broadcastAfterCommit(gameId: Int, event: DomainEvent) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    stompPublisher.broadcastGame(gameId, event)
                }
            })
        } else {
            stompPublisher.broadcastGame(gameId, event)
        }
    }
}
