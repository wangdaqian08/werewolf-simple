package com.werewolf.audio

import com.werewolf.game.DomainEvent
import com.werewolf.model.AudioSequence
import com.werewolf.model.GamePhase
import com.werewolf.model.WinnerSide
import com.werewolf.service.GameStateLogger
import com.werewolf.service.StompPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * Unit-level coverage for the STOMP-reconnect AudioSequence recovery path.
 *
 * Why these tests exist
 * ---------------------
 * Production game 19 (witch_open_eyes) and game 20 (seer_open_eyes) showed
 * a single role's open-eyes audio failing to play on the host page despite
 * the backend log proving `broadcastGame ... AudioSequence` fired. Root
 * cause: STOMP-JS auto-reconnects after a transient WebSocket close, but
 * Spring's SimpleBroker drops in-flight broadcasts to disconnected
 * subscribers. The reconnecting client's recovery path
 * (GameView.refreshState → /api/game/{id}/state) carried no audio field,
 * so any cue broadcast during the disconnect window was lost forever.
 *
 * Fix locked in by these tests:
 *   - AudioReplayCache stores the most recent AudioSequence per gameId.
 *   - StompPublisher.broadcastGame side-effects into the cache for every
 *     AudioSequence event (so the cache always reflects the last frame
 *     the broker tried to deliver).
 *   - StompPublisher.broadcastGame clears the cache on GameOver so an
 *     ended game does not leak an unbounded entry per gameId.
 */
class AudioReplayCacheTest {

    @Test
    fun `put then get returns the same audio sequence for that game`() {
        val cache = AudioReplayCache()
        val seq = AudioSequence(
            id = "1-100-witch_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = "WITCH_ACT",
            audioFiles = listOf("witch_open_eyes.mp3"),
        )

        cache.put(1, seq)

        assertThat(cache.getLatest(1)).isSameAs(seq)
    }

    @Test
    fun `put overwrites prior entry for the same game`() {
        val cache = AudioReplayCache()
        val older = AudioSequence(
            id = "1-100-wolf_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("wolf_open_eyes.mp3"),
        )
        val newer = AudioSequence(
            id = "1-200-wolf_close_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("wolf_close_eyes.mp3"),
        )

        cache.put(1, older)
        cache.put(1, newer)

        assertThat(cache.getLatest(1)).isSameAs(newer)
    }

    @Test
    fun `entries are isolated per gameId`() {
        val cache = AudioReplayCache()
        val a = AudioSequence(
            id = "1-100-witch_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("witch_open_eyes.mp3"),
        )
        val b = AudioSequence(
            id = "2-100-seer_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("seer_open_eyes.mp3"),
        )

        cache.put(1, a)
        cache.put(2, b)

        assertThat(cache.getLatest(1)).isSameAs(a)
        assertThat(cache.getLatest(2)).isSameAs(b)
    }

    @Test
    fun `clear removes only the targeted game`() {
        val cache = AudioReplayCache()
        val a = AudioSequence(
            id = "1-100-witch_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("witch_open_eyes.mp3"),
        )
        val b = AudioSequence(
            id = "2-100-seer_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("seer_open_eyes.mp3"),
        )
        cache.put(1, a)
        cache.put(2, b)

        cache.clear(1)

        assertThat(cache.getLatest(1)).isNull()
        assertThat(cache.getLatest(2)).isSameAs(b)
    }

    @Test
    fun `get on unknown gameId returns null`() {
        assertThat(AudioReplayCache().getLatest(999)).isNull()
    }

    @Test
    fun `buffer keeps the most recent N entries`() {
        val cache = AudioReplayCache(maxEntriesPerGame = 3)
        val a = AudioSequence(id = "1-100-a.mp3", phase = GamePhase.NIGHT, subPhase = null, audioFiles = listOf("a.mp3"))
        val b = AudioSequence(id = "1-200-b.mp3", phase = GamePhase.NIGHT, subPhase = null, audioFiles = listOf("b.mp3"))
        val c = AudioSequence(id = "1-300-c.mp3", phase = GamePhase.NIGHT, subPhase = null, audioFiles = listOf("c.mp3"))
        val d = AudioSequence(id = "1-400-d.mp3", phase = GamePhase.NIGHT, subPhase = null, audioFiles = listOf("d.mp3"))

        cache.put(1, a); cache.put(1, b); cache.put(1, c); cache.put(1, d)

        // Oldest (a) evicted; buffer holds last 3 in insertion order.
        assertThat(cache.snapshot(1).map { it.id })
            .containsExactly(b.id, c.id, d.id)
        assertThat(cache.getLatest(1)).isSameAs(d)
    }

    @Test
    fun `StompPublisher caches AudioSequence side-effect on broadcastGame`() {
        val template: SimpMessagingTemplate = mock()
        val logger: GameStateLogger = mock()
        val cache = AudioReplayCache()
        val publisher = StompPublisher(template, logger, cache)

        val seq = AudioSequence(
            id = "1-100-witch_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = "WITCH_ACT",
            audioFiles = listOf("witch_open_eyes.mp3"),
        )

        publisher.broadcastGame(1, DomainEvent.AudioSequence(1, seq))

        // Cache is populated so a subsequent getGameState can recover the
        // missed cue for a reconnecting client.
        assertThat(cache.getLatest(1)).isSameAs(seq)
        // Broadcast still happens — caching is purely a side-effect.
        verify(template).convertAndSend(eq("/topic/game/1"), any<Any>())
    }

    @Test
    fun `StompPublisher clears cache on GameOver broadcast`() {
        val template: SimpMessagingTemplate = mock()
        val logger: GameStateLogger = mock()
        val cache = AudioReplayCache()
        val publisher = StompPublisher(template, logger, cache)

        cache.put(
            1,
            AudioSequence(
                id = "1-100-guard_close_eyes.mp3",
                phase = GamePhase.NIGHT,
                subPhase = null,
                audioFiles = listOf("guard_close_eyes.mp3"),
            ),
        )

        publisher.broadcastGame(1, DomainEvent.GameOver(1, WinnerSide.WEREWOLF))

        assertThat(cache.getLatest(1)).isNull()
    }

    @Test
    fun `StompPublisher does not touch cache for unrelated events`() {
        val template: SimpMessagingTemplate = mock()
        val logger: GameStateLogger = mock()
        val cache = AudioReplayCache()
        val publisher = StompPublisher(template, logger, cache)

        val pristine = AudioSequence(
            id = "1-100-wolf_open_eyes.mp3",
            phase = GamePhase.NIGHT,
            subPhase = null,
            audioFiles = listOf("wolf_open_eyes.mp3"),
        )
        cache.put(1, pristine)

        publisher.broadcastGame(
            1,
            DomainEvent.PhaseChanged(1, GamePhase.NIGHT, "WEREWOLF_PICK"),
        )

        // Phase change is not an audio event and not a game-over — cache
        // stays exactly as it was.
        assertThat(cache.getLatest(1)).isSameAs(pristine)
    }
}
