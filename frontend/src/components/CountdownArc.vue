<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'

const props = defineProps<{
  remainingMs: number
  durationMs: number
  running: boolean
  isHost: boolean
}>()

const emit = defineEmits<{
  'start-timer': [seconds: number]
  'stop-timer': []
}>()

const CIRCUM = 2 * Math.PI * 22 // r=22 in viewBox 50×50
const URGENT_THRESHOLD_MS = 10_000
const PRESETS = [60, 120]

// Snapshot: when the prop changes, record the prop value and the local clock
const snapshotRemainingMs = ref(props.remainingMs)
const snapshotAt = ref(Date.now())

watch(
  () => [props.remainingMs, props.durationMs, props.running] as const,
  ([r]) => {
    snapshotRemainingMs.value = r
    snapshotAt.value = Date.now()
    // Immediately reflect the new value so tests/E2E don't wait for the next tick
    if (props.running) localRemaining.value = r
  },
)

// Local 200ms tick — only active when running
const localRemaining = ref(props.remainingMs)
let intervalId = 0

function startTicking() {
  stopTicking()
  intervalId = window.setInterval(() => {
    localRemaining.value = Math.max(0, snapshotRemainingMs.value - (Date.now() - snapshotAt.value))
  }, 200)
}

function stopTicking() {
  if (intervalId) {
    clearInterval(intervalId)
    intervalId = 0
  }
}

watch(
  () => props.running,
  (isRunning) => {
    if (isRunning) startTicking()
    else {
      stopTicking()
      localRemaining.value = props.running ? snapshotRemainingMs.value : 0
    }
  },
  { immediate: true },
)

onUnmounted(stopTicking)

const urgent = computed(
  () => props.running && localRemaining.value > 0 && localRemaining.value <= URGENT_THRESHOLD_MS,
)

const idle = computed(() => !props.running && props.durationMs === 0)
const stopped = computed(() => !props.running && props.durationMs > 0)
const visuallyIdle = computed(() => (props.isHost ? idle.value || stopped.value : idle.value))

const pct = computed(() =>
  props.durationMs > 0 && !visuallyIdle.value ? localRemaining.value / props.durationMs : 0,
)

const arcClass = computed(() =>
  urgent.value ? 'urgent' : visuallyIdle.value || stopped.value ? 'idle' : '',
)

function fmt(ms: number): string {
  const s = Math.ceil(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

const displayLabel = computed(() => (visuallyIdle.value ? '—' : fmt(localRemaining.value)))
</script>

<template>
  <div class="ca-root" data-testid="phase-countdown" :data-remaining-ms="localRemaining">
    <div class="ca-wrap">
      <svg viewBox="0 0 50 50">
        <circle cx="25" cy="25" r="22" :class="['ca-bg', arcClass]" />
        <circle
          cx="25"
          cy="25"
          r="22"
          :class="['ca-fg', arcClass]"
          :stroke-dasharray="CIRCUM"
          :stroke-dashoffset="CIRCUM * (1 - pct)"
        />
      </svg>
      <div :class="['ca-inner', arcClass]">{{ displayLabel }}</div>
    </div>

    <div v-if="isHost" class="ca-controls">
      <button
        v-if="running"
        type="button"
        class="ca-pill ca-pill--danger"
        data-testid="phase-countdown-stop"
        @click="emit('stop-timer')"
      >
        停止 Stop
      </button>
      <template v-else>
        <button
          v-for="seconds in PRESETS"
          :key="seconds"
          type="button"
          class="ca-pill"
          :data-testid="`phase-countdown-start-${seconds}`"
          @click="emit('start-timer', seconds)"
        >
          {{ fmt(seconds * 1000) }}
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ca-root {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.ca-wrap {
  position: relative;
  width: 56px;
  height: 56px;
}

.ca-wrap svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.ca-bg {
  fill: none;
  stroke: var(--border-l, #ddd6c6);
  stroke-width: 4;
  transition: stroke 0.3s;
}

.ca-bg.urgent {
  stroke: rgba(181, 37, 26, 0.32);
}

.ca-fg {
  fill: none;
  stroke: var(--ink, #2a1f14);
  stroke-width: 4;
  stroke-linecap: round;
  transition:
    stroke 0.3s,
    stroke-dashoffset 0.5s linear;
}

.ca-fg.urgent {
  stroke: var(--red, #b5251a);
}

.ca-fg.idle {
  stroke: var(--border-l, #ddd6c6);
}

.ca-inner {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Noto Serif SC', serif;
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink, #2a1f14);
  line-height: 1;
  transition: color 0.3s;
}

.ca-inner.urgent {
  color: var(--red, #b5251a);
  animation: ca-pulse 1s ease-in-out infinite;
}

.ca-inner.idle {
  color: var(--muted, #8a7a65);
}

.ca-controls {
  display: flex;
  gap: 4px;
}

.ca-pill {
  padding: 4px 10px;
  font: inherit;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.5px;
  cursor: pointer;
  border-radius: 100px;
  background: var(--ink, #2a1f14);
  color: var(--paper, #f5f0e8);
  border: none;
  font-family: inherit;
  transition: background 0.12s;
}

.ca-pill:hover {
  background: #000;
}

.ca-pill--danger {
  background: transparent;
  color: var(--red, #b5251a);
  border: 1px solid rgba(181, 37, 26, 0.3);
}

.ca-pill--danger:hover {
  background: rgba(181, 37, 26, 0.08);
}

@keyframes ca-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}
</style>
