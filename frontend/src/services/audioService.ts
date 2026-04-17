/**
 * Audio Service - Manages audio playback for game phases
 *
 * Features:
 * - Audio instance caching for performance
 * - Global volume control
 * - Loop playback support
 * - Preload functionality
 * - Silent error handling
 */

export interface AudioOptions {
  loop?: boolean
  volume?: number
}

const MUTE_STORAGE_KEY = 'audio-muted'

class AudioService {
  private audioCache = new Map<string, HTMLAudioElement>()
  private globalVolume = 1.0
  private muted = false
  private userInteracted = false
  private audioContext: AudioContext | null = null

  constructor() {
    try {
      this.muted = localStorage.getItem(MUTE_STORAGE_KEY) === 'true'
    } catch {
      // localStorage unavailable (SSR, privacy mode)
    }

    // Track user interaction for autoplay policy
    this.setupUserInteractionTracking()
  }

  private audioQueue: Array<{ filename: string; options: AudioOptions }> = []
  private isPlayingQueue = false

  /**
   * Setup tracking for user interaction to enable audio playback
   * Browsers require user interaction before playing audio
   */
  private setupUserInteractionTracking(): void {
    const enableAudio = () => {
      if (!this.userInteracted) {
        this.userInteracted = true

        // Initialize AudioContext on first user interaction
        if (!this.audioContext) {
          try {
            this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
          } catch (error) {
            console.warn('[AudioService] Failed to initialize AudioContext:', error)
          }
        }
      }
    }

    // Listen for various user interactions
    const events = ['click', 'touchstart', 'keydown', 'mousedown', 'pointerdown']
    events.forEach((eventName) => {
      document.addEventListener(eventName, enableAudio, { once: true, capture: true })
    })
  }

  /**
   * Play an audio file (uses queue system to prevent overlap)
   * @param filename - The audio filename (e.g., 'goes_dark_close_eyes.mp3')
   * @param options - Optional playback settings
   */
  play(filename: string, options: AudioOptions = {}): void {
    if (this.muted) return

    // Add to queue and play (queue will prevent overlap)
    this.playSequential([filename], options)
  }

  /**
   * Play multiple audio files sequentially
   * @param filenames - Array of audio filenames to play in order
   * @param options - Optional playback settings
   */
  playSequential(filenames: string[], options: AudioOptions = {}): void {
    if (this.muted || filenames.length === 0) return

    // Add to queue with options
    filenames.forEach((filename) => {
      this.audioQueue.push({ filename, options })
    })

    // Start playing queue if not already playing
    if (!this.isPlayingQueue) {
      this.playNextInQueue()
    }
  }

  /**
   * Play the next audio in the queue
   * @private
   */
  private playNextInQueue(): void {
    if (this.audioQueue.length === 0) {
      this.isPlayingQueue = false
      console.log('[AudioService] Queue empty, playback complete')
      return
    }

    this.isPlayingQueue = true
    const { filename, options } = this.audioQueue.shift()!
    console.log(`[AudioService] Starting playback: ${filename} (queue remaining: ${this.audioQueue.length})`)

    try {
      const audio = this.getAudio(filename)
      audio.currentTime = 0

      // Apply options
      if (options.loop !== undefined) {
        audio.loop = options.loop
      } else {
        audio.loop = false
      }

      if (options.volume !== undefined) {
        audio.volume = options.volume * this.globalVolume
      } else {
        audio.volume = this.globalVolume
      }

      // Play next audio when current finishes
      audio.onended = () => {
        console.log(`[AudioService] Finished playback: ${filename}`)
        this.playNextInQueue()
      }

      // Check if user has interacted before playing
      if (!this.userInteracted) {
        console.warn('[AudioService] Cannot play audio - user has not interacted with the page yet')
        console.warn('[AudioService] Please click anywhere on the page to enable audio')
        this.playNextInQueue() // Continue to next audio
        return
      }

      // Handle play errors and continue to next
      audio.play().catch((error) => {
        console.warn(`[AudioService] Failed to play ${filename} in queue:`, error)

        // Check if it's an autoplay error
        if (error instanceof Error && error.name === 'NotAllowedError') {
          console.warn('[AudioService] Autoplay prevented by browser policy')
          console.warn('[AudioService] Please click anywhere on the page to enable audio')
        }

        this.playNextInQueue()
      })
    } catch (error) {
      console.warn(`[AudioService] Error playing ${filename} in queue:`, error)
      this.playNextInQueue()
    }
  }

  /**
   * Pause an audio file
   * @param filename - The audio filename
   */
  pause(filename: string): void {
    try {
      const audio = this.audioCache.get(filename)
      if (audio) {
        audio.pause()
      }
    } catch (error) {
      console.warn(`[AudioService] Error pausing ${filename}:`, error)
    }
  }

  /**
   * Stop an audio file (pause and reset)
   * @param filename - The audio filename
   */
  stop(filename: string): void {
    try {
      const audio = this.audioCache.get(filename)
      if (audio) {
        audio.pause()
        audio.currentTime = 0
      }
    } catch (error) {
      console.warn(`[AudioService] Error stopping ${filename}:`, error)
    }
  }

  /**
   * Stop all playing audio
   */
  stopAll(): void {
    try {
      for (const [, audio] of this.audioCache) {
        audio.pause()
        audio.currentTime = 0
      }
    } catch (error) {
      console.warn('[AudioService] Error stopping all audio:', error)
    }
  }

  /**
   * Set global volume (0-1)
   * @param volume - Volume level (0 to 1)
   */
  setGlobalVolume(volume: number): void {
    try {
      this.globalVolume = Math.max(0, Math.min(1, volume))
      // Update all cached audio instances
      for (const audio of this.audioCache.values()) {
        audio.volume = this.globalVolume
      }
    } catch (error) {
      console.warn('[AudioService] Error setting global volume:', error)
    }
  }

  /**
   * Get current global volume
   */
  getGlobalVolume(): number {
    return this.globalVolume
  }

  /**
   * Preload an audio file
   * @param filename - The audio filename to preload
   */
  preload(filename: string): void {
    try {
      this.getAudio(filename)
    } catch (error) {
      console.warn(`[AudioService] Error preloading ${filename}:`, error)
    }
  }

  /**
   * Get or create an audio instance for the given filename
   * @private
   */
  private getAudio(filename: string): HTMLAudioElement {
    // Return cached instance if available
    if (this.audioCache.has(filename)) {
      return this.audioCache.get(filename)!
    }

    // Create new audio instance
    const url = `/audio/${encodeURIComponent(filename)}`
    const audio = new Audio(url)

    // Preload
    audio.preload = 'auto'

    // Set initial volume
    audio.volume = this.globalVolume

    // Cache the instance
    this.audioCache.set(filename, audio)

    return audio
  }

  /**
   * Toggle mute state
   */
  toggleMute(): boolean {
    // Mark user as interacted when they toggle mute
    this.userInteracted = true

    this.muted = !this.muted
    this.persistMute()
    if (this.muted) this.stopAll()
    return this.muted
  }

  /**
   * Check if audio is muted
   */
  isMuted(): boolean {
    return this.muted
  }

  /**
   * Check if audio playback is enabled (user has interacted with the page)
   */
  isAudioEnabled(): boolean {
    return this.userInteracted
  }

  private persistMute(): void {
    try {
      localStorage.setItem(MUTE_STORAGE_KEY, String(this.muted))
    } catch {
      // localStorage unavailable
    }
  }

  /**
   * Clear all cached audio instances (useful for cleanup)
   */
  clearCache(): void {
    this.stopAll()
    this.audioCache.clear()
  }

  /**
   * Clear the audio queue and stop current playback
   */
  clearQueue(): void {
    this.audioQueue = []
    this.stopAll()
    this.isPlayingQueue = false
  }
}

// Export singleton instance
export const audioService = new AudioService()
