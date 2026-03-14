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
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    timeRemaining: number
    totalTime?: number
  }>(),
  { totalTime: 120 },
)

const W = 417
const H = 56

// Quadratic bezier: M 0,H Q W/2,0 W,H
// At parameter t: x = t*W, y = H*(1 - 2t + 2t²)
const progress = computed(() => {
  const t = 1 - props.timeRemaining / props.totalTime
  return Math.min(1, Math.max(0, t))
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
  padding: 0 1.25rem;
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
