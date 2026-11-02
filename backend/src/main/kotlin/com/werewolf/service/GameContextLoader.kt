package com.werewolf.service

import com.werewolf.game.GameContext
import com.werewolf.model.Game
import com.werewolf.model.GamePhase
import com.werewolf.repository.*
import org.springframework.stereotype.Service

class GameNotFoundException(gameId: Int) : IllegalArgumentException("Game $gameId not found")

@Service
class GameContextLoader(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val roomRepository: RoomRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val sheriffElectionRepository: SheriffElectionRepository,
) {
    fun load(gameId: Int): GameContext = build(
        gameId, gameRepository.findById(gameId).orElseThrow { GameNotFoundException(gameId) }
    )

    /**
     * Same as [load] but locks the game row until the caller's transaction ends, so two
     * actions on one game run one after the other instead of overwriting each other's
     * whole-row saves. Must be called inside a transaction.
     */
    fun loadForUpdate(gameId: Int): GameContext = build(
        gameId, gameRepository.findByIdForUpdate(gameId).orElseThrow { GameNotFoundException(gameId) }
    )

    private fun build(gameId: Int, game: Game): GameContext {
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
