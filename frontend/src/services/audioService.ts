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
// Absolute BGM volume during narration cues. Capped at the unducked target so
// users listening at < 10% never get loudened mid-cue.
const BGM_DUCK_ABSOLUTE = 0.1
const BGM_RAMP_SEC = 0.15

// A queue is "stuck" only if no cue has STARTED for this long — a hung play()
// promise or AudioContext suspended in a background tab. Used by both the
// playSequential watchdog and the tab-resume drain so a healthy, progressing
// queue is never discarded.
const STUCK_QUEUE_MS = 15_000

/**
 * Static narration pool, primed (muted play+pause) inside the first user
 * gesture. iOS Safari removes its autoplay restriction PER ELEMENT on the
 * element's first gestured play() — page-level interaction history is not
 * enough — so cues pushed later over STOMP would otherwise be denied on
 * every night. Mirrors backend/src/main/resources/static/audio/ minus the
 * unreferenced crow_night.mp3; a cue missing from this list still works via
 * the park-and-resume path, it just needs one extra tap on iOS.
 */
export const KNOWN_NARRATION_FILES: readonly string[] = [
  'goes_dark_close_eyes.mp3',
  'wolf_howl.mp3',
  'wolf_open_eyes.mp3',
  'wolf_close_eyes.mp3',
  'witch_open_eyes.mp3',
  'witch_close_eyes.mp3',
  'seer_open_eyes.mp3',
  'seer_close_eyes.mp3',
  'guard_open_eyes.mp3',
  'guard_close_eyes.mp3',
  'rooster_crowing.mp3',
  'day_time.mp3',
  'countdown_warning.mp3',
]

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
  private lastPlaybackStartTime = 0
  // Narration blocked by the autoplay policy waits here for a user gesture
  // (the narration counterpart of bgmPendingStart, PR #119).
  private pendingGestureResume = false
  private primedFiles = new Set<string>()
  private currentAudio: HTMLAudioElement | null = null

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

      // Bless the narration pool while we are inside a genuine input
      // handler — the only context iOS accepts for a first play().
      this.primeNarrationPool()

      // Resume narration parked by the autoplay policy. playNextInQueue's
      // play() runs synchronously inside this input handler, which is
      // exactly the context iOS/Firefox require.
      if (this.pendingGestureResume) {
        this.pendingGestureResume = false
        if (!this.muted && this.audioQueue.length > 0 && !this.isPlayingQueue) {
          this.duckBgm()
          this.playNextInQueue()
        }
      }

      // If a BGM start was deferred until first interaction, run it now.
      if (this.bgmPendingStart) {
        const fn = this.bgmPendingStart
        this.bgmPendingStart = null
        try {
          fn()
        } catch (e) {
          console.warn('[AudioService] deferred bgm start failed', e)
        }
      }
    }

    // Listen for various user interactions
    const events = ['click', 'touchstart', 'keydown', 'mousedown', 'pointerdown']
    events.forEach((eventName) => {
      document.addEventListener(eventName, enableAudio, { capture: true })
    })

    // On returning to foreground: resume the audio context, then drain any queue
    // that got stuck during suspension. Do NOT replay stale narration — playing
    // audio that other players already heard would confuse the resumed player.
    // PR #113's stuck-queue watchdog (in playSequential) remains as the backstop.
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return
      if (this.audioContext?.state === 'suspended') {
        this.audioContext.resume().catch(() => {
          /* swallow */
        })
      }
      // Drain queue stuck from suspension. Do NOT replay — playing stale narration
      // on a freshly-woken device duplicates what other players already heard
      // (the action is over). Just reset state so subsequent live STOMP cues play.
      //
      // Only drain a GENUINELY STUCK queue (no cue has started for >STUCK_QUEUE_MS).
      // A queue that is merely actively playing must NOT be discarded: a resume
      // that coincides with a fresh phase cue queued behind the still-playing one
      // (game 80 day-2: day_time/rooster_crowing queued behind seer_close_eyes)
      // would otherwise be wrongly dropped, leaving the day silent.
      if (this.isPlayingQueue && performance.now() - this.lastPlaybackStartTime > STUCK_QUEUE_MS) {
        console.warn('[AudioService] Tab resumed with stuck queue — draining without replay')
        this.audioQueue = []
        this.isPlayingQueue = false
        // The queue drained mid-narration without firing the unduck path in
        // playNextInQueue's "queue empty" branch. Restore BGM gain so the
        // music isn't left clamped at the duck level forever.
        this.unduckBgm()
      }
      // Mobile Chrome / iOS pause the BGM <audio> element implicitly when the
      // screen locks or the tab backgrounds. Narration recovers because each
      // cue calls play() fresh on a new (or cached + user-unlocked) element;
      // BGM does not, since it's a single long-lived element. Re-issue play()
      // here so the music resumes on unlock without waiting for a manual tap.
      if (this.bgmAudioEl && this.bgmFilename && this.bgmAudioEl.paused && !this.muted) {
        const bgmFilename = this.bgmFilename
        this.bgmAudioEl.play().catch(() => {
          // If the resume itself needs user-activation (no recent gesture),
          // re-arm pendingStart so the next click retries — same recovery
          // path as the startBgm rejection branch above.
          this.bgmPendingStart = () => this.startBgm(bgmFilename)
        })
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

    // Stuck-queue self-heal. Documented failure (see stopAll() comment): if
    // a prior playback's onended never fires (paused mid-stream, AudioContext
    // suspended in background tab, play() promise hung), isPlayingQueue stays
    // true forever and every subsequent playSequential is silently dropped
    // because of the !isPlayingQueue gate. Detect via wall-clock: if we are
    // "playing" but no item has started for >15s, the queue is stuck — drain
    // and reset so this call gets to play.
    if (this.isPlayingQueue && performance.now() - this.lastPlaybackStartTime > STUCK_QUEUE_MS) {
      console.warn(
        '[AudioService] Stuck queue detected (no playback start in >15s) — force-recovering',
        { queueLen: this.audioQueue.length, lastStart: this.lastPlaybackStartTime },
      )
      this.audioQueue = []
      this.isPlayingQueue = false
      // Note: we deliberately do NOT call unduckBgm() here. The watchdog only
      // fires from playSequential, which immediately re-enqueues narration
      // and re-ducks. The visibility-resume drain (in setupUserInteractionTracking)
      // does call unduckBgm because no new narration is guaranteed to follow.
    }

    // A parked backlog is stale by definition (same philosophy as the
    // tab-resume drain: never replay narration other players already
    // heard). Keep only the newest sequence for the eventual gesture-resume.
    if (this.pendingGestureResume && !this.isPlayingQueue && this.audioQueue.length > 0) {
      console.warn('[AudioService] Replacing parked narration with a newer sequence', {
        dropped: this.audioQueue.length,
      })
      this.audioQueue = []
    }

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
      this.currentAudio = null
      this.unduckBgm()
      console.log('[AudioService] Queue empty, playback complete')
      return
    }

    this.isPlayingQueue = true
    const item = this.audioQueue.shift()!
    const { filename, options } = item

    try {
      const audio = this.getAudio(filename)
      audio.currentTime = 0
      // Pool priming leaves elements muted for a tick; real playback must
      // always be audible.
      audio.muted = false

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

      // No interaction yet: the browser would deny play() anyway. Park the
      // sequence for the first gesture instead of consuming it.
      if (!this.userInteracted) {
        console.warn('[AudioService] Cannot play audio - user has not interacted with the page yet')
        this.parkQueue(item)
        return
      }

      console.log(
        `[AudioService] Starting playback: ${filename} (queue remaining: ${this.audioQueue.length})`,
      )

      // Record start time for stuck-queue watchdog in playSequential.
      this.lastPlaybackStartTime = performance.now()
      this.currentAudio = audio

      audio.play().catch((error) => {
        // Autoplay denial (iOS per-element gesture rule, Chrome/Firefox
        // before first interaction): park and retry on the next gesture —
        // dropping here is what made whole nights silently mute on phones.
        if (error instanceof Error && error.name === 'NotAllowedError') {
          console.warn(
            '[AudioService] Autoplay prevented by browser policy — parking narration for the next gesture',
          )
          this.parkQueue(item)
          return
        }

        // Anything else (decode error, missing file): skip this cue and
        // keep the sequence moving.
        console.warn(`[AudioService] Failed to play ${filename} in queue:`, error)
        this.playNextInQueue()
      })
    } catch (error) {
      console.warn(`[AudioService] Error playing ${filename} in queue:`, error)
      this.playNextInQueue()
    }
  }

  /**
   * Put a blocked item back at the queue head and wait for a user gesture
   * (the interaction tracker resumes the queue inside its handler). The
   * narration counterpart of bgmPendingStart: before this existed, blocked
   * cues were consumed one by one — a whole night could drain silently, and
   * the first cue to arrive after a stray tap played out of context (e.g.
   * wolf_close_eyes blaring from the wolf's phone during 狼人闭眼).
   */
  private parkQueue(item: { filename: string; options: AudioOptions }): void {
    this.audioQueue.unshift(item)
    this.isPlayingQueue = false
    this.currentAudio = null
    this.pendingGestureResume = true
    console.warn(
      `[AudioService] Narration parked (autoplay blocked) — ${this.audioQueue.length} cue(s) awaiting a user gesture`,
    )
  }

  /**
   * Muted play()+pause() inside a user gesture removes the element-level
   * autoplay restriction (iOS Safari blesses each HTMLAudioElement on its
   * first gestured play; page-level interaction is not enough). Primes the
   * static cue pool plus anything queued, except the queue head — the
   * gesture-resume real-plays that one in the same handler. A rejected
   * prime is retried on a later gesture.
   */
  private primeNarrationPool(): void {
    const queued = this.audioQueue.map((q) => q.filename)
    const head = queued[0]
    let primed = 0
    for (const filename of new Set([...KNOWN_NARRATION_FILES, ...queued])) {
      if (this.primedFiles.has(filename) || filename === head) continue
      try {
        const el = this.getAudio(filename)
        if (el === this.currentAudio) continue
        el.muted = true
        this.primedFiles.add(filename)
        primed += 1
        el.play()
          .then(() => {
            // Never yank an element that became the live cue meanwhile.
            if (el !== this.currentAudio) {
              el.pause()
              el.currentTime = 0
            }
            el.muted = false
          })
          .catch(() => {
            el.muted = false
            this.primedFiles.delete(filename)
          })
      } catch {
        /* priming is best-effort; the park-resume path still covers playback */
      }
    }
    if (primed > 0) {
      console.log(`[AudioService] Primed ${primed} narration file(s) during user gesture`)
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
   * Stop all playing audio AND drain the queue / reset queue state.
   *
   * Pausing a mid-narration HTMLAudioElement does NOT fire its `onended`
   * handler, so [playNextInQueue] is never called and `isPlayingQueue`
   * would otherwise stay stuck at true. The next [playSequential] then
   * queues files but skips processing because of its `!isPlayingQueue`
   * gate, leaving every subsequent narration silent forever.
   *
   * Reproduced when a game ends mid-narration: GameView unmount fires
   * useAudioService.onUnmounted → stopAll() while witch_close_eyes is
   * still playing → next game's NIGHT init audio is queued but never
   * starts. Surface symptom is the host hearing nothing for the entire
   * second game.
   */
  stopAll(): void {
    try {
      for (const [, audio] of this.audioCache) {
        audio.pause()
        audio.currentTime = 0
      }
      this.audioQueue = []
      this.isPlayingQueue = false
      this.pendingGestureResume = false
      this.currentAudio = null
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
    this.notifyMuteListeners()
    return this.muted
  }

  /**
   * Set mute state to a specific value.
   * No-op if the value is already set.
   */
  setMuted(value: boolean): void {
    if (this.muted === value) return
    this.muted = value
    this.persistMute()
    if (this.muted) {
      this.stopAll()
    }
    this.applyBgmGain()
    this.notifyMuteListeners()
  }

  /**
   * Check if audio is muted
   */
  isMuted(): boolean {
    return this.muted
  }

  // Mute-change subscription. VolumeControl and similar UI read `muted` once
  // at mount; without this, a later setMuted call (e.g. the host-aware default
  // in GameView, which fires only after gameStore.hostId arrives via HTTP)
  // would update audioService state but leave the icon stuck on the initial
  // value. Subscribe in setup, unsubscribe on unmount.
  private muteListeners = new Set<(muted: boolean) => void>()

  onMuteChange(listener: (muted: boolean) => void): () => void {
    this.muteListeners.add(listener)
    return () => {
      this.muteListeners.delete(listener)
    }
  }

  private notifyMuteListeners(): void {
    for (const fn of this.muteListeners) fn(this.muted)
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
    this.pendingGestureResume = false
    this.currentAudio = null
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
        // Mobile Chrome rejects autoplay if no user-activation is on the
        // document at the moment play() is called — which is exactly the
        // case for BGM, since startBgm fires from a STOMP phase change, not
        // a user click. Re-arm bgmPendingStart so the very next gesture
        // (any click / touch / keydown via setupUserInteractionTracking)
        // retries the start. Keeps retrying until one sticks.
        console.warn('[AudioService] BGM play() rejected — will retry on next user gesture', err)
        this.bgmPendingStart = () => this.startBgm(filename, displayName)
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
    try {
      this.bgmAudioEl?.pause()
    } catch {
      /* swallow */
    }
  }

  resumeBgm(): void {
    try {
      this.bgmAudioEl?.play().catch(() => {
        /* swallow */
      })
    } catch {
      /* swallow */
    }
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
    try {
      localStorage.setItem(BGM_VOLUME_STORAGE_KEY, String(clamped))
    } catch {
      /* swallow */
    }
    this.applyBgmGain()
  }

  getBgmVolume(): number {
    return this.bgmBaseVolume
  }

  isBgmPlaying(): boolean {
    return !!this.bgmAudioEl && !this.bgmAudioEl.paused
  }

  /**
   * E2E-only: snapshot the current BGM element state.
   * `new Audio(url)` creates an in-memory HTMLMediaElement that is NOT
   * attached to the document, so DOM queries can't see it. This getter
   * returns what a test-author would otherwise look for.
   */
  getBgmState(): {
    exists: boolean
    paused: boolean
    volume: number
    loop: boolean
    src: string
    filename: string | null
    userInteracted: boolean
    pendingStart: boolean
  } {
    const el = this.bgmAudioEl
    return {
      exists: !!el,
      paused: el?.paused ?? true,
      volume: el?.volume ?? 0,
      loop: el?.loop ?? false,
      src: el?.src ?? '',
      filename: this.bgmFilename,
      userInteracted: this.userInteracted,
      pendingStart: !!this.bgmPendingStart,
    }
  }

  /** Compute the target HTMLAudioElement.volume from current state. */
  private computeBgmTargetVolume(): number {
    if (this.muted) return 0
    const levelMult = this.bgmLevel === 'HIGH' ? BGM_GAIN_HIGH : BGM_GAIN_LOW
    const unducked = this.bgmBaseVolume * levelMult
    if (this.bgmNarrationActive) {
      // Absolute target during cue narration; never louder than what the
      // user picked unducked (otherwise a 5%-listener would hear the BGM
      // jump UP to 10% during cues).
      return Math.max(0, Math.min(unducked, BGM_DUCK_ABSOLUTE))
    }
    return Math.max(0, Math.min(1, unducked))
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
    const startTime = typeof performance !== 'undefined' ? performance.now() : Date.now()
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
    } catch {
      /* swallow */
    }
  }

  private clearMediaSession(): void {
    if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
    try {
      const ms = (navigator as any).mediaSession
      ms.metadata = null
      ms.setActionHandler?.('play', null)
      ms.setActionHandler?.('pause', null)
    } catch {
      /* swallow */
    }
  }
}

// Export singleton instance
export const audioService = new AudioService()

// E2E hook: expose to the browser context so Playwright can read live BGM
// state without needing a DOM-attached <audio> element. `new Audio(url)`
// creates an in-memory HTMLMediaElement that is NOT a child of document, so
// `document.querySelectorAll('audio')` won't find it. The BGM element is
// the only one routed via Web Audio anyway, so a singleton-reflection
// hook is the cleanest way to expose its state.
if (typeof window !== 'undefined') {
  ;(window as unknown as { __audioService?: AudioService }).__audioService = audioService
}
