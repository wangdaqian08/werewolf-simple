<template>
  <div v-if="history" class="rh">
    <button class="btn btn-outline rh-btn" data-testid="role-history-btn" @click="open = !open">
      {{ $t('roleHistory.title') }}
    </button>

    <div v-if="open" class="rh-panel" data-testid="role-history">
      <div
        v-for="(e, i) in history.entries"
        :key="i"
        class="rh-row"
        data-testid="role-history-entry"
      >
        {{ $t('roleHistory.night', { n: e.round, text: describe(e) }) }}
      </div>
      <div v-if="!history.entries.length" class="rh-empty">{{ $t('roleHistory.empty') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { t } from '@/i18n'
import { ref } from 'vue'
import type { RoleHistory, RoleHistoryEntry, RoleHistoryPlayer } from '@/types'

defineProps<{ history?: RoleHistory | null }>()

const open = ref(false)

function who(p: RoleHistoryPlayer | null): string {
  return p ? t('common.seatNick', { n: p.seatIndex, name: p.nickname }) : t('roleHistory.nobody')
}

function describe(e: RoleHistoryEntry): string {
  switch (e.action) {
    case 'CHECK':
      return t('roleHistory.check', {
        who: who(e.target),
        result: e.isWerewolf ? t('roleHistory.wolf') : t('roleHistory.good'),
      })
    case 'GUARD':
      return e.target ? t('roleHistory.guard', { who: who(e.target) }) : t('roleHistory.guardNone')
    case 'KILL':
      return e.target ? t('roleHistory.kill', { who: who(e.target) }) : t('roleHistory.killNone')
    case 'ATTACKED':
      return e.target
        ? t('roleHistory.attacked', { who: who(e.target) })
        : t('roleHistory.attackedNone')
    case 'ANTIDOTE':
      return t('roleHistory.antidote', { who: who(e.target) })
    case 'POISON':
      return t('roleHistory.poison', { who: who(e.target) })
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
.rh-row,
.rh-empty {
  padding: 0.4rem 0.6rem;
  font-size: 0.8125rem;
  border-bottom: 1px solid var(--border-l);
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
