package com.werewolf.service

import com.werewolf.game.GameContext
import com.werewolf.model.GamePhase
import com.werewolf.repository.*
import org.springframework.stereotype.Service

@Service
class GameContextLoader(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val roomRepository: RoomRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
) {
    fun load(gameId: Int): GameContext {
        val game = gameRepository.findById(gameId)
            .orElseThrow { IllegalArgumentException("Game $gameId not found") }
        val players = gamePlayerRepository.findByGameId(gameId)
        val room = roomRepository.findById(game.roomId)
            .orElseThrow { IllegalArgumentException("Room ${game.roomId} not found") }

        val nightPhase = if (game.phase == GamePhase.NIGHT)
            nightPhaseRepository.findByGameIdAndDayNumber(gameId, game.dayNumber).orElse(null)
        else null

        val election = if (game.phase == GamePhase.SHERIFF_ELECTION)
            sheriffElectionRepository.findByGameId(gameId).orElse(null)
        else null

        val allNightPhases = nightPhaseRepository.findByGameId(gameId)

        return GameContext(game, room, players, nightPhase, election, allNightPhases)
    }
}
