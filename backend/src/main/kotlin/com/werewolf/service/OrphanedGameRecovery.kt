package com.werewolf.service

import com.werewolf.game.DomainEvent
import com.werewolf.model.GamePhase
import com.werewolf.model.ReadyStatus
import com.werewolf.model.RoomStatus
import com.werewolf.repository.GameRepository
import com.werewolf.repository.RoomPlayerRepository
import com.werewolf.repository.RoomRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/**
 * Cancels any games that were still in-flight when the JVM last shut down.
 *
 * The night/voting/sheriff orchestrators keep state in memory only
 * (CompletableDeferreds, coroutine jobs, the WerewolfHandler kill lock).
 * A backend restart wipes that state, but the DB rows survive — so without
 * intervention, a recipient that re-issues a WOLF_KILL after restart hits
 * GameController.action() → submitAction(), which queues a signal nothing
 * ever consumes, and the game hangs forever. Reproduced 2026-04-29 game=10
 * after a v0.3.0 redeploy.
 *
 * On startup we scan for any Game with `endedAt IS NULL` and forfeit it:
 *   - Game.phase = GAME_OVER, endedAt = now() (winner stays null → "no winner")
 *   - Room.status = WAITING and all RoomPlayer.status = NOT_READY, so the
 *     same lobby can ready up and start a fresh game without anyone having
 *     to recreate the room.
 *   - Broadcast DomainEvent.GameOver so any client still on the GameView
 *     auto-routes to the Result screen instead of staring at a frozen UI.
 *
 * Operationally: server restart = forfeit the in-flight round. The lobby
 * survives. This is the documented, conservative recovery — it does not
 * attempt to rebuild the orchestrator state machines (that would be a much
 * larger feature; see the README's "don't redeploy mid-game" rule).
 */
@Component
class OrphanedGameRecovery(
    private val gameRepository: GameRepository,
    private val roomRepository: RoomRepository,
    private val roomPlayerRepository: RoomPlayerRepository,
    private val stompPublisher: StompPublisher,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(OrphanedGameRecovery::class.java)

    // One TransactionTemplate per cancellation so a poisoned row can't take down
    // the whole sweep. Spring's @Transactional self-invocation gotcha would
    // otherwise leave each save() running in its own implicit auto-tx and lose
    // atomicity for the (game, room, players) triple.
    private val txTemplate = TransactionTemplate(txManager)

    @EventListener(ApplicationReadyEvent::class)
    fun cancelInFlightGames() {
        val orphans = txTemplate.execute { gameRepository.findByEndedAtIsNull() } ?: emptyList()
        if (orphans.isEmpty()) {
            log.info("[orphan-recovery] no in-flight games to cancel")
            return
        }

        log.warn("[orphan-recovery] cancelling {} in-flight games left over from previous JVM: {}",
            orphans.size, orphans.map { it.gameId })

        for (gameId in orphans.mapNotNull { it.gameId }) {
            try {
                txTemplate.execute { cancelOne(gameId) }
            } catch (e: Exception) {
                // Don't let a single bad row block the rest. Worst case for a
                // failed cancel is the same stuck-forever state we already have.
                log.error("[orphan-recovery] failed to cancel game $gameId", e)
            }
        }
    }

    private fun cancelOne(gameId: Int) {
        val game = gameRepository.findById(gameId).orElse(null) ?: return
        if (game.endedAt != null) return // raced: already ended by something else

        val previousPhase = game.phase
        game.phase = GamePhase.GAME_OVER
        game.endedAt = LocalDateTime.now()
        // game.winner stays null — frontend ResultView already shows the no-winner
        // case (outcomeTitle "比赛取消 / Game Cancelled") so players don't think
        // someone won.
        gameRepository.save(game)

        roomRepository.findById(game.roomId).ifPresent { room ->
            room.status = RoomStatus.WAITING
            roomRepository.save(room)
            val roomId = room.roomId ?: return@ifPresent
            // Reset every seated player's ready flag — a fresh game requires a
            // fresh ready check, mirroring the post-game flow.
            roomPlayerRepository.findByRoomId(roomId).forEach { rp ->
                if (!rp.host && rp.status != ReadyStatus.NOT_READY) {
                    rp.status = ReadyStatus.NOT_READY
                    roomPlayerRepository.save(rp)
                }
            }
        }

        log.warn("[orphan-recovery] game=$gameId cancelled (was phase=$previousPhase) — broadcasting GameOver")
        // Broadcast on the topic the GameView is subscribed to so any client
        // still on the page auto-navigates to the Result screen.
        stompPublisher.broadcastGame(gameId, DomainEvent.GameOver(gameId, winner = null))
    }
}
