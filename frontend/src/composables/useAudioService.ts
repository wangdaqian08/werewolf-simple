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

      // Clear queue to ensure new audio replaces old audio completely
      console.log('[useAudioService] Clearing queue before playing:', newSequence.audioFiles)
      audioService.clearQueue()

      // Play all audio files in sequence
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
