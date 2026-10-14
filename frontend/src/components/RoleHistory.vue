<template>
  <div v-if="history" class="rh">
    <button class="btn btn-outline rh-btn" data-testid="role-history-btn" @click="open = !open">
      📜 历史记录 · History
    </button>

    <div v-if="open" class="rh-panel" data-testid="role-history">
      <div v-if="history.lastNightAttacked" class="rh-last" data-testid="role-history-last-night">
        第{{ history.lastNightAttacked.round }}夜被刀 ·
        {{ who(history.lastNightAttacked.target) }}
      </div>
      <div
        v-for="(e, i) in history.entries"
        :key="i"
        class="rh-row"
        data-testid="role-history-entry"
      >
        第{{ e.round }}夜 · {{ describe(e) }}
      </div>
      <div v-if="!history.entries.length && !history.lastNightAttacked" class="rh-empty">
        暂无记录
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { RoleHistory, RoleHistoryEntry, RoleHistoryPlayer } from '@/types'

defineProps<{ history?: RoleHistory | null }>()

const open = ref(false)

function who(p: RoleHistoryPlayer | null): string {
  return p ? `${p.seatIndex}号 ${p.nickname}` : '无人'
}

function describe(e: RoleHistoryEntry): string {
  switch (e.action) {
    case 'CHECK':
      return `查验 ${who(e.target)} → ${e.isWerewolf ? '狼人' : '好人'}`
    case 'GUARD':
      return e.target ? `守护 ${who(e.target)}` : '空守'
    case 'KILL':
      return e.target ? `刀了 ${who(e.target)}` : '空刀'
    case 'ANTIDOTE':
      return `解药救了 ${who(e.target)}`
    case 'POISON':
      return `毒药毒了 ${who(e.target)}`
  }
}
</script>

<style scoped>
.rh {
  width: 100%;
  margin-top: 0.5rem;
}
.rh-btn {
  width: 100%;
}
.rh-panel {
  margin-top: 0.5rem;
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  max-height: 10rem;
  overflow-y: auto;
}
.rh-last,
.rh-row,
.rh-empty {
  padding: 0.4rem 0.6rem;
  font-size: 0.8125rem;
  border-bottom: 1px solid var(--border-l);
}
.rh-last {
  font-weight: 600;
}
.rh-row:last-child,
.rh-empty {
  border-bottom: none;
}
.rh-empty {
  color: var(--muted);
  text-align: center;
}
</style>
