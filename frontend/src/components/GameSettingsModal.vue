<template>
  <Teleport to="body">
    <!-- Backdrop -->
    <Transition name="fade">
      <div
        v-if="open"
        class="settings-backdrop"
        data-testid="settings-backdrop"
        @click="$emit('close')"
      />
    </Transition>

    <!-- Panel -->
    <Transition name="slide-up">
      <div v-if="open" class="game-settings-modal" data-testid="settings-modal">
        <div class="modal-handle" />

        <div class="modal-header">
          <span class="modal-title">{{ $t('common.roomInfo') }}</span>
          <button class="modal-close" data-testid="settings-close" @click="$emit('close')">
            ✕
          </button>
        </div>

        <div class="modal-body">
          <template v-if="settings">
            <!-- Room code — displayed large so other players can be invited to watch -->
            <div class="room-code-block">
              <div class="block-label">{{ $t('settings.roomCode') }}</div>
              <div class="room-code-value" data-testid="settings-room-code">
                {{ settings.roomCode }}
              </div>
            </div>

            <!-- Role composition -->
            <div class="settings-section">
              <div class="section-title">{{ $t('settings.roles') }}</div>
              <div class="role-rows" data-testid="settings-roles">
                <div
                  v-for="rc in roleCounts"
                  :key="rc.id"
                  class="role-row"
                  :data-testid="`settings-role-${rc.id}`"
                >
                  <span class="role-emoji" aria-hidden="true">{{ rc.emoji }}</span>
                  <span class="role-name">{{ rc.name }}</span>
                  <span class="role-count">×{{ rc.count }}</span>
                </div>
              </div>
            </div>

            <!-- Rules -->
            <div class="settings-section">
              <div class="section-title">{{ $t('settings.rules') }}</div>
              <div class="rule-row" data-testid="settings-player-count">
                <span class="rule-label">{{ $t('settings.playerCount') }}</span>
                <span class="rule-value">{{ settings.totalPlayers }}</span>
              </div>
              <div class="rule-row" data-testid="settings-sheriff">
                <span class="rule-label">{{ $t('rv.sheriff') }}</span>
                <span class="rule-value">{{
                  settings.hasSheriff ? $t('settings.yes') : $t('settings.no')
                }}</span>
              </div>
              <div class="rule-row" data-testid="settings-witch-self-save">
                <span class="rule-label">{{ $t('create.witchSelfSave') }}</span>
                <span class="rule-value">{{
                  settings.witchSelfSaveAllowed
                    ? $t('settings.firstNight')
                    : $t('settings.disallowed')
                }}</span>
              </div>
              <div class="rule-row" data-testid="settings-no-kill">
                <span class="rule-label">{{ $t('create.noKill') }}</span>
                <span class="rule-value">{{
                  settings.noKillAllowed ? $t('settings.allowed') : $t('settings.disallowed')
                }}</span>
              </div>
              <div class="rule-row" data-testid="settings-guard-skip">
                <span class="rule-label">{{ $t('create.noProtect') }}</span>
                <span class="rule-value">{{
                  settings.guardSkipAllowed ? $t('settings.allowed') : $t('settings.disallowed')
                }}</span>
              </div>
              <div class="rule-row" data-testid="settings-win-condition">
                <span class="rule-label">{{ $t('create.winCondition') }}</span>
                <span class="rule-value">
                  {{
                    settings.winCondition === 'HARD_MODE'
                      ? $t('settings.hard')
                      : $t('settings.classic')
                  }}
                </span>
              </div>
            </div>

            <!-- Scoring: the same rules settlement actually pays on -->
            <div v-if="rules.length" class="settings-section" data-testid="settings-rewards">
              <div class="section-title">{{ $t('settings.scoring') }}</div>
              <template v-for="g in ruleGroups" :key="g.key">
                <div class="rule-group" :data-testid="`settings-reward-group-${g.key}`">
                  {{ $t(g.title) }}
                </div>
                <div
                  v-for="r in g.rules"
                  :key="r.reason"
                  class="rule-row"
                  :data-testid="`settings-reward-${r.reason}`"
                >
                  <span class="rule-label">
                    {{ ruleText(r, 'label') }}
                    <span class="rule-detail">{{ ruleText(r, 'detail') }}</span>
                  </span>
                  <span class="rule-value" :class="r.amount < 0 ? 'rule-penalty' : 'rule-gain'">
                    {{ ruleAmount(r) }}
                  </span>
                </div>
              </template>
              <p v-if="hasPenalty" class="rule-note">{{ $t('settings.floor') }}</p>
            </div>
          </template>

          <div v-else class="settings-empty" data-testid="settings-empty">
            {{ $t('settings.empty') }}
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { i18n, t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import type { GameSettings, RewardRule } from '@/types'
import { ROLE_DEFINITIONS, roleName } from '@/utils/roleDefinitions'
import { walletService } from '@/services/walletService'

const props = defineProps<{ settings: GameSettings | null | undefined; open: boolean }>()
defineEmits<{ close: [] }>()

// The scoring table is fixed per deployment, so it is fetched once, the first time
// someone looks at it, and the section simply stays hidden if that call fails.
const rules = ref<RewardRule[]>([])
watch(
  () => props.open,
  async (open) => {
    if (!open || rules.value.length) return
    try {
      rules.value = (await walletService.getRewardRules()) ?? []
    } catch {
      rules.value = []
    }
  },
  { immediate: true },
)
const te = i18n.global.te
const hasPenalty = computed(() => rules.value.some((r) => r.amount < 0))

// A player gets exactly one BASE rule, so listing all five flat read as though a
// winning wolf collected both the win bonus and the survival score. An empty group
// renders nothing, so 扣分项 stays hidden until a penalty exists.
// NOTE: 三者取其一 names the three BASE rules; update it if a fourth is ever added.
const RULE_GROUPS = [
  { key: 'BASE', title: 'settings.groupBase' },
  { key: 'BONUS', title: 'settings.groupBonus' },
  { key: 'PENALTY', title: 'settings.groupPenalty' },
] as const
const ruleGroups = computed(() =>
  RULE_GROUPS.map((g) => ({ ...g, rules: rules.value.filter((r) => r.group === g.key) })).filter(
    (g) => g.rules.length > 0,
  ),
)
/**
 * The catalog comes from the server (so the amounts stay authoritative); its
 * wording is localised here by rule code, falling back to the server's text
 * for a rule this client does not know yet.
 */
function ruleText(r: RewardRule, field: 'label' | 'detail'): string {
  const key = `rules.${field}.${r.reason}`
  return te(key) ? t(key) : r[field]
}
function ruleUnit(unit: string): string {
  return unit === '天' ? t('rules.unit.day') : unit === '次' ? t('rules.unit.time') : unit
}
/** A penalty reads as a real minus sign, not a hyphen. */
function ruleAmount(r: RewardRule): string {
  return `${r.amount < 0 ? '−' : '+'}${Math.abs(r.amount)}${r.unit ? ` / ${ruleUnit(r.unit)}` : ''}`
}

interface RoleCount {
  id: string
  name: string
  emoji: string
  count: number
}

// Aggregate the per-seat multiset (["WEREWOLF","WEREWOLF","SEER",...]) into
// display rows, ordered by ROLE_DEFINITIONS; unknown ids appended raw so a
// future backend role never renders as a blank row.
const roleCounts = computed<RoleCount[]>(() => {
  const counts = new Map<string, number>()
  for (const r of props.settings?.roles ?? []) counts.set(r, (counts.get(r) ?? 0) + 1)

  const ordered: RoleCount[] = []
  for (const def of ROLE_DEFINITIONS) {
    const count = counts.get(def.id)
    if (count) {
      ordered.push({ id: def.id, name: roleName(def.id), emoji: def.emoji, count })
      counts.delete(def.id)
    }
  }
  for (const [id, count] of counts) ordered.push({ id, name: id, emoji: '❓', count })
  return ordered
})
</script>

<style scoped>
.settings-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 200;
}

.game-settings-modal {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  max-height: 70vh;
  background: var(--paper, #f5f0e8);
  border-radius: 16px 16px 0 0;
  z-index: 201;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.rule-group {
  margin: 0.625rem 0 0.125rem;
  color: var(--muted);
  font-size: 0.75rem;
  font-weight: 600;
}
.rule-detail {
  display: block;
  color: var(--muted);
  font-size: 0.75rem;
}
.rule-gain {
  color: var(--gold);
  font-weight: 600;
}
.rule-penalty {
  color: var(--red);
  font-weight: 600;
}
.rule-note {
  margin: 0.5rem 0 0;
  color: var(--muted);
  font-size: 0.75rem;
}

.modal-handle {
  width: 36px;
  height: 4px;
  background: var(--border, #ccc2b0);
  border-radius: 2px;
  margin: 10px auto 0;
  flex-shrink: 0;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px 8px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
}

.modal-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--text, #1a140c);
}

.modal-close {
  background: none;
  border: none;
  font-size: 18px;
  color: var(--muted, #8a7a65);
  cursor: pointer;
  padding: 4px 8px;
  min-width: 44px;
  min-height: 44px;
}

.modal-body {
  overflow-y: auto;
  padding: 14px 16px 24px;
}

.room-code-block {
  text-align: center;
  padding: 8px 0 14px;
}

.block-label {
  font-size: 12px;
  color: var(--muted, #8a7a65);
  margin-bottom: 4px;
}

.room-code-value {
  font-family: 'Noto Serif SC', serif;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 8px;
  color: var(--red, #b5251a);
}

.settings-section {
  margin-top: 12px;
}

.section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--gold, #a07830);
  margin-bottom: 6px;
}

.role-rows {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
}

.role-row {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--card, #ffffff);
  border: 1px solid var(--border-l, #ddd6c6);
  border-radius: 999px;
  font-size: 14px;
  color: var(--text, #1a140c);
}

.role-count {
  color: var(--muted, #8a7a65);
}

.rule-row {
  display: flex;
  justify-content: space-between;
  padding: 7px 2px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
  font-size: 14px;
}

.rule-label {
  color: var(--muted, #8a7a65);
}

.rule-value {
  color: var(--text, #1a140c);
  font-weight: 500;
}

.settings-empty {
  text-align: center;
  color: var(--muted, #8a7a65);
  padding: 24px 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.25s ease;
}
.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
}
</style>
