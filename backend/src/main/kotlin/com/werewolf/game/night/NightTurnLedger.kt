package com.werewolf.game.night

import com.werewolf.model.NightSubPhase
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * One completing action per night turn (game, night, sub-phase). The night loop
 * keeps a sub-phase open for its close-eyes / gap / warm-up delays after the turn
 * completes, so without this a repeat (double tap, network retry, second tab) is
 * accepted again: the witch could use both potions in one night, and the extra
 * signal completes the next role's turn. [NightOrchestrator] clears a game's claims
 * when its next night starts or its night is cancelled.
 */
@Component
class NightTurnLedger {
    private data class Turn(val gameId: Int, val dayNumber: Int, val subPhase: NightSubPhase)

    private val claimed: MutableSet<Turn> = ConcurrentHashMap.newKeySet()

    /** True if this call took the turn; false if it was already taken. */
    fun claim(gameId: Int, dayNumber: Int, subPhase: NightSubPhase): Boolean =
        claimed.add(Turn(gameId, dayNumber, subPhase))

    fun release(gameId: Int, dayNumber: Int, subPhase: NightSubPhase) {
        claimed.remove(Turn(gameId, dayNumber, subPhase))
    }

    fun clear(gameId: Int) {
        claimed.removeIf { it.gameId == gameId }
    }
}
