package com.werewolf.audio

import com.werewolf.model.AudioSequence
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of recent AudioSequence broadcasts per game.
 *
 * Why: Spring's SimpleBroker does not buffer messages for clients that are
 * momentarily offline. When STOMP-JS auto-reconnects after a transient
 * WebSocket close (heartbeat timeout, mobile context-switch, network blip),
 * any AudioSequence broadcast during the disconnect window is permanently
 * lost. The frontend's reconnect recovery path
 * (GameView.refreshState → GET /api/game/{id}/state) carried no audio
 * field, so missed cues never reached the client — players experienced
 * "the witch's open-eyes audio never played on Day 1" as a hard regression
 * even though the backend log showed the broadcast fired.
 *
 * Fix: StompPublisher.broadcastGame intercepts every AudioSequence event
 * and appends to a small per-game ring buffer; GameService.getGameState
 * reads the most-recent entry into the polled-state response (single field
 * to keep the wire shape stable). Frontend dedup (lastPlayedSequenceId)
 * skips already-played sequences so polls do not double-fire audio for
 * still-online clients.
 *
 * The buffer keeps the last [maxEntriesPerGame] frames so future work can
 * surface multiple missed cues if a single field turns out to be too
 * narrow. For the bug shape reported in prod (one role's open-eyes lost
 * during a sub-second blip) [getLatest] is sufficient.
 *
 * Lifecycle: per-game, cleared on GameOver. Memory footprint is bounded
 * by concurrent in-flight games × [maxEntriesPerGame] AudioSequences.
 */
@Component
class AudioReplayCache(
    private val maxEntriesPerGame: Int = DEFAULT_BUFFER_SIZE,
) {
    private val store = ConcurrentHashMap<Int, ArrayDeque<AudioSequence>>()

    fun put(gameId: Int, audioSequence: AudioSequence) {
        val deque = store.computeIfAbsent(gameId) { ArrayDeque(maxEntriesPerGame) }
        synchronized(deque) {
            deque.addLast(audioSequence)
            while (deque.size > maxEntriesPerGame) deque.removeFirst()
        }
    }

    /**
     * Returns the most recently broadcast AudioSequence for this game, or
     * null if nothing has been cached yet (e.g. game just started, no
     * audio frames yet, or the game ended and the cache was cleared).
     */
    fun getLatest(gameId: Int): AudioSequence? {
        val deque = store[gameId] ?: return null
        return synchronized(deque) { deque.lastOrNull() }
    }

    /** Snapshot of all cached frames for diagnostics/tests. */
    fun snapshot(gameId: Int): List<AudioSequence> {
        val deque = store[gameId] ?: return emptyList()
        return synchronized(deque) { deque.toList() }
    }

    fun clear(gameId: Int) {
        store.remove(gameId)
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE: Int = 8
    }
}
