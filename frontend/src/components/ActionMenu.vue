<template>
  <!-- Chip is only shown during sheriff election, day discussion, and day voting -->
  <template v-if="chipVisible">
    <button class="action-menu-chip" data-testid="action-menu-btn" @click="openMenu">
      <span class="action-chip-icon" aria-hidden="true">⚡</span>
      <span class="action-chip-label">行动</span>
    </button>

    <!-- Drop-sheet (teleported to body to escape stacking contexts) -->
    <Teleport to="body">
      <!-- Backdrop -->
      <Transition name="fade">
        <div v-if="menuOpen" class="action-menu-backdrop" @click="closeMenu" />
      </Transition>

      <!-- Sheet -->
      <Transition name="slide-up">
        <div v-if="menuOpen" class="action-menu-sheet">
          <div class="action-menu-handle" />
          <div class="action-menu-title">行动菜单 · Actions</div>

          <div class="action-menu-body">
            <!-- Wolf + alive + allowed phase → self-destruct option -->
            <template v-if="canSelfDestruct">
              <button
                class="action-menu-item action-menu-item-danger"
                data-testid="action-menu-self-destruct"
                @click="onSelfDestructClick"
              >
                <span class="ami-icon">💥</span>
                <span class="ami-label">自爆 / Self-destruct</span>
              </button>
            </template>

            <!-- Non-wolf or dead or disallowed → empty state -->
            <template v-else>
              <div class="action-menu-empty" data-testid="action-menu-empty">
                <span class="ami-icon">🌙</span>
                <span class="ami-label">暂无操作 / No actions available</span>
              </div>
            </template>
          </div>
        </div>
      </Transition>

      <!-- Confirm modal -->
      <Transition name="fade">
        <div v-if="confirmOpen" class="action-confirm-overlay" @click.self="cancelConfirm">
          <div class="action-confirm-sheet">
            <div class="action-confirm-title">确认自爆？</div>
            <div class="action-confirm-body">此操作不可撤销。你将公开死亡，今天不进行投票。</div>
            <div class="action-confirm-btns">
              <button
                class="btn btn-secondary"
                data-testid="action-menu-cancel"
                @click="cancelConfirm"
              >
                取消 / Cancel
              </button>
              <button
                class="btn btn-danger"
                data-testid="action-menu-confirm"
                @click="confirmSelfDestruct"
              >
                确认 / Confirm
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </template>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import type { GamePhase, PlayerRole } from '@/types'

const props = defineProps<{
  phase: GamePhase
  subPhase?: string
  myRole?: PlayerRole
  isAlive: boolean
}>()

const emit = defineEmits<{
  'self-destruct': []
}>()

const menuOpen = ref(false)
const confirmOpen = ref(false)

const ALLOWED_PHASES: GamePhase[] = ['SHERIFF_ELECTION', 'DAY_DISCUSSION', 'DAY_VOTING']

const chipVisible = computed(() => ALLOWED_PHASES.includes(props.phase))

const canSelfDestruct = computed(
  () => props.myRole === 'WEREWOLF' && props.isAlive && chipVisible.value,
)

function openMenu() {
  menuOpen.value = true
}

function closeMenu() {
  menuOpen.value = false
}

function onSelfDestructClick() {
  menuOpen.value = false
  confirmOpen.value = true
}

function cancelConfirm() {
  confirmOpen.value = false
}

function confirmSelfDestruct() {
  confirmOpen.value = false
  emit('self-destruct')
}
</script>

<style scoped>
.action-menu-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  background: var(--paper, #f5f0e8);
  border: 1px solid var(--border, #ccc2b0);
  border-radius: 999px;
  font-size: 13px;
  color: var(--text, #1a140c);
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: background 0.15s;
}
.action-menu-chip:hover {
  background: var(--bg, #ede8df);
}
.action-chip-icon {
  font-size: 14px;
}
.action-chip-label {
  font-size: 13px;
  font-weight: 500;
}

/* Backdrop */
.action-menu-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  z-index: 300;
}

/* Sheet */
.action-menu-sheet {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  max-height: 50vh;
  background: var(--paper, #f5f0e8);
  border-radius: 16px 16px 0 0;
  z-index: 301;
  padding-bottom: env(safe-area-inset-bottom, 0);
}
.action-menu-handle {
  width: 36px;
  height: 4px;
  background: var(--border, #ccc2b0);
  border-radius: 2px;
  margin: 10px auto 8px;
}
.action-menu-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--muted, #8a7a65);
  text-align: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
}
.action-menu-body {
  padding: 12px 16px;
}
.action-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 12px 14px;
  background: transparent;
  border: 1px solid var(--border, #ccc2b0);
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  color: var(--text, #1a140c);
  text-align: left;
}
.action-menu-item-danger {
  border-color: var(--red, #b5251a);
  color: var(--red, #b5251a);
}
.action-menu-item-danger:hover {
  background: rgba(181, 37, 26, 0.06);
}
.action-menu-empty {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  color: var(--muted, #8a7a65);
  font-size: 14px;
}
.ami-icon {
  font-size: 18px;
}

/* Confirm overlay */
.action-confirm-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 400;
  display: flex;
  align-items: center;
  justify-content: center;
}
.action-confirm-sheet {
  background: var(--paper, #f5f0e8);
  border-radius: 14px;
  padding: 24px 20px;
  max-width: 320px;
  width: 90%;
  text-align: center;
}
.action-confirm-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--red, #b5251a);
  margin-bottom: 10px;
}
.action-confirm-body {
  font-size: 13px;
  color: var(--muted, #8a7a65);
  margin-bottom: 20px;
  line-height: 1.5;
}
.action-confirm-btns {
  display: flex;
  gap: 10px;
  justify-content: center;
}
.btn-danger {
  background: var(--red, #b5251a);
  color: #fff;
  border: none;
  border-radius: 8px;
  padding: 10px 20px;
  font-size: 14px;
  cursor: pointer;
}

/* Transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
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
