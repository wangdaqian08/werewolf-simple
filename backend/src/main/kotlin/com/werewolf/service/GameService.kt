package com.werewolf.service

import com.werewolf.audio.AudioReplayCache
import com.werewolf.game.DomainEvent
import com.werewolf.game.RoleDealer
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.game.role.RoleRules
import com.werewolf.game.timer.HostTimerService
import com.werewolf.game.voting.TallyCalculator
import com.werewolf.model.*
import com.werewolf.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.random.Random

@Service
class GameService(
    private val gameRepository: GameRepository,
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val stompPublisher: StompPublisher,
    private val nightOrchestrator: NightOrchestrator,
    private val userRepository: UserRepository,
    private val sheriffService: SheriffService,
    private val nightPhaseRepository: NightPhaseRepository,
    private val voteRepository: VoteRepository,
    private val eliminationHistoryRepository: EliminationHistoryRepository,
    private val audioReplayCache: AudioReplayCache,
    private val hostTimerService: HostTimerService,
    private val creditTransactionRepository: CreditTransactionRepository,
    private val walletService: WalletService,
    private val perkService: PerkService,
) {
    @Transactional
    fun startGame(hostUserId: String, roomId: Int): GameActionResult {
        // Row lock: a double-tapped Start must see IN_GAME, not create a second game.
        val room = roomRepository.findByIdForUpdate(roomId).orElse(null)
            ?: return GameActionResult.Rejected("Room not found")
        if (room.hostUserId != hostUserId)
            return GameActionResult.Rejected("Only the host can start the game")
        if (room.status != RoomStatus.WAITING)
            return GameActionResult.Rejected("Room is not in WAITING state")

        val roomPlayers = roomPlayerRepository.findByRoomId(roomId)
        if (roomPlayers.size < 4)
            return GameActionResult.Rejected("Need at least 4 players to start")

        // The client gates Start on a full room too, but buildRoleList would silently drop roles if short.
        if (roomPlayers.size != room.totalPlayers)
            return GameActionResult.Rejected("Need ${room.totalPlayers} players, have ${roomPlayers.size}")

        if (roomPlayers.any { it.seatIndex == null })
            return GameActionResult.Rejected("Not all players have a seat")

        val notReady = roomPlayers.filter { !it.host && it.status != ReadyStatus.READY }
        if (notReady.isNotEmpty())
            return GameActionResult.Rejected("Not all players are ready")

        val perksOn = room.config?.perksAllowed != false
        if (perksOn && roomPlayers.any { it.rolePick == null })
            return GameActionResult.Rejected("Every player must choose a role-pick card")

        val dealt = RoleDealer.deal(
            RoleDealer.roleList(room, roomPlayers.size), roomPlayers.map { it.userId },
            if (perksOn) perkService.liveRolePicks(roomId) else emptyMap(), Random.Default,
        )

        val game = Game(roomId = roomId, hostUserId = hostUserId)
        gameRepository.save(game)
        val gameId = game.gameId ?: error("Failed to persist game")

        val gamePlayers = roomPlayers.map { rp ->
            GamePlayer(
                gameId = gameId,
                userId = rp.userId,
                seatIndex = rp.seatIndex ?: error("Player ${rp.userId} has no seat"),
                role = dealt.getValue(rp.userId),
            )
        }
        gamePlayerRepository.saveAll(gamePlayers)

        // Bind room perk activations to this game; activations held by a
        // player dealt a wolf role become VOID — bound but inapplicable. The
        // refund happens at game end via PerkSettlementService (refunding now
        // would leak the wolf identity through the balance change).
        perkService.onGameStart(roomId, gameId, gamePlayers)

        room.status = RoomStatus.IN_GAME
        roomRepository.save(room)

        // Broadcasts must happen AFTER the transaction commits. Otherwise a
        // recipient that navigates to /game/{id} on GAME_STARTED and calls
        // getGameState in a separate read transaction will not yet see the
        // games row this method just inserted, and the response degrades to
        // {error: "Game not found"} — the frontend then renders a blank
        // fallback UI. Reproduced in prod 2026-04-29 game=10: GAME_STARTED
        // delivered at t≈0, getGameState SKIPped "(game not found)" at
        // t+1.8s, the @Transactional only returned (committed) at t+2.5s.
        val playersForBroadcast = gamePlayers.toList()
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    broadcastGameStarted(roomId, gameId, playersForBroadcast)
                }
            })
        } else {
            broadcastGameStarted(roomId, gameId, playersForBroadcast)
        }
        return GameActionResult.Success()
    }

    private fun broadcastGameStarted(roomId: Int, gameId: Int, gamePlayers: List<GamePlayer>) {
        stompPublisher.broadcastRoom(roomId, mapOf("type" to "GAME_STARTED", "payload" to mapOf("gameId" to gameId)))
        gamePlayers.forEach { gp ->
            stompPublisher.sendPrivate(gp.userId, DomainEvent.RoleAssigned(gameId, gp.userId, gp.role))
        }
        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.ROLE_REVEAL, null))
    }

    @Transactional
    fun getGameState(gameId: Int, requestingUserId: String): Map<String, Any?> {
        val game = gameRepository.findById(gameId).orElse(null)
            ?: return mapOf("error" to "Game not found")
        val room = roomRepository.findById(game.roomId).orElse(null)
        val players = gamePlayerRepository.findByGameId(gameId)
        val myPlayer = players.firstOrNull { it.userId == requestingUserId }

        // Always look up user nicknames — used by players map, roleReveal, nightPhase, votingPhase
        val userLookup = userRepository.findAllById(players.map { it.userId }).associateBy { it.userId }
        // Per-room nickname overrides come from the RoomPlayer row (carry through
        // for the lifetime of the game). Avatar URLs come from the User row.
        val roomPlayerLookup = roomPlayerRepository.findByRoomId(game.roomId).associateBy { it.userId }
        fun displayNameFor(userId: String): String =
            roomPlayerLookup[userId]?.displayName ?: userLookup[userId]?.nickname ?: userId

        val roleReveal = if (game.phase == GamePhase.ROLE_REVEAL) {
            val confirmedCount = players.count { it.confirmedRole }
            val teammates = if (myPlayer?.role?.isPackWolf == true) {
                players
                    .filter { it.role.isPackWolf && it.userId != requestingUserId }
                    .map { userLookup[it.userId]?.nickname ?: it.userId }
            } else emptyList()
            mapOf(
                "confirmedCount" to confirmedCount,
                "totalCount" to players.size,
                "teammates" to teammates,
            )
        } else null

        val sheriffElection = if (game.phase == GamePhase.SHERIFF_ELECTION) {
            sheriffService.buildState(gameId, game, myPlayer, players)
        } else null

        val nightPhase = if (game.phase == GamePhase.NIGHT) {
            nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)?.let { np ->
                val base = mutableMapOf<String, Any?>(
                    "subPhase"  to np.subPhase.name,
                    "dayNumber" to np.dayNumber,
                )
                val playerMap = players.associateBy { it.userId }

                // ── WEREWOLF: share selected target + formatted teammate list ──────────
                // The mechanical wolf is not in the pack: no teammates, and it sees the
                // pick only while it is the lone killer. Only current killers (alive) see the pick:
                // a dead pack wolf gets neither selectedTargetId nor wolfKiller.
                val wolfKiller = myPlayer != null && myPlayer in RoleRules.wolfKillers(players.filter { it.alive })
                base["wolfKiller"] = wolfKiller
                if (wolfKiller && np.wolfTargetUserId != null) base["selectedTargetId"] = np.wolfTargetUserId
                if (myPlayer?.role?.isPackWolf == true) {
                    val teammates = players.filter { it.role.isPackWolf && it.userId != requestingUserId }
                    base["teammates"] = teammates.map { tp ->
                        "${tp.seatIndex}·${userLookup[tp.userId]?.nickname ?: tp.userId}"
                    }
                }

                // ── SEER: share result + full check history ───────────────────────────
                if (myPlayer?.role == PlayerRole.SEER && np.seerCheckedUserId != null) {
                    val allNights = nightPhaseRepository.findByGameId(gameId)
                    val history = allNights
                        .filter { it.seerCheckedUserId != null }
                        .sortedBy { it.dayNumber }
                    base["seerResult"] = mapOf(
                        "checkedPlayerId"  to np.seerCheckedUserId,
                        "checkedNickname"  to (userLookup[np.seerCheckedUserId]?.nickname ?: np.seerCheckedUserId),
                        "checkedSeatIndex" to (playerMap[np.seerCheckedUserId]?.seatIndex ?: 0),
                        "isWerewolf"       to (np.seerResultIsWerewolf ?: false),
                        "history"          to history.map { h ->
                            mapOf(
                                "round"      to h.dayNumber,
                                "nickname"   to (userLookup[h.seerCheckedUserId]?.nickname ?: h.seerCheckedUserId),
                                "isWerewolf" to (h.seerResultIsWerewolf ?: false),
                            )
                        }
                    )
                }

                // ── MEDIUM: share the exact role seen + full check history ─────────────
                if (myPlayer?.role == PlayerRole.MEDIUM && np.mediumCheckedUserId != null) {
                    val history = nightPhaseRepository.findByGameId(gameId)
                        .filter { it.mediumCheckedUserId != null }
                        .sortedBy { it.dayNumber }
                    base["mediumResult"] = mapOf(
                        "checkedPlayerId"  to np.mediumCheckedUserId,
                        "checkedNickname"  to (userLookup[np.mediumCheckedUserId]?.nickname ?: np.mediumCheckedUserId),
                        "checkedSeatIndex" to (playerMap[np.mediumCheckedUserId]?.seatIndex ?: 0),
                        "role"             to np.mediumResultRole?.name,
                        "history"          to history.map { h ->
                            mapOf(
                                "round"    to h.dayNumber,
                                "nickname" to (userLookup[h.mediumCheckedUserId]?.nickname ?: h.mediumCheckedUserId),
                                "role"     to h.mediumResultRole?.name,
                            )
                        }
                    )
                }

                // ── MECHANICAL WOLF: learned role, tonight's skill, and tonight's action ──
                if (myPlayer?.role == PlayerRole.MECHANICAL_WOLF) {
                    val allNights = nightPhaseRepository.findByGameId(gameId)
                    val skillTonight = RoleRules.mechNightSkill(myPlayer, np.dayNumber, allNights)
                    fun target(id: String?) = mapOf(
                        "targetPlayerId"   to id,
                        "targetNickname"   to id?.let { userLookup[it]?.nickname ?: it },
                        "targetSeatIndex"  to id?.let { playerMap[it]?.seatIndex },
                    )
                    val result: Map<String, Any?>? = when {
                        np.mechLearnTargetUserId != null ->
                            mapOf("kind" to "LEARN", "role" to myPlayer.learnedRole?.name) + target(np.mechLearnTargetUserId)
                        np.mechCheckTargetUserId != null ->
                            if (myPlayer.learnedRole?.learnedSkill == LearnedSkill.CHECK_ALIGNMENT)
                                mapOf("kind" to "CHECK", "isWerewolf" to (np.mechCheckResultRole?.isWolf ?: false)) + target(np.mechCheckTargetUserId)
                            else mapOf("kind" to "CHECK", "role" to np.mechCheckResultRole?.name) + target(np.mechCheckTargetUserId)
                        np.mechPoisonTargetUserId != null -> mapOf("kind" to "POISON") + target(np.mechPoisonTargetUserId)
                        np.mechGuardTargetUserId != null -> mapOf("kind" to "PROTECT") + target(np.mechGuardTargetUserId)
                        // No field records a skip: reaching the result sub-phase with nothing done means it skipped.
                        np.subPhase == NightSubPhase.MECH_WOLF_RESULT -> mapOf("kind" to "SKIP")
                        else -> null
                    }
                    base["mechWolf"] = mapOf(
                        "learnedRole"     to myPlayer.learnedRole?.name,
                        "learnedPlayerId" to allNights.firstOrNull { it.mechLearnTargetUserId != null }?.mechLearnTargetUserId,
                        "skillTonight"    to skillTonight.name,
                        "result"          to result,
                    )
                }

                // ── WITCH: share attack info + per-game potion availability ───────────
                if (myPlayer?.role == PlayerRole.WITCH && np.subPhase == NightSubPhase.WITCH_ACT) {
                    val allNights = nightPhaseRepository.findByGameId(gameId)
                    val hasAntidote = allNights.none { it.witchAntidoteUsed }
                    base["hasAntidote"] = hasAntidote
                    base["hasPoison"]   = allNights.none { it.witchPoisonTargetUserId != null }
                    // The witch learns the victim only while she holds the antidote.
                    if (hasAntidote && np.wolfTargetUserId != null) {
                        val attackedPlayer = playerMap[np.wolfTargetUserId]
                        base["attackedPlayerId"] = np.wolfTargetUserId
                        base["attackedNickname"] = userLookup[np.wolfTargetUserId]?.nickname
                        base["attackedSeatIndex"] = attackedPlayer?.seatIndex
                        val selfSaveAllowed = (room?.config?.witchSelfSaveAllowed ?: true) && np.dayNumber == 1
                        base["selfSaveBlocked"] = np.wolfTargetUserId == requestingUserId && !selfSaveAllowed
                    }
                }

                // ── GUARD: share previous protection target ───────────────────────────
                if (myPlayer?.role == PlayerRole.GUARD) {
                    base["previousGuardTargetId"] = np.prevGuardTargetUserId
                }

                base
            }
        } else null

        val dayPhase = if (game.phase == GamePhase.DAY_DISCUSSION) {
            val playerMap = players.associateBy { it.userId }
            // Show the night deaths once the host reveals them and through the
            // badge handover, so the death announcement stays visible until the
            // vote begins.
            val showNightResult = game.subPhase in setOf(
                DaySubPhase.RESULT_REVEALED.name,
                DaySubPhase.BADGE_HANDOVER.name,
            )
            // Night kills come from NightPhase (EliminationHistory only tracks voting eliminations).
            // Shared computePendingKills keeps this consistent with the actual
            // applied kills (witch/guard saves AND the night-1 immunity perk) —
            // an inline re-derivation here previously risked diverging.
            val nightResult = if (showNightResult) {
                val np = nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
                if (np != null) {
                    val uniqueKilledIds = NightOrchestrator.bySeat(nightOrchestrator.computePendingKills(gameId, np), players)

                    if (uniqueKilledIds.isNotEmpty()) {
                        mapOf(
                            "killedPlayers" to uniqueKilledIds.map { killedId ->
                                val killedPlayer = playerMap[killedId]
                                val killedUser = userLookup[killedId]
                                mapOf(
                                    "killedPlayerId"  to killedId,
                                    "killedNickname"  to (killedUser?.nickname ?: killedId),
                                    "killedSeatIndex" to (killedPlayer?.seatIndex ?: 0),
                                    "killedAvatar"    to killedUser?.avatarUrl,
                                )
                            }
                        )
                    } else null
                } else null
            } else null
            // The wolf who self-destructed (自爆) this day, if any — surfaced in the
            // day death banner. Cleared at night-init so it only shows for its day.
            val selfDestruct = game.selfDestructUserId?.let { sdId ->
                val takenId = game.selfDestructTakenUserId
                mapOf(
                    "seatIndex"      to (playerMap[sdId]?.seatIndex ?: 0),
                    "nickname"       to displayNameFor(sdId),
                    "takenSeatIndex" to takenId?.let { playerMap[it]?.seatIndex },
                    "takenNickname"  to takenId?.let { displayNameFor(it) },
                )
            }
            // Public: the knight's duel, for its own day's banner only.
            val knightDuel = game.knightDuelTargetUserId?.takeIf { game.knightDuelDay == game.dayNumber }?.let { targetId ->
                val knightId = players.firstOrNull { it.role == PlayerRole.KNIGHT }?.userId
                mapOf(
                    "knightSeatIndex"  to (knightId?.let { playerMap[it]?.seatIndex } ?: 0),
                    "knightNickname"   to (knightId?.let { displayNameFor(it) } ?: ""),
                    "targetSeatIndex"  to (playerMap[targetId]?.seatIndex ?: 0),
                    "targetNickname"   to displayNameFor(targetId),
                    "targetIsWolf"     to (playerMap[targetId]?.role?.isWolf == true),
                )
            }
            mapOf(
                "subPhase"      to (game.subPhase ?: DaySubPhase.RESULT_HIDDEN.name),
                "dayNumber"     to game.dayNumber,
                "phaseDeadline" to 0L,
                "phaseStarted"  to 0L,
                "nightResult"   to nightResult,
                "selfDestruct"  to selfDestruct,
                "knightDuel"    to knightDuel,
                "canVote"       to (myPlayer != null && myPlayer.alive && myPlayer.canVote),
                // Private: true only on the pending shooter's own state.
                "canShoot"      to (game.pendingShooterUserId == requestingUserId),
            )
        } else null

        val votingPhase = if (game.phase == GamePhase.DAY_VOTING) {
            val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
                gameId, VoteContext.ELIMINATION, game.dayNumber
            )
            val playerMap = players.associateBy { it.userId }
            val myVotingPlayer = players.firstOrNull { it.userId == requestingUserId }
            val pk = if (game.subPhase == VotingSubPhase.RE_VOTING.name) game.pkCandidates() else emptySet()
            val myVoteRecord = votes.firstOrNull { it.voterUserId == requestingUserId }

            val tallyRevealed = game.subPhase in setOf(
                VotingSubPhase.VOTE_RESULT.name,
                VotingSubPhase.BADGE_HANDOVER.name,
            )

            val elimHistory = eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
            // Matches VotingPipeline.revealTally: a vote for a player declared out counts for no one.
            // Not `alive`: the player this vote eliminated is dead by now and keeps their votes.
            val counted = votes.filter { v ->
                v.targetUserId?.let { t -> playerMap[t]?.removedByHost != true || t == elimHistory?.eliminatedUserId } != false
            }
            val rawTally: Map<String, Double> = TallyCalculator.calculateWeightedTally(counted)

            val voterInfo = { v: Vote ->
                mapOf(
                    "userId" to v.voterUserId,
                    "nickname" to (userLookup[v.voterUserId]?.nickname ?: v.voterUserId),
                    "seatIndex" to (playerMap[v.voterUserId]?.seatIndex ?: 0),
                )
            }

            val tallyList = if (tallyRevealed) {
                rawTally.entries.map { (targetId, voteCount) ->
                    val targetPlayer = playerMap[targetId]
                    val targetUser = userLookup[targetId]
                    val voters = counted.filter { it.targetUserId == targetId }.map(voterInfo)
                    mapOf(
                        "playerId" to targetId,
                        "nickname" to (targetUser?.nickname ?: targetId),
                        "seatIndex" to (targetPlayer?.seatIndex ?: 0),
                        "votes" to voteCount,
                        "voters" to voters,
                    )
                }.sortedByDescending { it["votes"] as Double }
            } else null

            val abstainVoters = if (tallyRevealed) {
                votes.filter { it.targetUserId == null }.map(voterInfo)
            } else null

            val eliminatedPlayer = elimHistory?.eliminatedUserId?.let { playerMap[it] }
            val eliminatedUser = elimHistory?.eliminatedUserId?.let { userLookup[it] }

            // Idiot reveal: no EliminationHistory record, but the top-voted player survived with idiotRevealed=true
            val idiotRevealedPlayer = if (elimHistory == null && tallyRevealed) {
                val topVotedId = rawTally.maxByOrNull { it.value }?.key
                topVotedId?.let { playerMap[it] }?.takeIf { it.idiotRevealed && it.alive }
            } else null
            val idiotRevealedUser = idiotRevealedPlayer?.let { userLookup[it.userId] }

            mapOf(
                "subPhase" to game.subPhase,
                "dayNumber" to game.dayNumber,
                "phaseDeadline" to 0L,
                "phaseStarted" to 0L,
                "canVote" to (myVotingPlayer != null && myVotingPlayer.alive && myVotingPlayer.canVote && requestingUserId !in pk),
                "pkCandidates" to pk.toList(),
                "myVote" to myVoteRecord?.targetUserId,
                "myVoteSkipped" to (myVoteRecord != null && myVoteRecord.targetUserId == null),
                "votedPlayerIds" to votes.map { it.voterUserId },
                "votesSubmitted" to votes.size,
                "totalVoters" to players.count { it.alive && it.canVote && it.userId !in pk },
                "tally" to tallyList,
                "abstainVoters" to abstainVoters,
                "tallyRevealed" to tallyRevealed,
                "eliminatedPlayerId" to elimHistory?.eliminatedUserId,
                "eliminatedNickname" to eliminatedUser?.nickname,
                "eliminatedSeatIndex" to eliminatedPlayer?.seatIndex,
                // Private: true only on the pending shooter's own state.
                "canShoot" to (game.pendingShooterUserId == requestingUserId),
                "idiotRevealedId" to idiotRevealedPlayer?.userId,
                "idiotRevealedNickname" to idiotRevealedUser?.nickname,
                "idiotRevealedSeatIndex" to idiotRevealedPlayer?.seatIndex,
            )
        } else null

        // Game-end credit rewards, rebuilt from the ledger (the durable record)
        // so a page refresh on the Result screen still shows earnings.
        val settlement = if (game.phase == GamePhase.GAME_OVER) {
            val playerMap = players.associateBy { it.userId }
            val rewardRows = creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD)
            if (rewardRows.isEmpty()) null else mapOf(
                "rewards" to rewardRows.map { tx ->
                    mapOf(
                        "userId" to tx.userId,
                        "nickname" to displayNameFor(tx.userId),
                        "seatIndex" to (playerMap[tx.userId]?.seatIndex ?: 0),
                        "amount" to tx.amount,
                    )
                },
                "myEarned" to rewardRows.firstOrNull { it.userId == requestingUserId }?.amount,
                "myBalance" to walletService.balance(requestingUserId),
            )
        } else null

        val timerSnapshot = hostTimerService.snapshot(gameId)
        return mapOf(
            "gameId" to gameId,
            "hostId" to game.hostUserId,
            "timer" to mapOf(
                "remainingMs" to timerSnapshot.remainingMs,
                "durationMs"  to timerSnapshot.durationMs,
                "running"     to timerSnapshot.running,
            ),
            "timerPresetsSeconds" to hostTimerService.presetsSeconds,
            "phase" to game.phase.name,
            "subPhase" to game.subPhase,
            "dayNumber" to game.dayNumber,
            "sheriffUserId" to game.sheriffUserId,
            "knightDuelUsed" to (game.knightDuelTargetUserId != null),
            "hasSheriff" to room?.hasSheriff,
            // bgmTrack lives in Room.config (JSONB GameConfig). roomStore on
            // the frontend is in-memory only, so a mid-game page reload loses
            // it. Surfacing the track here lets useAudioService recover BGM
            // after a refresh — the existing per-phase startBgm watcher reads
            // it from gameStore as a fallback when roomStore is empty.
            "bgmTrack" to room?.config?.bgmTrack,
            "witchSelfSaveAllowed" to (room?.config?.witchSelfSaveAllowed ?: true),
            "noKillAllowed" to (room?.config?.noKillAllowed ?: false),
            "guardSkipAllowed" to (room?.config?.guardSkipAllowed ?: false),
            // Private night-action history for the requester's role (role card).
            "roleHistory" to myPlayer?.let {
                RoleHistoryBuilder.build(it, game, nightPhaseRepository.findByGameId(gameId), players, ::displayNameFor)
            },
            // Room code + full game config for the in-game info modal.
            // Members only: non-members get null (never a 403) so existing
            // bots/scripts that poll state as outsiders keep working.
            "gameSettings" to if (myPlayer != null && room != null) mapOf(
                "roomCode" to room.roomCode,
                "totalPlayers" to room.totalPlayers,
                "wolfCount" to room.wolfCount,
                "roles" to RoleDealer.roleList(room, players.size).map { it.name },
                "hasSheriff" to room.hasSheriff,
                "witchSelfSaveAllowed" to (room.config?.witchSelfSaveAllowed ?: true),
                "noKillAllowed" to (room.config?.noKillAllowed ?: false),
                "guardSkipAllowed" to (room.config?.guardSkipAllowed ?: false),
                "winCondition" to room.winCondition.name,
            ) else null,
            "winner" to game.winner?.name,
            "settlement" to settlement,
            "myRole" to myPlayer?.role?.name,
            "myRolePick" to perkService.myRolePick(gameId, requestingUserId),
            // Wolves know each other all game (role card); everyone else gets an empty list.
            "wolfTeammates" to if (myPlayer?.role?.isPackWolf == true) {
                players.filter { it.role.isPackWolf && it.userId != requestingUserId }.sortedBy { it.seatIndex }.map { it.userId }
            } else emptyList(),
            "roleReveal" to roleReveal,
            "sheriffElection" to sheriffElection,
            "dayPhase" to dayPhase,
            "daySkipVoting" to game.daySkipVoting,
            "votingPhase" to votingPhase,
            "nightPhase" to nightPhase,
            // Audio recovery for STOMP-reconnect.
            //
            // `audioSequence` carries the most recent broadcast (legacy
            // single-field contract — preserves behaviour for any consumer
            // that already reads it). `audioReplayBuffer` carries the full
            // recent ring buffer so the frontend can replay every cue
            // missed during a disconnect window, not just the last one.
            //
            // Frontend dedup (useAudioService playedIds Set) skips
            // sequences the client already played, so polls do not
            // double-fire audio for still-online clients.
            "audioSequence" to audioReplayCache.getLatest(gameId),
            "audioReplayBuffer" to audioReplayCache.snapshot(gameId),
            "players" to players.sortedBy { it.seatIndex }.map { p ->
                mapOf(
                    "userId"        to p.userId,
                    "nickname"      to displayNameFor(p.userId),
                    "avatar"        to userLookup[p.userId]?.avatarUrl,
                    "seatIndex"     to p.seatIndex,
                    "isAlive"       to p.alive,
                    "isSheriff"     to p.sheriff,
                    "confirmedRole" to p.confirmedRole,
                    "canVote"       to p.canVote,
                    "idiotRevealed" to p.idiotRevealed,
                    "removedByHost" to p.removedByHost,
                    "role"          to if (p.userId == requestingUserId || game.phase == GamePhase.GAME_OVER) p.role.name else null,
                )
            },
        )
    }
}
