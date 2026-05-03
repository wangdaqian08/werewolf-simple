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

const HIGH_VOL_NIGHT_SUBPHASES: ReadonlySet<string> = new Set([
  'WEREWOLF_PICK',
  'SEER_PICK',
  'SEER_RESULT',
  'WITCH_ACT',
  'GUARD_PICK',
])

export function useAudioService() {
  const gameStore = useGameStore()
  const roomStore = useRoomStore()
  const isMuted = ref(audioService.isMuted())
  const lastPlayedSequenceId = ref<string | null>(null)

  /**
   * Watch audio sequence changes from backend
   */
  watch(
    () => gameStore.state?.audioSequence,
    (newSequence, oldSequence) => {
      if (!newSequence) return

      // Log all audio sequence changes for debugging
      console.log('[useAudioService] AudioSequence changed:', {
        oldId: oldSequence?.id,
        newId: newSequence.id,
        audioFiles: newSequence.audioFiles,
        phase: newSequence.phase,
        subPhase: newSequence.subPhase,
      })

      // Prevent duplicate playback of the same sequence
      if (newSequence.id === lastPlayedSequenceId.value) {
        console.log('[useAudioService] Skipping duplicate sequence (same ID):', newSequence.id)
        return
      }

      // Phase-level audio (priority >= 10) is meant to replace the queue —
      // e.g. DAY→NIGHT rooster_crowing should interrupt any lingering night
      // audio. But an unconditional clearQueue() drops role-owned audio that
      // is queued but hasn't started yet — the most common offender:
      // guard_close_eyes.mp3 gets queued ~15ms before the DAY AudioSequence
      // arrives, and clearQueue wipes it before audio.play() ever fires.
      // Result: the audible role-narrative ("all roles done → day breaks")
      // is silently truncated.
      //
      // Fix: only clear when the queue is idle (no harm done). When it is
      // still draining low-priority role audio, APPEND the high-priority
      // files so they play after the role-narrative audio finishes —
      // preserves narrative integrity while still ensuring the high-priority
      // sequence eventually plays.
      const isHighPriority = (newSequence.priority ?? 0) >= 10
      if (isHighPriority) {
        if (audioService.isQueueActive()) {
          console.log(
            '[useAudioService] High-priority sequence — queue active, appending after current items:',
            newSequence.audioFiles,
          )
        } else {
          console.log(
            '[useAudioService] High-priority sequence — clearing idle queue:',
            newSequence.audioFiles,
          )
          audioService.clearQueue()
        }
      } else {
        console.log(
          '[useAudioService] Low-priority sequence — appending to queue:',
          newSequence.audioFiles,
        )
      }

      // Play (or append) all audio files in sequence
      if (newSequence.audioFiles.length > 0) {
        console.log('[useAudioService] Playing audio files:', newSequence.audioFiles)
        audioService.playSequential(newSequence.audioFiles)
        lastPlayedSequenceId.value = newSequence.id
      }
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
