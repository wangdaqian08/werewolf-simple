/**
 * useAudioService - Composable for managing game audio
 *
 * Plays audio based on backend-calculated AudioSequence.
 * Backend determines what audio should play, frontend just plays it.
 */

import { onUnmounted, ref, watch } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import { useRoomStore } from '@/stores/roomStore'
import { audioService } from '@/services/audioService'
import type { AudioSequence } from '@/types'

const HIGH_VOL_NIGHT_SUBPHASES: ReadonlySet<string> = new Set([
  'WEREWOLF_PICK',
  'SEER_PICK',
  'SEER_RESULT',
  'WITCH_ACT',
  'GUARD_PICK',
])

// Bound for the dedup set — keep memory finite even on long games.
// 50 covers ~6 nights' worth of role audio plus init/transition cues with
// margin; older entries roll out of the window and would replay if the
// same id is ever broadcast again (which it never is, ids are timestamped).
const PLAYED_IDS_LIMIT = 50

export function useAudioService() {
  const gameStore = useGameStore()
  const roomStore = useRoomStore()
  const isMuted = ref(audioService.isMuted())
  // Set of AudioSequence ids already played by this composable. Replaces the
  // single lastPlayedSequenceId because the audioReplayBuffer can deliver
  // several missed cues at once (in chronological order), and we need to
  // dedup per-id rather than just against the most recent live frame.
  const playedIds = new Set<string>()

  function tryPlay(seq: AudioSequence, source: 'live' | 'replay'): void {
    if (playedIds.has(seq.id)) {
      console.log(`[useAudioService] Skipping duplicate sequence (${source}, same ID):`, seq.id)
      return
    }
    if (seq.audioFiles.length === 0) return

    console.log(`[useAudioService] AudioSequence (${source}):`, {
      id: seq.id,
      audioFiles: seq.audioFiles,
      phase: seq.phase,
      subPhase: seq.subPhase,
    })

    // Phase-level audio (priority >= 10) is meant to replace the queue —
    // e.g. DAY→NIGHT rooster_crowing should interrupt any lingering night
    // audio. But an unconditional clearQueue() drops role-owned audio that
    // is queued but hasn't started yet — the most common offender:
    // guard_close_eyes.mp3 gets queued ~15ms before the DAY AudioSequence
    // arrives, and clearQueue wipes it before audio.play() ever fires.
    //
    // Fix: only clear when the queue is idle (no harm done). When it is
    // still draining low-priority role audio, APPEND the high-priority
    // files so they play after the role-narrative audio finishes —
    // preserves narrative integrity while still ensuring the high-priority
    // sequence eventually plays.
    //
    // Replay-buffer items are by definition stale — never let them clear
    // the queue, only ever append, otherwise a recovery poll racing a live
    // frame would clobber the live frame.
    const isHighPriority = (seq.priority ?? 0) >= 10
    if (source === 'live' && isHighPriority && !audioService.isQueueActive()) {
      audioService.clearQueue()
    }

    audioService.playSequential(seq.audioFiles)
    playedIds.add(seq.id)
    if (playedIds.size > PLAYED_IDS_LIMIT) {
      // Sets in JS preserve insertion order — drop the oldest id.
      const oldest = playedIds.values().next().value
      if (oldest !== undefined) playedIds.delete(oldest)
    }
  }

  /**
   * Watch audio sequence changes from backend (live STOMP path).
   */
  watch(
    () => gameStore.state?.audioSequence,
    (newSequence) => {
      if (newSequence) tryPlay(newSequence, 'live')
    },
    { deep: true },
  )

  /**
   * Watch audioReplayBuffer changes (HTTP recovery path).
   *
   * The backend's AudioReplayCache surfaces recent AudioSequence frames in
   * the getGameState response so a STOMP-reconnect's refreshState() can
   * replay every cue missed during the disconnect window. Iterate the
   * buffer in chronological order; tryPlay's playedIds dedup ensures we
   * never double-play a frame the live path already handled.
   */
  watch(
    () => gameStore.state?.audioReplayBuffer,
    (buffer) => {
      if (!buffer || buffer.length === 0) return
      for (const seq of buffer) tryPlay(seq, 'replay')
    },
    { deep: true },
  )

  // ── Background music: lifecycle + level modulation ──────────────────────

  // (a) Start/stop BGM on NIGHT phase boundary. immediate:true handles
  //     mid-game reconnect where the player rejoins while already in NIGHT.
  watch(
    () => gameStore.state?.phase,
    (phase) => {
      const track = roomStore.room?.config?.bgmTrack
      if (phase === 'NIGHT' && track) {
        audioService.startBgm(track)
      } else {
        audioService.stopBgm()
      }
    },
    { immediate: true },
  )

  // (b) HIGH/LOW volume tier follows the night sub-phase. Pure modulator —
  //     never starts or stops the element, so it is safe to fire at any time.
  watch(
    () => gameStore.state?.nightPhase?.subPhase,
    (sub) => {
      audioService.setBgmLevel(
        typeof sub === 'string' && HIGH_VOL_NIGHT_SUBPHASES.has(sub) ? 'HIGH' : 'LOW',
      )
    },
    { immediate: true },
  )

  /**
   * Cleanup on unmount
   */
  onUnmounted(() => {
    audioService.stopAll()
    audioService.stopBgm()
  })

  function toggleMute() {
    audioService.toggleMute()
    isMuted.value = audioService.isMuted()
  }

  return {
    isMuted,
    toggleMute,
    setVolume: (volume: number) => audioService.setGlobalVolume(volume),
    getVolume: () => audioService.getGlobalVolume(),
    setBgmVolume: (v: number) => audioService.setBgmVolume(v),
    getBgmVolume: () => audioService.getBgmVolume(),
  }
}
