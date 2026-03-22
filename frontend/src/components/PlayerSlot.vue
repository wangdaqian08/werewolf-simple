<template>
  <div
    :class="['player-slot', variantClass, mode === 'room' && 'slot-room']"
    @click="$emit('click')"
  >
    <!-- ── Room mode: square card with avatar circle ── -->
    <template v-if="mode === 'room'">
      <template v-if="nickname">
        <!-- Seat number always visible so players can identify themselves -->
        <span class="slot-index">{{ seat }}</span>
        <div class="av">{{ avatar }}</div>
        <span class="av-name">{{ nickname }}</span>
        <slot name="badge" />
      </template>
      <template v-else>
        <!-- Show the seat number so players can identify which slot to pick -->
        <span class="empty-num">{{ seat }}</span>
      </template>
      <slot name="overlay" />
    </template>

    <!-- ── Game mode: seat number + name + badge/overlay slots ── -->
    <template v-else>
      <slot name="top" />
      <div :class="{ muted: !nickname }" class="seat-num">{{ seat }}</div>
      <div :class="{ muted: !nickname }" class="seat-name">{{ nickname ?? '—' }}</div>
      <slot name="badge" />
      <slot name="overlay" />
    </template>
  </div>
</template>

<script lang="ts" setup>
import {computed} from 'vue'

type SlotVariant =
  | 'empty'
  | 'selectable'
  | 'me'
  | 'me-ready'
  | 'ready'
  | 'waiting'
  | 'alive'
  | 'dead'
  | 'killed'
  | 'selected'
  | 'teammate' // night-mode: werewolf teammate (styled blue by consumer)

const props = defineProps<{
  seat: number
  nickname?: string | null
  variant?: SlotVariant
  mode?: 'room' | 'game' // default 'game'
  avatar?: string
}>()

defineEmits<{ click: [] }>()

const variantClass = computed(() => {
  switch (props.variant ?? 'empty') {
    case 'selectable':
      return 'slot-selectable'
    case 'me':
      return 'slot-me'
    case 'me-ready':
      return 'slot-me-ready'
    case 'ready':
      return 'slot-ready'
    case 'waiting':
      return 'slot-waiting'
    case 'alive':
      return 'slot-alive'
    case 'dead':
      return 'slot-dead'
    case 'killed':
      return 'slot-killed'
    case 'selected':
      return 'slot-selected'
    case 'teammate':
      return 'slot-teammate'
    default:
      return 'slot-empty'
  }
})
</script>

<style scoped>
/* ── Base (game mode) ── */
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

/* ── Room mode overrides ── */
.slot-room {
  aspect-ratio: 1;
  min-height: unset;
  min-width: 0;       /* prevent grid cell blowout from long text */
  overflow: hidden;   /* clip children that exceed the card boundary */
  padding: 0.375rem 0.25rem;
  gap: 3px;
  border-radius: 0.375rem;
}

/* ── Variant colours ── */
.slot-empty {
  background: var(--paper);
  border: 1px dashed var(--border-l);
  color: var(--border);
}
.slot-selectable {
  background: var(--paper);
  border: 1px dashed var(--gold);
  color: var(--gold);
  cursor: pointer;
}
.slot-me {
  background: var(--card);
  border: 2px solid var(--red);
  color: var(--red);
}
.slot-me-ready {
  background: rgba(45, 106, 63, 0.12);
  border: 2px solid var(--red);
  color: var(--red);
}
.slot-ready {
  background: rgba(45, 106, 63, 0.12);
  border: 1px solid rgba(45, 106, 63, 0.4);
  color: var(--green);
}
.slot-waiting {
  background: var(--bg);
  border: 1px solid var(--border-l);
  color: var(--muted);
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
.slot-killed {
  background: rgba(181, 37, 26, 0.06);
  border: 2px solid var(--red);
  opacity: 0.75;
  cursor: default;
}
.slot-selected {
  background: rgba(160, 120, 48, 0.1);
  border: 2px solid var(--gold);
  cursor: pointer;
}
.slot-teammate {
  background: var(--card);
  border: 1px solid var(--border-l);
  cursor: pointer;
}

/* ── Game mode text ── */
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

/* ── Room mode text ── */
.slot-index {
  font-size: 9px;
  font-weight: 600;
  color: inherit;
  opacity: 0.6;
  line-height: 1;
}

.av {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.av-name {
  font-size: 10px;
  color: inherit;
  line-height: 1.2;
  width: 100%;        /* fill card width so ellipsis has a boundary to clip at */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
  padding: 0 2px;
  box-sizing: border-box;
}

.empty-num {
  font-size: 0.875rem;
  font-weight: 600;
  color: inherit;
}

/* ── Overlay slot structural base ── */
:slotted(.slot-overlay) {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: inherit;
}
</style>
