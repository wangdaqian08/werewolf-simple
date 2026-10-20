package com.werewolf.service

import com.werewolf.dto.GameReview
import com.werewolf.model.GamePhase
import com.werewolf.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class ReviewForbiddenException(message: String) : RuntimeException(message)

@Service
class GameReviewService(
    private val gameRepository: GameRepository,
    private val gamePlayerRepository: GamePlayerRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val userRepository: UserRepository,
    private val nightPhaseRepository: NightPhaseRepository,
    private val gameEventRepository: GameEventRepository,
    private val perkActivationRepository: PerkActivationRepository,
) {
    /** The review reveals every role and night action, so it opens only to the game's players once it is over. */
    @Transactional(readOnly = true)
    fun getReview(gameId: Int, userId: String): GameReview {
        val game = gameRepository.findById(gameId).orElseThrow { GameNotFoundException(gameId) }
        val players = gamePlayerRepository.findByGameId(gameId)
        if (players.none { it.userId == userId }) throw ReviewForbiddenException("Only players of this game can see its review")
        if (game.phase != GamePhase.GAME_OVER) throw ReviewForbiddenException("The review opens when the game is over")

        val users = userRepository.findAllById(players.map { it.userId }).associateBy { it.userId }
        val roomNames = roomPlayerRepository.findByRoomId(game.roomId).associateBy { it.userId }
        // PerkService.markNight1Triggered stamps triggeredAt only when the perk was the decisive night-1 save.
        val night1PerkSaved = perkActivationRepository.findByGameId(gameId)
            .filter { it.perkCode == PERK_NIGHT1_IMMUNITY && it.triggeredAt != null }
            .mapTo(mutableSetOf()) { it.userId }
        return GameReviewBuilder.build(
            game,
            players,
            nightPhaseRepository.findByGameId(gameId),
            gameEventRepository.findByGameIdOrderByCreatedAtAsc(gameId),
            night1PerkSaved,
        ) { id -> roomNames[id]?.displayName ?: users[id]?.nickname ?: id }
    }
}
