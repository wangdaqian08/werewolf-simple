package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.GameContext
import com.werewolf.game.action.GameActionRequest
import com.werewolf.game.action.GameActionResult
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
        val speechQuitterIds = candidates
            .filter { it.status == CandidateStatus.QUIT && speakingOrderIds.contains(it.userId) }
            .map { it.userId }.toSet()
        val totalAlivePlayers = players.count { it.alive }
        val eligibleVoterCount = totalAlivePlayers - speechQuitterIds.size
        val submittedVoteCount = voteRepository.findByGameIdAndVoteContextAndDayNumber(
            gameId, VoteContext.SHERIFF_ELECTION, game.dayNumber
        ).size
        // allVoted is true when all eligible voters have submitted a vote (including voluntary abstains).
        // If eligibleVoterCount == 0 (all players are speech-quitters), voting is trivially complete.
        val allVoted = eligibleVoterCount == 0 || submittedVoteCount >= eligibleVoterCount

        return mapOf(
            "subPhase" to election.subPhase.name,
            "timeRemaining" to 0,
            "candidates" to candidates.map { c ->
                val user = userMap[c.userId]
                mapOf(
                    "userId" to c.userId,
                    "nickname" to (user?.nickname ?: c.userId),
                    "avatar" to user?.avatarUrl,
                    "status" to c.status.name,
                )
            },
            "speakingOrder" to speakingOrderIds,
            "currentSpeakerId" to currentSpeakerId,
            // hasPassed: player explicitly chose not to run (QUIT but was never in the speaking order)
            "hasPassed" to (myCandidate?.status == CandidateStatus.QUIT && !speakingOrderIds.contains(myPlayer?.userId)),
            "myVote" to myVoteRecord?.targetUserId,
            "abstained" to (myVoteRecord != null && myVoteRecord.targetUserId == null),
            // canVote: only players who QUIT during speech (were in speaking order) lose their vote
            "canVote" to !(myCandidate?.status == CandidateStatus.QUIT && speakingOrderIds.contains(myPlayer?.userId)),
            "allVoted" to allVoted,
            // voteProgress: speech-quitters count as auto-voted (they can't vote); total = all alive
            "voteProgress" to mapOf("voted" to submittedVoteCount + speechQuitterIds.size, "total" to totalAlivePlayers),
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
        val existing = sheriffCandidateRepository.findByElectionId(electionId)
            .firstOrNull { it.userId == request.actorUserId }
        if (existing != null) {
            existing.status = CandidateStatus.RUNNING
            sheriffCandidateRepository.save(existing)
        } else {
            sheriffCandidateRepository.save(SheriffCandidate(electionId = electionId, userId = request.actorUserId))
        }
        broadcastSignupUpdate(context.gameId)
        return GameActionResult.Success()
    }

    private fun pass(request: GameActionRequest, context: GameContext): GameActionResult {
        if (context.game.phase != GamePhase.SHERIFF_ELECTION)
            return GameActionResult.Rejected("Not in SHERIFF_ELECTION phase")
        val election = context.election ?: return GameActionResult.Rejected("No election in progress")
        if (election.subPhase != ElectionSubPhase.SIGNUP)
            return GameActionResult.Rejected("Sign-up period is over")

        val electionId = election.id ?: error("Election has no ID")
        val existing = sheriffCandidateRepository.findByElectionId(electionId)
            .firstOrNull { it.userId == request.actorUserId }
        if (existing == null) {
            // Record the pass so hasPassed can be returned correctly
            sheriffCandidateRepository.save(
                SheriffCandidate(electionId = electionId, userId = request.actorUserId, status = CandidateStatus.QUIT)
            )
        } else if (existing.status == CandidateStatus.RUNNING) {
            existing.status = CandidateStatus.QUIT
            sheriffCandidateRepository.save(existing)
        }
        broadcastSignupUpdate(context.gameId)
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
            // No one ran — sheriff election is over before it starts.
            // Variant B: return to day discussion (we're already on Day 1).
            advanceToDayDiscussion(context.gameId)
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
                // Running candidates exist but got zero votes → TIED → host appoints
                election.subPhase = ElectionSubPhase.TIED
                sheriffElectionRepository.save(election)
                broadcastAfterCommit(context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.TIED.name))
            } else {
                // No running candidates at all → RESULT with auto-advance to night
                election.subPhase = ElectionSubPhase.RESULT
                sheriffElectionRepository.save(election)
                broadcastAfterCommit(context.gameId,
                    DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
                scheduleAutoAdvanceFromSheriffResult(context.gameId)
            }
            return GameActionResult.Success()
        }
        if (topCandidates.size == 1) {
            val winnerUserId = topCandidates.first()
            election.subPhase = ElectionSubPhase.RESULT
            election.electedSheriffUserId = winnerUserId
            sheriffElectionRepository.save(election)
            electSheriff(winnerUserId, context)
            broadcastAfterCommit(context.gameId, DomainEvent.SheriffElected(context.gameId, winnerUserId))
            broadcastAfterCommit(context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
            // Schedule auto-advance to day discussion (60s)
            scheduleAutoAdvanceFromSheriffResult(context.gameId)
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

        broadcastAfterCommit(context.gameId, DomainEvent.SheriffElected(context.gameId, targetUserId))
        broadcastAfterCommit(context.gameId,
            DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
        // Schedule auto-advance to day discussion (60s)
        scheduleAutoAdvanceFromSheriffResult(context.gameId)
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
            // No running candidates remain → show RESULT phase with auto-advance to night
            election.subPhase = ElectionSubPhase.RESULT
            sheriffElectionRepository.save(election)
            broadcastAfterCommit(context.gameId,
                DomainEvent.PhaseChanged(context.gameId, GamePhase.SHERIFF_ELECTION, ElectionSubPhase.RESULT.name))
            scheduleAutoAdvanceFromSheriffResult(context.gameId)
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
        // Only candidates who QUIT during SPEECH (were in speaking order) forfeit their vote
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
     * Variant B: after the sheriff RESULT is shown, auto-advance back to
     * DAY_DISCUSSION/RESULT_REVEALED. The host then drives the regular day
     * cadence (dayAdvance → DAY_VOTING). The game stays on the same Day 1
     * — we are NOT starting a new night.
     */
    private fun scheduleAutoAdvanceFromSheriffResult(gameId: Int): Job {
        log.info("[SheriffService] Scheduling auto-advance to day discussion for game $gameId in 60 seconds")
        return coroutineScope.launch {
            delay(60_000) // 60 seconds for manual interaction
            log.info("[SheriffService] Auto-advance to day discussion triggered for game $gameId")
            advanceToDayDiscussion(gameId)
        }.also { job ->
            scheduledJobs[gameId] = job
        }
    }

    /**
     * Transition SHERIFF_ELECTION → DAY_DISCUSSION/RESULT_REVEALED. Idempotent:
     * if the phase has already moved on (e.g. a fast host clicked a manual
     * advance before the 60s timer fired) this is a no-op.
     */
    @Transactional
    fun advanceToDayDiscussion(gameId: Int) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        if (game.phase != GamePhase.SHERIFF_ELECTION) return
        game.phase = GamePhase.DAY_DISCUSSION
        game.subPhase = DaySubPhase.RESULT_REVEALED.name
        gameRepository.save(game)
        broadcastAfterCommit(
            gameId,
            DomainEvent.PhaseChanged(gameId, GamePhase.DAY_DISCUSSION, DaySubPhase.RESULT_REVEALED.name),
        )
    }

    fun cancelScheduledJob(gameId: Int) {
        scheduledJobs[gameId]?.cancel()
        scheduledJobs.remove(gameId)
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
