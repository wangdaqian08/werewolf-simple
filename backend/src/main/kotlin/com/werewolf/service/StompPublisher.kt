package com.werewolf.service

import com.werewolf.game.DomainEvent
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class StompPublisher(
    private val template: SimpMessagingTemplate,
    private val gameStateLogger: GameStateLogger,
) {

    /** Broadcast a public game event to all subscribers of this game. */
    fun broadcastGame(gameId: Int, event: Any) {
        template.convertAndSend("/topic/game/$gameId", event)
        // After every state-change broadcast, fire-and-forget a one-line state
        // snapshot for server-side debugging. logSnapshot is @Async so its DB
        // reads cannot delay the broadcast path. See GameStateLogger.
        stateChangeContext(event)?.let { ctx -> gameStateLogger.logSnapshot(gameId, ctx) }
    }

    /** Broadcast a room event to all subscribers of this room. */
    fun broadcastRoom(roomId: Int, event: Any) {
        template.convertAndSend("/topic/room/$roomId", event)
    }

    /** Send a private message to a specific user (role assignment, seer result, etc.). */
    fun sendPrivate(userId: String, event: Any) {
        template.convertAndSendToUser(userId, "/queue/private", event)
    }

    // ── afterCommit variants ──────────────────────────────────────────────────
    //
    // Use these whenever the broadcast accompanies a write inside an
    // @Transactional method. Without afterCommit the STOMP frame races the DB
    // commit: a recipient that re-reads /api/game/{id}/state from a separate
    // (read) tx can see the *prior* state, which manifests as flaky CI and
    // stuck UIs. Concrete reproductions: PR #67 (GamePhasePipeline.dayAdvance),
    // PR #83 (GameService.startGame, prod game=10), PR #84 (SheriffService
    // SHERIFF_START_SPEECH on CI shard 3 run 25160390283).
    //
    // When called outside an active tx (e.g. from a coroutine that already
    // committed, or non-transactional setup), these fall through to an
    // immediate broadcast — caller does not need to know whether a tx is
    // active.

    fun broadcastGameAfterCommit(gameId: Int, event: Any) {
        runAfterCommitOrNow { broadcastGame(gameId, event) }
    }

    fun broadcastRoomAfterCommit(roomId: Int, event: Any) {
        runAfterCommitOrNow { broadcastRoom(roomId, event) }
    }

    fun sendPrivateAfterCommit(userId: String, event: Any) {
        runAfterCommitOrNow { sendPrivate(userId, event) }
    }

    private inline fun runAfterCommitOrNow(crossinline action: () -> Unit) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }

    /**
     * Map a DomainEvent to a short context label for the state snapshot. Returns
     * null for events that aren't state changes (audio cues, per-vote/per-confirm
     * pings) — these would just produce log noise without changing state.
     */
    private fun stateChangeContext(event: Any): String? = when (event) {
        is DomainEvent.PhaseChanged -> "PHASE=${event.phase}/${event.subPhase ?: "-"}"
        is DomainEvent.NightSubPhaseChanged -> "NIGHT_SUBPHASE=${event.subPhase}"
        is DomainEvent.NightResult -> "NIGHT_RESULT kills=${event.kills.size}"
        is DomainEvent.SheriffElected -> "SHERIFF_ELECTED=${event.sheriffUserId ?: "null"}"
        is DomainEvent.BadgeHandover -> "BADGE=${event.fromUserId}->${event.toUserId ?: "destroyed"}"
        is DomainEvent.PlayerEliminated -> "ELIMINATED=${event.userId}(${event.role})"
        is DomainEvent.HunterShot -> "HUNTER_SHOT=${event.hunterUserId}->${event.targetUserId}"
        is DomainEvent.VoteTally -> "VOTE_TALLY eliminated=${event.eliminatedUserId ?: "null"}"
        is DomainEvent.IdiotRevealed -> "IDIOT_REVEALED=${event.userId}"
        is DomainEvent.GameOver -> "GAME_OVER winner=${event.winner}"
        else -> null  // RoleConfirmed, AudioSequence, OpenEyes, CloseEyes, RoleAction, VoteSubmitted, etc.
    }
}
