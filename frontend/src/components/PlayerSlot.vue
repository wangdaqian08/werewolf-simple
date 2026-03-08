<template>
  <div :class="['player-slot', variantClass]" @click="$emit('click')">
    <!-- Top overlay slot: sheriff badge, etc. -->
    <slot name="top" />
    <div :class="{ muted: !nickname }" class="seat-num">{{ seat }}</div>
    <div :class="{ muted: !nickname }" class="seat-name">{{ nickname ?? '—' }}</div>
    <!-- Badge slot: HOST / READY / custom -->
    <slot name="badge" />
    <!-- Overlay slot: dead cross, etc. -->
    <slot name="overlay" />
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

type SlotVariant = 'empty' | 'me' | 'ready' | 'waiting' | 'alive' | 'dead'

const props = defineProps<{
  seat: number
  nickname?: string | null
  variant?: SlotVariant
}>()

defineEmits<{ click: [] }>()

const variantClass = computed(() => {
  switch (props.variant ?? 'empty') {
    case 'me':
      return 'slot-me'
    case 'ready':
      return 'slot-ready'
    case 'waiting':
      return 'slot-waiting'
    case 'alive':
      return 'slot-alive'
    case 'dead':
      return 'slot-dead'
    default:
      return 'slot-empty'
  }
})
</script>

<style scoped>
.player-slot {
  border-radius: 0.625rem;
  padding: 0.75rem 0.5rem;
  text-align: center;
  min-height: 88px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem;
  position: relative;
}

.slot-empty {
  background: var(--paper);
  border: 1px dashed var(--border-l);
}

.slot-me {
  background: var(--card);
  border: 2px solid var(--red);
}

.slot-ready {
  background: rgba(45, 106, 63, 0.08);
  border: 1px solid rgba(45, 106, 63, 0.25);
}

.slot-waiting {
  background: var(--card);
  border: 1px solid var(--border-l);
}

.slot-alive {
  background: var(--card);
  border: 1px solid var(--border-l);
  cursor: pointer;
}

.slot-dead {
  background: var(--paper);
  border: 1px solid var(--border-l);
  opacity: 0.45;
  cursor: default;
}

.seat-num {
  font-size: 0.75rem;
  color: var(--muted);
}

.seat-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--text);
  word-break: break-all;
}

.muted {
  color: var(--border);
}
</style>
