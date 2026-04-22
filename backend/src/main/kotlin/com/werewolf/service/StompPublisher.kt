package com.werewolf.service

import com.werewolf.game.DomainEvent
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

@Service
class StompPublisher(
    private val template: SimpMessagingTemplate,
    private val gameStateLogger: GameStateLogger,
) {

    /** Broadcast a public game event to all subscribers of this game. */
    fun broadcastGame(gameId: Int, event: Any) {
        template.convertAndSend("/topic/game/$gameId", event)
        // After every state-change broadcast, emit a one-line state snapshot for
        // server-side debugging. See GameStateLogger for the format.
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
