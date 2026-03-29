package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
) {
    @Transactional
    fun startGame(hostUserId: String, roomId: Int): GameActionResult {
        val room = roomRepository.findById(roomId).orElse(null)
            ?: return GameActionResult.Rejected("Room not found")
        if (room.hostUserId != hostUserId)
            return GameActionResult.Rejected("Only the host can start the game")
        if (room.status != RoomStatus.WAITING)
            return GameActionResult.Rejected("Room is not in WAITING state")

        val roomPlayers = roomPlayerRepository.findByRoomId(roomId)
        if (roomPlayers.size < 4)
            return GameActionResult.Rejected("Need at least 4 players to start")

        val notReady = roomPlayers.filter { !it.host && it.status != ReadyStatus.READY }
        if (notReady.isNotEmpty())
            return GameActionResult.Rejected("Not all players are ready")

        val roles = buildRoleList(room, roomPlayers.size)
        roles.shuffle()

        val game = Game(roomId = roomId, hostUserId = hostUserId)
        gameRepository.save(game)
        val gameId = game.gameId ?: error("Failed to persist game")

        val gamePlayers = roomPlayers.mapIndexed { idx, rp ->
            GamePlayer(
                gameId = gameId,
                userId = rp.userId,
                seatIndex = rp.seatIndex ?: error("Player ${rp.userId} has no seat"),
                role = roles[idx],
            )
        }
        gamePlayerRepository.saveAll(gamePlayers)

        room.status = RoomStatus.IN_GAME
        roomRepository.save(room)

        stompPublisher.broadcastRoom(roomId, mapOf("type" to "GAME_STARTED", "payload" to mapOf("gameId" to gameId)))

        gamePlayers.forEach { gp ->
            stompPublisher.sendPrivate(gp.userId, DomainEvent.RoleAssigned(gameId, gp.userId, gp.role))
        }

        stompPublisher.broadcastGame(gameId, DomainEvent.PhaseChanged(gameId, GamePhase.ROLE_REVEAL, null))
        return GameActionResult.Success()
    }

    fun getGameState(gameId: Int, requestingUserId: String): Map<String, Any?> {
        val game = gameRepository.findById(gameId).orElse(null)
            ?: return mapOf("error" to "Game not found")
        val room = roomRepository.findById(game.roomId).orElse(null)
        val players = gamePlayerRepository.findByGameId(gameId)
        val myPlayer = players.firstOrNull { it.userId == requestingUserId }

        val roleReveal = if (game.phase == GamePhase.ROLE_REVEAL) {
            val confirmedCount = players.count { it.confirmedRole }
            val teammates = if (myPlayer?.role == PlayerRole.WEREWOLF) {
                val wolfIds = players
                    .filter { it.role == PlayerRole.WEREWOLF && it.userId != requestingUserId }
                    .map { it.userId }
                userRepository.findAllById(wolfIds).map { it.nickname }
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
                val base = mutableMapOf<String, Any?>("subPhase" to np.subPhase.name, "dayNumber" to np.dayNumber)
                if (myPlayer?.role == PlayerRole.WEREWOLF && np.wolfTargetUserId != null) {
                    base["selectedTargetId"] = np.wolfTargetUserId
                }
                base
            }
        } else null

        val votingPhase = if (game.phase == GamePhase.VOTING) {
            val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(
                gameId, VoteContext.ELIMINATION, game.dayNumber
            )
            val playerMap = players.associateBy { it.userId }
            val userLookup = userRepository.findAllById(players.map { it.userId }).associateBy { it.userId }
            val myVotingPlayer = players.firstOrNull { it.userId == requestingUserId }
            val myVoteRecord = votes.firstOrNull { it.voterUserId == requestingUserId }

            val tallyRevealed = game.subPhase in setOf(
                VotingSubPhase.VOTE_RESULT.name,
                VotingSubPhase.HUNTER_SHOOT.name,
                VotingSubPhase.BADGE_HANDOVER.name,
            )

            val rawTally: Map<String, Int> = votes
                .mapNotNull { it.targetUserId }
                .groupingBy { it }
                .eachCount()

            val tallyList = if (tallyRevealed) {
                rawTally.entries.map { (targetId, voteCount) ->
                    val targetPlayer = playerMap[targetId]
                    val targetUser = userLookup[targetId]
                    val voters = votes.filter { it.targetUserId == targetId }.map { v ->
                        val vp = playerMap[v.voterUserId]
                        val vu = userLookup[v.voterUserId]
                        mapOf(
                            "userId" to v.voterUserId,
                            "nickname" to (vu?.nickname ?: v.voterUserId),
                            "seatIndex" to (vp?.seatIndex ?: 0),
                        )
                    }
                    mapOf(
                        "playerId" to targetId,
                        "nickname" to (targetUser?.nickname ?: targetId),
                        "seatIndex" to (targetPlayer?.seatIndex ?: 0),
                        "votes" to voteCount,
                        "voters" to voters,
                    )
                }.sortedByDescending { it["votes"] as Int }
            } else null

            val elimHistory = eliminationHistoryRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
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
                "canVote" to ((myVotingPlayer?.alive == true) && (myVotingPlayer.canVote)),
                "myVote" to myVoteRecord?.targetUserId,
                "votedPlayerIds" to votes.map { it.voterUserId },
                "votesSubmitted" to votes.size,
                "totalVoters" to players.count { it.alive && it.canVote },
                "tally" to tallyList,
                "tallyRevealed" to tallyRevealed,
                "eliminatedPlayerId" to elimHistory?.eliminatedUserId,
                "eliminatedNickname" to eliminatedUser?.nickname,
                "eliminatedSeatIndex" to eliminatedPlayer?.seatIndex,
                "eliminatedRole" to elimHistory?.eliminatedRole?.name,
                "idiotRevealedId" to idiotRevealedPlayer?.userId,
                "idiotRevealedNickname" to idiotRevealedUser?.nickname,
                "idiotRevealedSeatIndex" to idiotRevealedPlayer?.seatIndex,
            )
        } else null

        return mapOf(
            "gameId" to gameId,
            "hostId" to game.hostUserId,
            "phase" to game.phase.name,
            "subPhase" to game.subPhase,
            "dayNumber" to game.dayNumber,
            "sheriffUserId" to game.sheriffUserId,
            "hasSheriff" to (room?.hasSheriff ?: true),
            "winner" to game.winner?.name,
            "myRole" to myPlayer?.role?.name,
            "roleReveal" to roleReveal,
            "sheriffElection" to sheriffElection,
            "votingPhase" to votingPhase,
            "nightPhase" to nightPhase,
            "players" to players.map { p ->
                mapOf(
                    "userId" to p.userId,
                    "seatIndex" to p.seatIndex,
                    "isAlive" to p.alive,
                    "isSheriff" to p.sheriff,
                    "confirmedRole" to p.confirmedRole,
                    "canVote" to p.canVote,
                    "idiotRevealed" to p.idiotRevealed,
                    "role" to if (p.userId == requestingUserId || game.phase == GamePhase.GAME_OVER) p.role.name else null,
                )
            },
        )
    }

    private fun buildRoleList(room: Room, playerCount: Int): MutableList<PlayerRole> {
        val roles = mutableListOf<PlayerRole>()
        val wolfCount = when {
            playerCount <= 6 -> 2
            playerCount <= 9 -> 3
            else -> playerCount / 3
        }
        repeat(wolfCount) { roles.add(PlayerRole.WEREWOLF) }
        if (room.hasSeer) roles.add(PlayerRole.SEER)
        if (room.hasWitch) roles.add(PlayerRole.WITCH)
        if (room.hasHunter) roles.add(PlayerRole.HUNTER)
        if (room.hasGuard) roles.add(PlayerRole.GUARD)
        if (room.hasIdiot) roles.add(PlayerRole.IDIOT)
        while (roles.size < playerCount) roles.add(PlayerRole.VILLAGER)
        return roles
    }
}
