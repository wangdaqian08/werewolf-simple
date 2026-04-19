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

      // Phase-level audio (priority >= 10) replaces the queue — e.g. DAY→NIGHT rooster
      // must interrupt any lingering night audio. Lower-priority sequences (role open/
      // close eyes, dead-role sim) APPEND to the queue so they play sequentially after
      // whatever is currently playing. This is what guarantees wolf_open_eyes.mp3
      // never overlaps with goes_dark_close_eyes.mp3 or wolf_howl.mp3, even if the
      // backend's NIGHT_INIT_AUDIO_DELAY_MS timing underestimates the combined duration.
      const isHighPriority = (newSequence.priority ?? 0) >= 10
      if (isHighPriority) {
        console.log(
          '[useAudioService] High-priority sequence — clearing queue:',
          newSequence.audioFiles,
        )
        audioService.clearQueue()
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
