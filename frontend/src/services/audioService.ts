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

export type BgmLevel = 'HIGH' | 'LOW'

const MUTE_STORAGE_KEY = 'audio-muted'
const BGM_VOLUME_STORAGE_KEY = 'bgm-volume'

const BGM_GAIN_HIGH = 1.0
const BGM_GAIN_LOW = 0.45
const BGM_GAIN_DUCKED = 0.15
const BGM_RAMP_SEC = 0.15

class AudioService {
  private audioCache = new Map<string, HTMLAudioElement>()
  private globalVolume = 1.0
  private muted = false
  private userInteracted = false
  private audioContext: AudioContext | null = null

  // ── BGM (background music) state ─────────────────────────────────────────
  // BGM uses plain HTMLAudioElement.volume (no Web Audio routing). A
  // requestAnimationFrame-based tween does the 150 ms duck/unduck ramp.
  // Web Audio routing was removed because createMediaElementSource captures
  // the element's output exclusively through the AudioContext, which renders
  // BGM silent until ctx.resume() resolves — easily missed on the first NIGHT.
  private bgmAudioEl: HTMLAudioElement | null = null
  private bgmFilename: string | null = null
  private bgmBaseVolume = 0.5
  private bgmLevel: BgmLevel = 'LOW'
  private bgmNarrationActive = false
  private bgmPendingStart: (() => void) | null = null
  private bgmTweenHandle: number | null = null

  constructor() {
    try {
      this.muted = localStorage.getItem(MUTE_STORAGE_KEY) === 'true'
      const v = localStorage.getItem(BGM_VOLUME_STORAGE_KEY)
      if (v !== null) {
        const parsed = Number.parseFloat(v)
        if (Number.isFinite(parsed)) this.bgmBaseVolume = Math.max(0, Math.min(1, parsed))
      }
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

      // If a BGM start was deferred until first interaction, run it now.
      if (this.bgmPendingStart) {
        const fn = this.bgmPendingStart
        this.bgmPendingStart = null
        try { fn() } catch (e) { console.warn('[AudioService] deferred bgm start failed', e) }
      }
    }

    // Listen for various user interactions
    const events = ['click', 'touchstart', 'keydown', 'mousedown', 'pointerdown']
    events.forEach((eventName) => {
      document.addEventListener(eventName, enableAudio, { capture: true })
    })

    // On returning to foreground, defensively resume the audio context.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && this.audioContext?.state === 'suspended') {
        this.audioContext.resume().catch(() => {/* swallow */})
      }
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
      // Duck BGM BEFORE first play call so narration is immediately in the foreground.
      this.duckBgm()
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
      this.unduckBgm()
      console.log('[AudioService] Queue empty, playback complete')
      return
    }

    this.isPlayingQueue = true
    const { filename, options } = this.audioQueue.shift()!
    console.log(
      `[AudioService] Starting playback: ${filename} (queue remaining: ${this.audioQueue.length})`,
    )

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
    if (this.muted) {
      this.stopAll()
    }
    // Recompute BGM target — applyBgmGain reads `this.muted` and ramps the
    // element's volume to 0 (mute) or to the level-aware target (unmute).
    this.applyBgmGain()
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
    this.stopAllNarration()
    this.isPlayingQueue = false
    this.unduckBgm()
  }

  /**
   * Stop only narration audio elements; never touch the BGM element.
   * Replaces the previous stopAll() semantics in queue-clearing paths.
   */
  private stopAllNarration(): void {
    try {
      for (const audio of this.audioCache.values()) {
        audio.pause()
        audio.currentTime = 0
      }
    } catch (error) {
      console.warn('[AudioService] Error stopping narration:', error)
    }
  }

  /**
   * True when an item is currently playing OR queued and pending.
   * Callers that want to interrupt mid-narrative use this to decide between
   * a "clear-and-replace" hard reset (queue idle) and a "wait-then-play"
   * append (queue still draining role-owned audio).
   */
  isQueueActive(): boolean {
    return this.isPlayingQueue || this.audioQueue.length > 0
  }

  // ── BGM API ──────────────────────────────────────────────────────────────

  /**
   * Start playing a background music track on loop. Idempotent: re-starting
   * the same filename is a no-op while it's already playing. Switching to a
   * different filename stops the previous track first.
   *
   * If the user has not interacted with the page yet (browser autoplay
   * policy), the start is deferred until the first interaction.
   */
  startBgm(filename: string, displayName?: string): void {
    if (!filename) return
    if (this.bgmFilename === filename && this.bgmAudioEl && !this.bgmAudioEl.paused) {
      return
    }
    if (this.bgmFilename && this.bgmFilename !== filename) {
      this.stopBgm()
    }

    if (!this.userInteracted) {
      // Defer until first interaction — exactly one start is queued.
      this.bgmPendingStart = () => this.startBgm(filename, displayName)
      return
    }

    try {
      const url = `/audio/bgm/${encodeURIComponent(filename)}`
      const el = new Audio(url)
      el.loop = true
      el.preload = 'auto'
      // Initial volume: silent if muted, otherwise the level-aware target.
      // Set BEFORE play() so the first frame isn't loud.
      el.volume = this.computeBgmTargetVolume()

      el.onerror = () => {
        console.warn('[AudioService] BGM error, stopping', filename)
        this.stopBgm()
      }

      this.bgmAudioEl = el
      this.bgmFilename = filename

      el.play().catch((err) => {
        console.warn('[AudioService] BGM play() rejected:', err)
      })

      this.applyMediaSession(displayName ?? filename)
    } catch (err) {
      console.warn('[AudioService] startBgm failed:', err)
      this.stopBgm()
    }
  }

  stopBgm(): void {
    this.bgmPendingStart = null
    this.cancelBgmTween()
    try {
      if (this.bgmAudioEl) {
        this.bgmAudioEl.pause()
        this.bgmAudioEl.currentTime = 0
        this.bgmAudioEl.onerror = null
        this.bgmAudioEl.src = ''
      }
    } catch (err) {
      console.warn('[AudioService] stopBgm cleanup error:', err)
    }
    this.bgmAudioEl = null
    this.bgmFilename = null
    this.bgmLevel = 'LOW'
    this.bgmNarrationActive = false
    this.clearMediaSession()
  }

  pauseBgm(): void {
    try { this.bgmAudioEl?.pause() } catch { /* swallow */ }
  }

  resumeBgm(): void {
    try { this.bgmAudioEl?.play().catch(() => {/* swallow */}) } catch { /* swallow */ }
  }

  /**
   * Set the BGM volume tier. The composable should call this in response to
   * sub-phase changes within NIGHT.
   */
  setBgmLevel(level: BgmLevel): void {
    if (this.bgmLevel === level) return
    this.bgmLevel = level
    this.applyBgmGain()
  }

  /** Mark narration as active so BGM stays at DUCKED until unduck. */
  duckBgm(): void {
    if (this.bgmNarrationActive) return
    this.bgmNarrationActive = true
    this.applyBgmGain()
  }

  unduckBgm(): void {
    if (!this.bgmNarrationActive) return
    this.bgmNarrationActive = false
    this.applyBgmGain()
  }

  /** User-facing volume control (0..1), persisted. */
  setBgmVolume(v: number): void {
    const clamped = Math.max(0, Math.min(1, v))
    this.bgmBaseVolume = clamped
    try { localStorage.setItem(BGM_VOLUME_STORAGE_KEY, String(clamped)) } catch { /* swallow */ }
    this.applyBgmGain()
  }

  getBgmVolume(): number {
    return this.bgmBaseVolume
  }

  isBgmPlaying(): boolean {
    return !!this.bgmAudioEl && !this.bgmAudioEl.paused
  }

  /** Compute the target HTMLAudioElement.volume from current state. */
  private computeBgmTargetVolume(): number {
    const multiplier = this.muted
      ? 0
      : this.bgmNarrationActive
        ? BGM_GAIN_DUCKED
        : this.bgmLevel === 'HIGH'
          ? BGM_GAIN_HIGH
          : BGM_GAIN_LOW
    return Math.max(0, Math.min(1, this.bgmBaseVolume * multiplier))
  }

  /**
   * Smoothly tween the BGM element's volume toward the current target over
   * BGM_RAMP_SEC. Uses requestAnimationFrame instead of Web Audio so we don't
   * need an AudioContext (which would require explicit resume() and was the
   * root cause of "BGM only plays from the second night onward").
   */
  private applyBgmGain(): void {
    const el = this.bgmAudioEl
    if (!el) return
    this.cancelBgmTween()
    const target = this.computeBgmTargetVolume()
    const start = el.volume
    if (Math.abs(target - start) < 0.001) {
      el.volume = target
      return
    }
    const startTime = (typeof performance !== 'undefined' ? performance.now() : Date.now())
    const durationMs = BGM_RAMP_SEC * 1000
    const step = (now: number) => {
      const t = Math.min(1, (now - startTime) / durationMs)
      const v = Math.max(0, Math.min(1, start + (target - start) * t))
      if (this.bgmAudioEl) this.bgmAudioEl.volume = v
      if (t < 1 && this.bgmAudioEl) {
        this.bgmTweenHandle = requestAnimationFrame(step)
      } else {
        this.bgmTweenHandle = null
      }
    }
    this.bgmTweenHandle = requestAnimationFrame(step)
  }

  private cancelBgmTween(): void {
    if (this.bgmTweenHandle !== null) {
      cancelAnimationFrame(this.bgmTweenHandle)
      this.bgmTweenHandle = null
    }
  }

  private applyMediaSession(title: string): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    try {
      const ms = (navigator as any).mediaSession
      if (typeof MediaMetadata !== 'undefined') {
        ms.metadata = new MediaMetadata({ title, artist: 'Werewolf Game' })
      }
      ms.setActionHandler?.('play', () => this.resumeBgm())
      ms.setActionHandler?.('pause', () => this.pauseBgm())
    } catch { /* swallow */ }
  }

  private clearMediaSession(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    try {
      const ms = (navigator as any).mediaSession
      ms.metadata = null
      ms.setActionHandler?.('play', null)
      ms.setActionHandler?.('pause', null)
    } catch { /* swallow */ }
  }
}

// Export singleton instance
export const audioService = new AudioService()
