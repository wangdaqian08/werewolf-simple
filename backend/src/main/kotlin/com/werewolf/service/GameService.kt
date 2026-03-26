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

        return mapOf(
            "gameId" to gameId,
            "phase" to game.phase.name,
            "subPhase" to game.subPhase,
            "dayNumber" to game.dayNumber,
            "sheriffUserId" to game.sheriffUserId,
            "winner" to game.winner?.name,
            "myRole" to myPlayer?.role?.name,
            "roleReveal" to roleReveal,
            "sheriffElection" to sheriffElection,
            "players" to players.map { p ->
                mapOf(
                    "userId" to p.userId,
                    "seatIndex" to p.seatIndex,
                    "isAlive" to p.alive,
                    "isSheriff" to p.sheriff,
                    "confirmedRole" to p.confirmedRole,
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
