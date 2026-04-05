<template>
  <div class="create-wrap">
    <div class="create-card">
      <!-- Back button -->
      <button class="back-btn" @click="router.push({ name: 'lobby' })">← 返回</button>
      <!-- Header -->
      <div class="create-header">
        <div class="section-lbl">Host</div>
        <h2 class="create-title">创建房间 / Create Room</h2>
      </div>

      <!-- Player count stepper -->
      <div class="stepper-card">
        <div class="field-lbl">玩家人数 / Number of Players</div>
        <div class="stepper-row">
          <button
            :disabled="totalPlayers <= MIN_PLAYERS"
            class="stepper-btn stepper-minus"
            @click="decrement"
          >
            −
          </button>
          <div class="stepper-value">
            <span class="stepper-num">{{ totalPlayers }}</span>
            <span class="stepper-range">{{ MIN_PLAYERS }} – {{ MAX_PLAYERS }}</span>
          </div>
          <button
            :disabled="totalPlayers >= MAX_PLAYERS"
            class="stepper-btn stepper-plus"
            @click="increment"
          >
            +
          </button>
        </div>
      </div>

      <!-- Role configuration -->
      <div class="field-lbl">角色配置 / Role Configuration</div>
      <div class="balance-note">Backend auto-balances role counts based on total players.</div>

      <div class="role-list">
        <div
          v-for="role in ROLE_DEFINITIONS"
          :key="role.id"
          :class="roleRowClass(role)"
          class="role-row"
        >
          <span class="role-emoji">{{ role.emoji }}</span>
          <div class="role-names">
            <span
              :class="{ 'role-name-muted': !role.required && !isEnabled(role.id) }"
              class="role-name"
            >
              {{ role.nameZh }} {{ role.nameEn }}
            </span>
          </div>
          <!-- Required roles: label only -->
          <span
            v-if="role.required"
            :class="role.id === 'WEREWOLF' ? 'req-wolf' : 'req-village'"
            class="req-label"
          >
            REQUIRED
          </span>
          <!-- Optional roles: toggle -->
          <button
            v-else
            :class="isEnabled(role.id) ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            @click="toggleRole(role.id)"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
      </div>

      <!-- Game settings -->
      <div class="field-lbl">游戏设置 / Game Settings</div>
      <div class="role-list">
        <div class="role-row row-on">
          <span class="role-emoji">⭐</span>
          <div class="role-names">
            <span class="role-name">警长竞选 Sheriff Election</span>
          </div>
          <button
            :class="hasSheriff ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            @click="hasSheriff = !hasSheriff"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="winCondition === 'HARD_MODE' ? 'row-wolf' : 'row-on'">
          <span class="role-emoji">⚔️</span>
          <div class="role-names">
            <span class="role-name">胜利条件 Win Condition</span>
            <span class="win-cond-desc">
              {{
                winCondition === 'CLASSIC'
                  ? '经典：狼人人数 ≥ 好人（Classic: wolves ≥ others）'
                  : '困难：全灭好人（Hard: eliminate all villagers）'
              }}
            </span>
          </div>
          <button
            :class="winCondition === 'HARD_MODE' ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            @click="winCondition = winCondition === 'CLASSIC' ? 'HARD_MODE' : 'CLASSIC'"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
      </div>

      <button :disabled="loading" class="btn btn-primary" @click="handleCreate">
        {{ loading ? '创建中…' : '创建房间 / Create Room' }}
      </button>

      <p v-if="error" class="error-msg">{{ error }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import type { WinConditionMode } from '@/types'

const router = useRouter()
const roomStore = useRoomStore()

const MIN_PLAYERS = 6
const MAX_PLAYERS = 12

const ROLE_DEFINITIONS = [
  { id: 'WEREWOLF', nameZh: '狼人', nameEn: 'Werewolf', emoji: '🐺', required: true },
  { id: 'VILLAGER', nameZh: '村民', nameEn: 'Villager', emoji: '🧑‍🌾', required: true },
  { id: 'SEER', nameZh: '预言家', nameEn: 'Seer', emoji: '🔮', required: false },
  { id: 'WITCH', nameZh: '女巫', nameEn: 'Witch', emoji: '🧙‍♀️', required: false },
  { id: 'HUNTER', nameZh: '猎人', nameEn: 'Hunter', emoji: '🏹', required: false },
  { id: 'GUARD', nameZh: '守卫', nameEn: 'Guard', emoji: '🛡️', required: false },
  { id: 'IDIOT', nameZh: '白痴', nameEn: 'Idiot', emoji: '🃏', required: false },
]

const totalPlayers = ref(9)
// Optional roles enabled by default
const enabledOptional = ref(new Set(['SEER', 'WITCH', 'HUNTER']))
const hasSheriff = ref(true)
const winCondition = ref<WinConditionMode>('CLASSIC')

const loading = ref(false)
const error = ref('')

function increment() {
  if (totalPlayers.value < MAX_PLAYERS) totalPlayers.value++
}
function decrement() {
  if (totalPlayers.value > MIN_PLAYERS) totalPlayers.value--
}

function isEnabled(roleId: string): boolean {
  const role = ROLE_DEFINITIONS.find((r) => r.id === roleId)
  if (role?.required) return true
  return enabledOptional.value.has(roleId)
}

function toggleRole(roleId: string) {
  if (enabledOptional.value.has(roleId)) {
    enabledOptional.value.delete(roleId)
  } else {
    enabledOptional.value.add(roleId)
  }
}

function roleRowClass(role: (typeof ROLE_DEFINITIONS)[0]) {
  if (role.required) return role.id === 'WEREWOLF' ? 'row-wolf' : 'row-village'
  return isEnabled(role.id) ? 'row-on' : 'row-off'
}

async function handleCreate() {
  loading.value = true
  error.value = ''
  try {
    const roles = ROLE_DEFINITIONS.filter((r) => isEnabled(r.id)).map((r) => r.id)
    const room = await roomService.createRoom({
      config: {
        totalPlayers: totalPlayers.value,
        roles,
        hasSheriff: hasSheriff.value,
        winCondition: winCondition.value,
      },
    })
    roomStore.setRoom(room)
    router.push({ name: 'room', params: { roomId: room.roomId } })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to create room. Try again.'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.create-wrap {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
  overflow-y: auto;
}

.create-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem;
  width: 100%;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.create-header {
  margin-bottom: 0.25rem;
}

.section-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 0.125rem;
}

.create-title {
  font-size: 1.0625rem;
  font-weight: 500;
  color: var(--text);
  margin: 0;
}

.back-btn {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.8125rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  align-self: flex-start;
  white-space: nowrap;
}

/* Player count stepper */
.stepper-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.875rem 1rem;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}

.field-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 0.625rem;
}

.stepper-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.stepper-btn {
  width: 38px;
  height: 38px;
  border-radius: 6px;
  border: 1px solid var(--border);
  font-size: 1.25rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--paper);
  color: var(--muted);
  font-family: inherit;
  transition: opacity 0.15s;
}

.stepper-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.stepper-plus {
  background: var(--red);
  color: #fff;
  border-color: var(--red);
}

.stepper-value {
  text-align: center;
}

.stepper-num {
  display: block;
  font-family: 'Noto Serif SC', serif;
  font-size: 2.25rem;
  font-weight: 700;
  color: var(--ink);
  line-height: 1;
}

.stepper-range {
  display: block;
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 2px;
}

/* Role list */
.balance-note {
  font-size: 0.625rem;
  color: var(--muted);
  padding: 0.375rem 0.5625rem;
  background: rgba(45, 106, 63, 0.05);
  border: 1px solid rgba(45, 106, 63, 0.18);
  border-radius: 0.3125rem;
}

.role-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.role-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5625rem 0.75rem;
  border-radius: 0.4375rem;
}

.row-wolf {
  background: rgba(181, 37, 26, 0.06);
  border: 1px solid rgba(181, 37, 26, 0.2);
}
.row-village {
  background: rgba(45, 106, 63, 0.05);
  border: 1px solid rgba(45, 106, 63, 0.18);
}
.row-on {
  background: var(--card);
  border: 1px solid var(--border-l);
}
.row-off {
  background: var(--paper);
  border: 1px solid var(--border-l);
  opacity: 0.7;
}

.role-emoji {
  font-size: 1.125rem;
}

.role-names {
  flex: 1;
}

.role-name {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--text);
}

.role-name-muted {
  color: var(--muted);
}

.win-cond-desc {
  display: block;
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 1px;
}

.req-label {
  font-size: 0.625rem;
  letter-spacing: 0.05em;
  font-weight: 600;
}

.req-wolf {
  color: var(--red);
}
.req-village {
  color: var(--green);
}

/* Toggle switch */
.toggle {
  width: 36px;
  height: 20px;
  border-radius: 10px;
  border: none;
  position: relative;
  cursor: pointer;
  transition: background 0.2s;
  flex-shrink: 0;
  padding: 0;
}

.toggle-on {
  background: var(--green);
}
.toggle-off {
  background: var(--border);
}

.toggle-thumb {
  display: block;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 2px;
  transition: left 0.2s;
}

.toggle-on .toggle-thumb {
  left: calc(100% - 18px);
}
.toggle-off .toggle-thumb {
  left: 2px;
}

.error-msg {
  color: var(--red);
  font-size: 0.875rem;
  text-align: center;
  margin: 0;
}
</style>
