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
            data-testid="winCondition-toggle"
            :data-win-condition="winCondition"
            @click="winCondition = winCondition === 'CLASSIC' ? 'HARD_MODE' : 'CLASSIC'"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row row-on">
          <span class="role-emoji">🎵</span>
          <div class="role-names">
            <span class="role-name">夜晚配乐 Night BGM</span>
            <span class="win-cond-desc">每夜循环播放，旁白时自动降低音量</span>
          </div>
          <div ref="bgmSelectRef" class="bgm-select-wrap">
            <button
              type="button"
              :class="['bgm-select-trigger', { open: bgmOpen }]"
              :disabled="bgmLoading"
              data-testid="bgm-track-select"
              :data-value="bgmTrack ?? ''"
              @click="bgmOpen = !bgmOpen"
            >
              <span class="bgm-select-label">{{ bgmDisplayName }}</span>
              <span class="bgm-select-caret" aria-hidden="true">▾</span>
            </button>
            <!--
              Bottom-anchored menu (bottom: 100%) opens UPWARD, which is the
              right call here: the BGM row is the last form row before the
              Create button, so a downward-opening menu would be clipped or
              overlap the CTA.
            -->
            <ul v-if="bgmOpen" class="bgm-select-menu" role="listbox">
              <li
                v-for="t in bgmTracks"
                :key="t.id ?? '__none__'"
                role="option"
                :data-filename="t.filename ?? ''"
                :aria-selected="bgmTrack === (t.filename ?? null)"
                :class="['bgm-select-option', { selected: bgmTrack === (t.filename ?? null) }]"
                @click="onSelectBgm(t.filename ?? null)"
              >
                {{ t.displayName }}
              </li>
            </ul>
          </div>
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useRoomStore } from '@/stores/roomStore'
import { useUserStore } from '@/stores/userStore'
import { roomService } from '@/services/roomService'
import { audioTracksService, type AudioTrack } from '@/services/audioTracksService'
import type { WinConditionMode } from '@/types'

const router = useRouter()
const roomStore = useRoomStore()
const userStore = useUserStore()

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

const bgmTracks = ref<AudioTrack[]>([{ id: null, filename: null, displayName: '无 (None)' }])
const bgmTrack = ref<string | null>(null)
const bgmLoading = ref(false)
const bgmOpen = ref(false)
const bgmSelectRef = ref<HTMLElement | null>(null)

const bgmDisplayName = computed(() => {
  const match = bgmTracks.value.find((t) => (t.filename ?? null) === bgmTrack.value)
  return match?.displayName ?? '无 (None)'
})

function onSelectBgm(filename: string | null) {
  bgmTrack.value = filename
  bgmOpen.value = false
}

function onDocumentClick(e: MouseEvent) {
  if (!bgmOpen.value) return
  const root = bgmSelectRef.value
  if (root && !root.contains(e.target as Node)) {
    bgmOpen.value = false
  }
}

const loading = ref(false)
const error = ref('')

onMounted(async () => {
  document.addEventListener('click', onDocumentClick, true)
  bgmLoading.value = true
  try {
    const list = await audioTracksService.fetchTracks()
    if (list.length > 0) bgmTracks.value = list
  } catch (e) {
    console.warn('[CreateRoomView] failed to load BGM tracks', e)
  } finally {
    bgmLoading.value = false
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick, true)
})

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
    const req: import('@/types').CreateRoomRequest = {
      config: {
        totalPlayers: totalPlayers.value,
        roles,
        hasSheriff: hasSheriff.value,
        winCondition: winCondition.value,
        bgmTrack: bgmTrack.value,
      },
    }
    // Carry the per-room display-name override that the lobby may have set.
    if (userStore.displayName) req.nickname = userStore.displayName
    const room = await roomService.createRoom(req)
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

/*
 * Custom BGM dropdown.
 * The native <select> rendered the option list at a Chrome-controlled
 * position that broke under mobile-emulation viewports (popup floated
 * mid-page, not anchored to the trigger). This custom component anchors
 * the menu to the trigger and opens UPWARD because the BGM row is the
 * last item before the Create button — opening downward would clip
 * against the viewport bottom on portrait mobile.
 */
.bgm-select-wrap {
  position: relative;
  flex-shrink: 0;
  max-width: 140px;
}

.bgm-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  width: 100%;
  padding: 0.4375rem 0.625rem;
  border-radius: 0.375rem;
  border: 1px solid var(--border);
  background: var(--paper);
  color: var(--text);
  font-family: inherit;
  font-size: 0.75rem;
  cursor: pointer;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
}

.bgm-select-trigger:disabled {
  opacity: 0.5;
  cursor: wait;
}

.bgm-select-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bgm-select-caret {
  font-size: 0.625rem;
  color: var(--muted);
  transition: transform 0.15s;
}

.bgm-select-trigger.open .bgm-select-caret {
  transform: rotate(180deg);
}

.bgm-select-menu {
  position: absolute;
  bottom: calc(100% + 0.25rem);
  right: 0;
  min-width: 100%;
  max-width: 200px;
  max-height: 220px;
  overflow-y: auto;
  margin: 0;
  padding: 0.25rem;
  list-style: none;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
  z-index: 50;
}

.bgm-select-option {
  padding: 0.5rem 0.625rem;
  border-radius: 0.25rem;
  font-size: 0.8125rem;
  color: var(--text);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bgm-select-option:hover,
.bgm-select-option:active {
  background: var(--paper);
}

.bgm-select-option.selected {
  background: rgba(45, 106, 63, 0.1);
  color: var(--green);
  font-weight: 500;
}
</style>
