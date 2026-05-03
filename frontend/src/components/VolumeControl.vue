<template>
  <div
    class="vc-root"
    @mouseenter="onRootEnter"
    @mouseleave="onRootLeave"
  >
    <button
      type="button"
      :class="['vc-btn', { 'is-muted': isMuted }]"
      :title="isMuted ? 'Unmute' : 'Mute'"
      :aria-label="isMuted ? 'Unmute' : 'Mute'"
      :aria-pressed="isMuted"
      data-testid="audio-mute-btn"
      @click="onToggle"
    >
      <svg
        v-if="isMuted"
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M11 5L6 9H2v6h4l5 4V5z" />
        <line x1="23" y1="9" x2="17" y2="15" />
        <line x1="17" y1="9" x2="23" y2="15" />
      </svg>
      <svg
        v-else
        width="16"
        height="16"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <path d="M11 5L6 9H2v6h4l5 4V5z" />
        <path v-if="volume > 0.33" d="M15.54 8.46a5 5 0 010 7.07" />
        <path v-if="volume > 0.66" d="M19.07 4.93a10 10 0 010 14.14" />
      </svg>
    </button>

    <div
      :class="['vc-slider-wrap', { 'is-hidden': !sliderShown }]"
      :aria-hidden="!sliderShown"
    >
      <div class="vc-track">
        <div class="vc-fill" :style="{ width: fill + '%' }" />
        <div class="vc-thumb" :style="{ left: fill + '%' }" />
      </div>
      <input
        class="vc-input"
        type="range"
        min="0"
        max="100"
        :value="fill"
        aria-label="Volume"
        data-testid="volume-slider"
        @input="onVolumeInput"
      />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onUnmounted, ref } from 'vue'
import { audioService } from '@/services/audioService'

/*
 * Visibility model:
 *   - The slider is hidden by default while unmuted ("settled" rest state).
 *   - Hovering the pill expands it; moving away starts a 1.5 s idle timer.
 *   - Adjusting the slider clears + restarts the timer (won't snap shut mid-drag).
 *   - Click mute   → instant collapse.
 *   - Click unmute → briefly reveals the slider, then auto-hides after 1.5 s.
 *
 * The slider is only ever rendered when !isMuted. `expanded` is the source of
 * truth; `sliderShown` ANDs it with !isMuted so a stray expansion while muted
 * cannot leak through.
 */

const IDLE_MS = 1500

const isMuted = ref(audioService.isMuted())
const volume = ref(audioService.getBgmVolume())
const expanded = ref(false)
const fill = computed(() => Math.round(volume.value * 100))
const sliderShown = computed(() => expanded.value && !isMuted.value)

let idleTimer: ReturnType<typeof setTimeout> | null = null

function clearIdleTimer() {
  if (idleTimer) {
    clearTimeout(idleTimer)
    idleTimer = null
  }
}

function startIdleTimer() {
  clearIdleTimer()
  idleTimer = setTimeout(() => {
    expanded.value = false
    idleTimer = null
  }, IDLE_MS)
}

function onRootEnter() {
  if (isMuted.value) return
  expanded.value = true
  clearIdleTimer()
}

function onRootLeave() {
  if (isMuted.value) return
  startIdleTimer()
}

function onToggle() {
  // Snapshot prior state, then flip mute.
  const wasMuted = isMuted.value
  audioService.toggleMute()
  isMuted.value = audioService.isMuted()

  if (wasMuted) {
    // Just unmuted — briefly reveal the slider, then auto-hide.
    expanded.value = true
    startIdleTimer()
  } else {
    // Just muted — instant collapse, kill any pending timer.
    expanded.value = false
    clearIdleTimer()
  }
}

function onVolumeInput(e: Event) {
  const raw = Number.parseFloat((e.target as HTMLInputElement).value)
  if (!Number.isFinite(raw)) return
  const v = Math.max(0, Math.min(1, raw / 100))
  volume.value = v
  audioService.setBgmVolume(v)
  // Adjusting resets the idle window so the slider stays open while in use.
  startIdleTimer()
}

onUnmounted(clearIdleTimer)
</script>

<style scoped>
/*
 * Floating mute toggle + expanding volume slider.
 * Style C "Ink & Paper" — uses project tokens, which match the reference's
 * --vc-* values byte-for-byte (#1a140c, #8a7a65, #f5f0e8, #ccc2b0, #ffffff).
 */

.vc-root {
  position: fixed;
  bottom: max(1rem, env(safe-area-inset-bottom));
  right: max(1rem, env(safe-area-inset-right));
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 0;
  padding: 4px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 999px;
  box-shadow:
    0 4px 16px rgba(0, 0, 0, 0.10),
    0 1px 3px rgba(0, 0, 0, 0.06);
  transition: all 0.2s ease;
}

.vc-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
  transition: background 0.15s, color 0.15s;
}

.vc-btn:hover {
  background: var(--paper);
}

.vc-btn.is-muted {
  background: var(--paper);
  color: var(--muted);
}

.vc-slider-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 96px;
  padding: 0 10px 0 6px;
  overflow: hidden;
  transition:
    width 0.25s ease,
    opacity 0.2s ease,
    padding 0.2s ease;
}

.vc-slider-wrap.is-hidden {
  width: 0;
  padding: 0;
  opacity: 0;
}

.vc-track {
  position: relative;
  flex: 1;
  height: 6px;
  background: var(--paper);
  border-radius: 3px;
}

.vc-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: var(--text);
  border-radius: 3px;
}

.vc-thumb {
  position: absolute;
  top: -5px;
  width: 16px;
  height: 16px;
  margin-left: -8px;
  border-radius: 50%;
  background: var(--card);
  border: 1.5px solid var(--text);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.15);
  cursor: pointer;
  pointer-events: none;
}

/*
 * Invisible <input type="range"> covers the entire pill row (icon excluded
 * because input lives inside .vc-slider-wrap). This gives a ~80×40 px hit
 * area instead of the 4 px-tall track, which is easy to grab on mobile.
 *
 * touch-action: none prevents the browser from interpreting a horizontal
 * drag as a page swipe / pull-to-refresh on mobile Chrome.
 */
.vc-input {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  padding: 0;
  opacity: 0;
  cursor: pointer;
  touch-action: none;
}

/* Night-mode overlay so the white pill doesn't burn against an ink bg. */
.night-mode .vc-root {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.night-mode .vc-btn {
  color: #f5f0e8;
}

.night-mode .vc-btn.is-muted {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(245, 240, 232, 0.55);
}

.night-mode .vc-track {
  background: rgba(255, 255, 255, 0.15);
}

.night-mode .vc-fill {
  background: #f5f0e8;
}

.night-mode .vc-thumb {
  background: #f5f0e8;
  border-color: #f5f0e8;
}
</style>
