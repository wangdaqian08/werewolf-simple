<template>
  <div class="sun-arc-wrap">
    <svg :viewBox="`0 0 ${W} ${H}`" class="sun-arc-svg" preserveAspectRatio="none">
      <!-- Arc path -->
      <path :d="arcPath" class="arc-path" />
      <!-- Sun rays -->
      <line
        v-for="(ray, i) in rays"
        :key="i"
        :x1="sunX + ray.x1"
        :y1="sunY + ray.y1"
        :x2="sunX + ray.x2"
        :y2="sunY + ray.y2"
        class="sun-ray"
      />
      <!-- Sun circle -->
      <circle :cx="sunX" :cy="sunY" r="7" class="sun-circle" />
    </svg>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps<{
  phaseDeadline: number
  phaseStarted: number
}>()

const W = 417
const H = 56

const now = ref(Date.now())
let rafId = 0

function tick() {
  now.value = Date.now()
  rafId = requestAnimationFrame(tick)
}

onMounted(() => {
  rafId = requestAnimationFrame(tick)
})

onUnmounted(() => {
  cancelAnimationFrame(rafId)
})

// Quadratic bezier: M 0,H Q W/2,0 W,H
// Progress 0 = sunrise (left), 1 = sunset (right)
const FALLBACK_PERIOD = 120_000 // 2-min loop when no deadline set

const progress = computed(() => {
  if (!props.phaseDeadline || !props.phaseStarted) {
    return (now.value % FALLBACK_PERIOD) / FALLBACK_PERIOD
  }
  const total = props.phaseDeadline - props.phaseStarted
  const elapsed = now.value - props.phaseStarted
  return Math.min(1, Math.max(0, elapsed / total))
})

const sunX = computed(() => progress.value * W)
const sunY = computed(() => {
  const t = progress.value
  return H * (1 - 2 * t + 2 * t * t)
})

const arcPath = `M 0 ${H} Q ${W / 2} 0 ${W} ${H}`

const RAY_INNER = 10
const RAY_OUTER = 16
const rays = Array.from({ length: 8 }, (_, i) => {
  const angle = (i * Math.PI) / 4
  return {
    x1: Math.cos(angle) * RAY_INNER,
    y1: Math.sin(angle) * RAY_INNER,
    x2: Math.cos(angle) * RAY_OUTER,
    y2: Math.sin(angle) * RAY_OUTER,
  }
})
</script>

<style scoped>
.sun-arc-wrap {
  width: 100%;
  padding: 0 1.25rem 1.5rem;
  box-sizing: border-box;
}

.sun-arc-svg {
  width: 100%;
  height: 3.5rem;
  overflow: visible;
}

.arc-path {
  fill: none;
  stroke: rgba(245, 166, 35, 0.25);
  stroke-width: 1.5;
  stroke-dasharray: 4 4;
}

.sun-circle {
  fill: #f5a623;
  filter: drop-shadow(0 0 4px rgba(245, 166, 35, 0.6));
}

.sun-ray {
  stroke: #f5a623;
  stroke-width: 1.5;
  stroke-linecap: round;
  opacity: 0.8;
}
</style>
