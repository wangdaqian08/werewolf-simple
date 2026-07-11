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
          <span class="modal-title">房间信息</span>
          <button class="modal-close" data-testid="settings-close" @click="$emit('close')">
            ✕
          </button>
        </div>

        <div class="modal-body">
          <template v-if="settings">
            <!-- Room code — displayed large so other players can be invited to watch -->
            <div class="room-code-block">
              <div class="block-label">房间号 · Room Code</div>
              <div class="room-code-value" data-testid="settings-room-code">
                {{ settings.roomCode }}
              </div>
            </div>

            <!-- Role composition -->
            <div class="settings-section">
              <div class="section-title">角色配置 · Roles</div>
              <div class="role-rows" data-testid="settings-roles">
                <div
                  v-for="rc in roleCounts"
                  :key="rc.id"
                  class="role-row"
                  :data-testid="`settings-role-${rc.id}`"
                >
                  <span class="role-emoji" aria-hidden="true">{{ rc.emoji }}</span>
                  <span class="role-name">{{ rc.nameZh }}</span>
                  <span class="role-count">×{{ rc.count }}</span>
                </div>
              </div>
            </div>

            <!-- Rules -->
            <div class="settings-section">
              <div class="section-title">规则 · Rules</div>
              <div class="rule-row" data-testid="settings-player-count">
                <span class="rule-label">玩家人数</span>
                <span class="rule-value">{{ settings.totalPlayers }}</span>
              </div>
              <div class="rule-row" data-testid="settings-sheriff">
                <span class="rule-label">警长</span>
                <span class="rule-value">{{ settings.hasSheriff ? '有' : '无' }}</span>
              </div>
              <div class="rule-row" data-testid="settings-witch-self-save">
                <span class="rule-label">女巫自救</span>
                <span class="rule-value">{{ settings.witchSelfSaveAllowed ? '允许' : '禁止' }}</span>
              </div>
              <div class="rule-row" data-testid="settings-win-condition">
                <span class="rule-label">胜利条件</span>
                <span class="rule-value">
                  {{ settings.winCondition === 'HARD_MODE' ? '屠城' : '屠边' }}
                </span>
              </div>
            </div>
          </template>

          <div v-else class="settings-empty" data-testid="settings-empty">暂无数据</div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { GameSettings } from '@/types'
import { ROLE_DEFINITIONS } from '@/utils/roleDefinitions'

const props = defineProps<{ settings: GameSettings | null | undefined; open: boolean }>()
defineEmits<{ close: [] }>()

interface RoleCount {
  id: string
  nameZh: string
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
      ordered.push({ id: def.id, nameZh: def.nameZh, emoji: def.emoji, count })
      counts.delete(def.id)
    }
  }
  for (const [id, count] of counts) ordered.push({ id, nameZh: id, emoji: '❓', count })
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
