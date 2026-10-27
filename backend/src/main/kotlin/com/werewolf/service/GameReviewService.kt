package com.werewolf.service

import com.werewolf.config.RewardProperties
import com.werewolf.dto.GameReview
import com.werewolf.dto.PlayerReview
import com.werewolf.model.CreditTxType
import com.werewolf.model.Game
import com.werewolf.model.GameEvent
import com.werewolf.model.GamePhase
import com.werewolf.model.GamePlayer
import com.werewolf.model.NightPhase
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
    private val creditTransactionRepository: CreditTransactionRepository,
    private val rewardProperties: RewardProperties,
    private val rewardSettlementService: RewardSettlementService,
) {
    /** The rows both replays are built from, loaded once the caller has been let in. */
    private class ReviewData(
        val game: Game,
        val players: List<GamePlayer>,
        val nights: List<NightPhase>,
        val events: List<GameEvent>,
        val night1PerkSaved: Set<String>,
        val rewards: Map<String, Int>,
        val displayNameFor: (String) -> String,
    )

    /** Either replay lays a game bare, so both open only to the game's own players, and only once it is over. */
    private fun load(gameId: Int, userId: String): ReviewData {
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
        return ReviewData(
            game,
            players,
            nightPhaseRepository.findByGameId(gameId),
            gameEventRepository.findByGameIdOrderByCreatedAtAscIdAsc(gameId),
            night1PerkSaved,
            // Rewards come from the ledger, the durable record, so both replays show the same numbers.
            creditTransactionRepository.findByGameIdAndType(gameId, CreditTxType.GAME_REWARD)
                .associate { it.userId to it.amount },
        ) { id -> roomNames[id]?.displayName ?: users[id]?.nickname ?: id }
    }

    /** Every role and night action, with every player's true role. */
    @Transactional(readOnly = true)
    fun getReview(gameId: Int, userId: String): GameReview = with(load(gameId, userId)) {
        GameReviewBuilder.build(game, players, nights, events, night1PerkSaved, rewards, displayNameFor)
    }

    /** Only what [userId] themselves knew, and only up to the moment they left the game. */
    @Transactional(readOnly = true)
    fun getPlayerReview(gameId: Int, userId: String): PlayerReview = with(load(gameId, userId)) {
        // Recomputed from the same rules settlement used, so the replay can say which
        // action earned what. A cancelled game was never settled and has nothing to show.
        val myItems = game.winner?.let { winner ->
            val me = players.first { it.userId == userId }
            RewardBreakdown.itemsFor(
                me,
                rewardSettlementService.facts(gameId, game.dayNumber, winner, players),
                rewardProperties,
            )
        }.orEmpty()
        PlayerReviewBuilder.build(
            game, players, nights, events, night1PerkSaved, userId, rewards, myItems, displayNameFor,
        )
    }
}
