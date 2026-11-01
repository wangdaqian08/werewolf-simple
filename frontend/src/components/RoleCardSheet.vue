<template>
  <Teleport to="body">
    <div class="role-card-overlay" data-testid="role-card-sheet" @click.self="emit('close')">
      <div class="role-card-sheet">
        <div class="role-card-header">
          <span>{{ title ?? $t('roleCard.title') }}</span>
          <button class="history-close" @click="emit('close')">✕</button>
        </div>
        <div class="role-card-body">
          <div class="rc-emoji">{{ roleDefinition(role)?.emoji }}</div>
          <div class="rc-name-zh">{{ roleName(role) }}</div>
          <div class="rc-label" :class="`rc-label-${team}`">{{ roleTag(role) }}</div>
          <div v-if="player" class="rc-identity" data-testid="rc-identity">
            {{ $t('common.seatDot', { n: player.seatIndex, name: player.nickname }) }}
          </div>
          <p class="rc-desc" data-testid="role-card-desc">{{ roleDescription(role, true) }}</p>
          <div v-if="teammates?.length" class="rc-section" data-testid="rc-teammates">
            <div class="rc-section-title">{{ $t('roleCard.teammates') }}</div>
            <div
              v-for="mate in teammates"
              :key="mate.userId"
              :class="{ 'rc-mate-dead': !mate.isAlive }"
              class="rc-mate"
            >
              {{ $t('common.seatDot', { n: mate.seatIndex, name: mate.nickname }) }}
            </div>
          </div>
          <div class="rc-section" data-testid="rc-rules">
            <div class="rc-section-title">{{ $t('roleCard.rules') }}</div>
            <ul class="rc-rules">
              <li v-for="line in rules" :key="line">{{ line }}</li>
            </ul>
          </div>
          <RoleHistory v-if="history !== undefined" :history="history" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { t } from '@/i18n'
import type { GamePlayer, RoleHistory as RoleHistoryData } from '@/types'
import {
  isWolfRole,
  roleDefinition,
  roleDescription,
  roleName,
  roleTag,
} from '@/utils/roleDefinitions'
import RoleHistory from '@/components/RoleHistory.vue'

// Role info + rules. Own role (day / voting chip): identity, wolf teammates, history.
// Any role (composition chips, room info): just the info and rules.
const props = defineProps<{
  role: string
  title?: string
  player?: GamePlayer
  teammates?: GamePlayer[]
  history?: RoleHistoryData | null
}>()
const emit = defineEmits<{ close: [] }>()

const team = computed(() =>
  isWolfRole(props.role) ? 'wolf' : props.role === 'VILLAGER' ? 'village' : 'special',
)
const rules = computed(() => t(`role.rules.${props.role}`).split('\n'))
</script>

<style scoped>
.role-card-overlay {
  position: fixed;
  inset: 0;
  background: rgba(26, 20, 12, 0.55);
  z-index: 300;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  touch-action: none;
}

.role-card-sheet {
  width: 100%;
  max-width: 320px;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--paper);
  border-radius: 1rem;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(26, 20, 12, 0.3);
}

.role-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem 0.75rem;
  border-bottom: 1px solid var(--border-l);
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  font-weight: 600;
}

.history-close {
  background: transparent;
  border: none;
  font-size: 1.125rem;
  color: var(--muted);
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  min-width: 44px;
  min-height: 44px;
}

.role-card-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1.75rem 1.5rem 2rem;
  overflow-y: auto;
  touch-action: pan-y;
}

.rc-emoji {
  font-size: 3.5rem;
  line-height: 1;
  margin-bottom: 0.5rem;
}
.rc-name-zh {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text);
}
.rc-label {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  font-weight: 600;
  margin-bottom: 0.5rem;
}
.rc-label-wolf {
  color: var(--red);
}
.rc-label-village {
  color: var(--green);
}
.rc-label-special {
  color: var(--gold);
}
.rc-identity {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text);
}
.rc-desc {
  font-size: 0.8125rem;
  color: var(--muted);
  line-height: 1.6;
  text-align: center;
  margin: 0;
}

.rc-section {
  align-self: stretch;
  border-top: 1px solid var(--border-l);
  padding-top: 0.625rem;
  margin-top: 0.25rem;
}
.rc-section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.8125rem;
  font-weight: 700;
  color: var(--gold);
  margin-bottom: 0.25rem;
}
.rc-mate {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--red);
}
.rc-mate-dead {
  color: var(--muted);
  text-decoration: line-through;
}
.rc-rules {
  margin: 0;
  padding-left: 1.1rem;
  font-size: 0.8125rem;
  color: var(--text);
  line-height: 1.6;
}
</style>
