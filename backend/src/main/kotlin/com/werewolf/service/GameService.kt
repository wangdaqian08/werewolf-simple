package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.game.action.GameActionResult
import com.werewolf.game.night.NightOrchestrator
import com.werewolf.model.*
import com.werewolf.repository.GamePlayerRepository
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
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

        val roles = buildRoleList(room, roomPlayers.size)
        roles.shuffle()

        val game = Game(roomId = roomId, hostUserId = hostUserId)
        gameRepository.save(game)
        val gameId = game.gameId ?: error("Failed to persist game")

        val shuffledPlayers = roomPlayers.shuffled()
        val gamePlayers = shuffledPlayers.mapIndexed { idx, rp ->
            GamePlayer(
                gameId = gameId,
                userId = rp.userId,
                seatIndex = idx + 1,
                role = roles[idx],
            )
        }
        gamePlayerRepository.saveAll(gamePlayers)

        room.status = RoomStatus.IN_GAME
        roomRepository.save(room)

        // Notify each player of their private role
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

        return mapOf(
            "gameId" to gameId,
            "phase" to game.phase.name,
            "subPhase" to game.subPhase,
            "dayNumber" to game.dayNumber,
            "sheriffUserId" to game.sheriffUserId,
            "winner" to game.winner?.name,
            "myRole" to myPlayer?.role?.name,
            "players" to players.map { p ->
                mapOf(
                    "userId" to p.userId,
                    "seatIndex" to p.seatIndex,
                    "isAlive" to p.alive,
                    "isSheriff" to p.sheriff,
                    "confirmedRole" to p.confirmedRole,
                    // Only reveal role to the player themselves or after game over
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
        while (roles.size < playerCount) roles.add(PlayerRole.VILLAGER)
        return roles
    }
}
