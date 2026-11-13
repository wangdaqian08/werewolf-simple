package com.werewolf.service

import com.werewolf.repository.GameRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

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
 * On startup we scan for any Game with `endedAt IS NULL` and forfeit it through
 * [GameCanceller] (shared with AbandonedGameSweep, which handles live games):
 *   - Game.phase = GAME_OVER, endedAt = now() (winner stays null → "no winner")
 *   - Room.status = WAITING and all RoomPlayer.status = NOT_READY, so the
 *     same lobby can ready up and start a fresh game without anyone having
 *     to recreate the room.
 *   - Broadcast DomainEvent.GameOver so any client still on the GameView
 *     auto-routes to the Result screen instead of staring at a frozen UI.
 *   - RewardSettlementService.settle(gameId, null) runs so perk activations
 *     bound to the forfeited game are refunded (no rewards — winner is null).
 *
 * Operationally: server restart = forfeit the in-flight round. The lobby
 * survives. This is the documented, conservative recovery — it does not
 * attempt to rebuild the orchestrator state machines (that would be a much
 * larger feature; see the README's "don't redeploy mid-game" rule).
 */
@Component
class OrphanedGameRecovery(
    private val gameRepository: GameRepository,
    private val gameCanceller: GameCanceller,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(OrphanedGameRecovery::class.java)
    private val txTemplate = TransactionTemplate(txManager)

    @EventListener(ApplicationReadyEvent::class)
    fun cancelInFlightGames() {
        val orphans = txTemplate.execute { gameRepository.findByEndedAtIsNull() }
        if (orphans.isEmpty()) {
            log.info("[orphan-recovery] no in-flight games to cancel")
            return
        }

        log.warn("[orphan-recovery] cancelling {} in-flight games left over from previous JVM: {}",
            orphans.size, orphans.map { it.gameId })

        for (gameId in orphans.mapNotNull { it.gameId }) {
            try {
                // One transaction per game (inside GameCanceller) so a poisoned row
                // can't take down the whole sweep.
                gameCanceller.cancel(gameId, "restart")
            } catch (e: Exception) {
                // Don't let a single bad row block the rest. Worst case for a
                // failed cancel is the same stuck-forever state we already have.
                log.error("[orphan-recovery] failed to cancel game $gameId", e)
            }
        }
    }
}
