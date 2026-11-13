package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.model.GamePhase
import com.werewolf.model.ReadyStatus
import com.werewolf.model.RoomStatus
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Ends an in-flight game with no winner, in one transaction:
 *   - Game.phase = GAME_OVER, endedAt = now() (winner stays null → "比賽取消")
 *   - RewardSettlementService.settle(gameId, null): no rewards, perk activations refunded
 *   - Room.status = WAITING, every guest NOT_READY and every role-pick cleared, so the
 *     same lobby can start a fresh game
 *   - DomainEvent.GameOver broadcast so a client still on the GameView moves to the result.
 * Used by OrphanedGameRecovery (games a restart interrupted) and AbandonedGameSweep (live
 * games nobody touched for hours). Callers stop any in-memory jobs for a live game first.
 */
@Service
class GameCanceller(
    private val gameRepository: GameRepository,
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val stompPublisher: StompPublisher,
    private val rewardSettlementService: RewardSettlementService,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(GameCanceller::class.java)
    private val txTemplate = TransactionTemplate(txManager)

    /** False when the game is unknown or already ended (raced with another end path). */
    fun cancel(gameId: Int, reason: String): Boolean = txTemplate.execute {
        // Row lock: a live game may be mid-action; wait for it rather than overwrite it.
        val game = gameRepository.findByIdForUpdate(gameId).orElse(null) ?: return@execute false
        if (game.endedAt != null) return@execute false

        val previousPhase = game.phase
        game.phase = GamePhase.GAME_OVER
        game.endedAt = LocalDateTime.now()
        gameRepository.save(game)

        rewardSettlementService.settle(gameId, null)

        roomRepository.findById(game.roomId).ifPresent { room ->
            room.status = RoomStatus.WAITING
            roomRepository.save(room)
            val roomId = room.roomId ?: return@ifPresent
            roomPlayerRepository.findByRoomId(roomId).forEach { rp ->
                if (!rp.host) rp.status = ReadyStatus.NOT_READY
                rp.rolePick = null
                roomPlayerRepository.save(rp)
            }
        }

        log.warn("[game-cancel] game={} cancelled ({}), was phase={} — broadcasting GameOver", gameId, reason, previousPhase)
        stompPublisher.broadcastGame(gameId, DomainEvent.GameOver(gameId, winner = null))
        true
    } ?: false
}
