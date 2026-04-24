/**
 * useAudioService - Composable for managing game audio
 *
 * Plays audio based on backend-calculated AudioSequence.
 * Backend determines what audio should play, frontend just plays it.
 */

import { onUnmounted, ref, watch } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import { audioService } from '@/services/audioService'

export function useAudioService() {
  const gameStore = useGameStore()
  const isMuted = ref(audioService.isMuted())
  const lastPlayedSequenceId = ref<string | null>(null)

  /**
   * Watch audio sequence changes from backend.
   *
   * Priority semantics:
   * - Phase-level audio (priority ≥ 10, e.g. DAY→rooster, NIGHT→goes_dark)
   *   is the "start of a new phase" cue. Historically this called
   *   audioService.clearQueue() to preempt any lingering audio, but that
   *   deterministically dropped the last role's close_eyes clip when night
   *   audio spilled past the coroutine's DAY transition (observed in the
   *   guard-audio-sequence quarantine — `guard_close_eyes.mp3` never logged
   *   "Starting playback" because clearQueue fired first).
   * - The new behaviour: wait (bounded) for the in-flight queue to drain,
   *   then append. This plays every role's close_eyes in order, and the
   *   rooster follows cleanly. A 5 s cap prevents indefinite waiting if the
   *   queue ever has runaway content.
   * - Low-priority sequences (role open/close eyes, dead-role sim) APPEND
   *   as before — they're meant to play sequentially after current audio.
   */
  watch(
    () => gameStore.state?.audioSequence,
    async (newSequence, oldSequence) => {
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

      const isHighPriority = (newSequence.priority ?? 0) >= 10
      if (isHighPriority && audioService.isActive) {
        console.log(
          '[useAudioService] High-priority sequence — waiting for queue to drain before append:',
          newSequence.audioFiles,
        )
        await audioService.waitForIdle(5_000)
      } else {
        console.log(
          `[useAudioService] ${isHighPriority ? 'High' : 'Low'}-priority sequence — appending to queue:`,
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

  /**
   * Cleanup on unmount
   */
  onUnmounted(() => {
    audioService.stopAll()
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
  }
}
