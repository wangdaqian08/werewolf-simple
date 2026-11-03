<template>
  <div class="create-wrap" data-testid="room-setup-panel">
    <div class="create-card">
      <button class="back-btn" @click="emit('done')">{{ $t('setup.backRoom') }}</button>
      <div class="create-header">
        <div class="section-lbl">{{ $t('common.host') }}</div>
        <h2 class="create-title">{{ $t('setup.title') }}</h2>
      </div>

      <!-- Invite: share while still deciding the headcount -->
      <div class="stepper-card invite-row">
        <div>
          <div class="field-lbl">{{ $t('setup.roomCode') }}</div>
          <div class="stepper-num" data-testid="setup-room-code">{{ room.roomCode }}</div>
        </div>
        <ShareButton :room-code="room.roomCode" />
      </div>
      <div class="balance-note" data-testid="setup-joined-count">
        {{ $t('setup.joined', { n: joined }) }}
      </div>

      <!-- Player count stepper -->
      <div class="stepper-card">
        <div class="field-lbl">{{ $t('setup.players') }}</div>
        <div class="stepper-row">
          <button
            :disabled="totalPlayers <= minPlayers"
            class="stepper-btn stepper-minus"
            data-testid="player-count-decrement"
            @click="decrement"
          >
            −
          </button>
          <div class="stepper-value">
            <span class="stepper-num" data-testid="player-count-value">{{ totalPlayers }}</span>
            <span class="stepper-range">{{ minPlayers }} – {{ MAX_PLAYERS }}</span>
          </div>
          <button
            :disabled="totalPlayers >= MAX_PLAYERS"
            class="stepper-btn stepper-plus"
            data-testid="player-count-increment"
            @click="increment"
          >
            +
          </button>
        </div>
      </div>

      <!-- Wolf-count stepper -->
      <div class="stepper-card">
        <div class="field-lbl">{{ $t('setup.wolves') }}</div>
        <div class="stepper-row">
          <button
            :disabled="!canRemoveWolf"
            class="stepper-btn stepper-minus"
            data-testid="wolf-count-decrement"
            @click="wolfDecrement"
          >
            −
          </button>
          <div class="stepper-value">
            <span class="stepper-num" data-testid="wolf-count-value">{{ wolfCount }}</span>
            <span class="stepper-range">{{ currentBounds.min }} – {{ currentBounds.max }}</span>
          </div>
          <button
            :disabled="!canAddWolf"
            class="stepper-btn stepper-plus"
            data-testid="wolf-count-increment"
            @click="wolfIncrement"
          >
            +
          </button>
        </div>
      </div>

      <!-- Role configuration -->
      <div class="field-lbl">{{ $t('setup.roleConfig') }}</div>
      <div class="balance-note">
        {{ $t('setup.remaining') }}
      </div>
      <div class="balance-note" data-testid="god-range">
        {{ $t('setup.gods', { min: godRange.min, max: godRange.max }) }}
      </div>

      <div class="role-list">
        <div
          v-for="role in ROLE_DEFINITIONS"
          :key="role.id"
          :class="roleRowClass(role)"
          class="role-row"
        >
          <button
            type="button"
            class="role-info-btn"
            :data-testid="`role-info-${role.id}`"
            @click="openRole = role.id"
          >
            <span class="role-emoji">{{ role.emoji }}</span>
            <span class="role-names">
              <span
                :class="{ 'role-name-muted': !role.required && !isEnabled(role.id) }"
                class="role-name"
              >
                {{ roleName(role.id) }} {{ roleTag(role.id) }}
              </span>
              <span
                v-if="SPECIAL_WOLF_IDS.includes(role.id)"
                class="win-cond-desc"
                :data-testid="`role-hint-${role.id}`"
              >
                {{ $t('setup.specialWolfMinPlayers', { n: SPECIAL_WOLF_MIN_PLAYERS }) }}
              </span>
              <span
                v-else-if="enabledOptional.has(EXCLUSIVE_WITH[role.id] ?? '')"
                class="win-cond-desc"
                :data-testid="`role-hint-${role.id}`"
              >
                {{ $t('setup.mediumSeerExclusive') }}
              </span>
            </span>
          </button>
          <!-- Required roles: label only -->
          <span
            v-if="role.required"
            :class="role.id === 'WEREWOLF' ? 'req-wolf' : 'req-village'"
            class="req-label"
          >
            {{ $t('setup.required') }}
          </span>
          <!-- Optional roles: toggle -->
          <button
            v-else
            :class="isEnabled(role.id) ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            :data-testid="`role-toggle-${role.id}`"
            :disabled="!canToggle(role.id)"
            :title="toggleHint(role.id)"
            @click="toggleRole(role.id)"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
      </div>

      <RoleCardSheet
        v-if="openRole"
        :role="openRole"
        :title="$t('roleCard.info')"
        @close="openRole = null"
      />

      <RoleComposition
        :total-players="totalPlayers"
        :wolf-count="wolfCount"
        :roles="compositionRoles"
      />

      <button
        :disabled="saving"
        class="btn btn-primary"
        data-testid="room-setup-done"
        @click="save"
      >
        {{ saving ? $t('setup.saving') : $t('setup.done') }}
      </button>

      <p v-if="error" class="error-msg" data-testid="room-setup-error">{{ error }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import axios from 'axios'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import type { Room } from '@/types'
import {
  ROLE_DEFINITIONS,
  type RoleDefinition,
  isWolfRole,
  roleDefinition,
  roleName,
  roleTag,
} from '@/utils/roleDefinitions'
import { wolfBounds } from '@/utils/wolfBounds'
import RoleComposition from '@/components/RoleComposition.vue'
import ShareButton from '@/components/ShareButton.vue'
import RoleCardSheet from '@/components/RoleCardSheet.vue'

// Page 2 of room creation, and the host's "edit players & roles" from the room.
const props = defineProps<{ room: Room }>()
const emit = defineEmits<{ done: [] }>()
const roomStore = useRoomStore()
const openRole = ref<string | null>(null)

const MIN_PLAYERS = 6
// 15 = 5 wolves / 5 gods / 5 villagers, the largest board the current roles balance.
// Above 12 the day/night seat grids get a 4th row and scroll on 417×614 (accepted).
const MAX_PLAYERS = 15

// The panel edits a live room, so it can't drop below the players already in it.
const joined = computed(() => props.room.players.length)
const minPlayers = computed(() => Math.max(MIN_PLAYERS, joined.value))

const totalPlayers = ref(props.room.config.totalPlayers)
const wolfCount = ref(props.room.config.wolfCount ?? wolfBounds(totalPlayers.value).default)
const currentBounds = computed(() => wolfBounds(totalPlayers.value))

// Whenever the host changes total players, snap wolf count to the canonical
// default for that count (matches real-world 狼人杀 板子: 6p→2W, 9p→3W, 12p→4W).
// The host can still adjust wolves up/down within the new bounds afterward.
watch(totalPlayers, (n) => {
  wolfCount.value = wolfBounds(n).default
  fitRolesToBoard(n)
})

const enabledOptional = ref(
  new Set(props.room.config.roles.filter((id) => !roleDefinition(id)?.required)),
)

function increment() {
  if (totalPlayers.value < MAX_PLAYERS) totalPlayers.value++
}
function decrement() {
  if (totalPlayers.value > minPlayers.value) totalPlayers.value--
}

// Board limits (UI policy): gods ∈ [wolves − 1, wolves + 1] (only 5 exist) and
// always leave ≥ 1 villager seat, and a special wolf (白狼王 / 黑狼王 / 機械狼, any
// may be on) needs 9+ players and each a plain fellow wolf: wolves ≥ specials + 1
// (the backend enforces these rules too).
const GOD_IDS = ROLE_DEFINITIONS.filter((r) => !r.required && !r.wolf).map((r) => r.id)
const SPECIAL_WOLF_IDS = ROLE_DEFINITIONS.filter((r) => !r.required && r.wolf).map((r) => r.id)
const SPECIAL_WOLF_MIN_PLAYERS = 9
// The seer and the medium are mutually exclusive: turning one on turns the other off.
const EXCLUSIVE_WITH: Record<string, string> = { SEER: 'MEDIUM', MEDIUM: 'SEER' }

const enabledSpecialWolfCount = computed(
  () => SPECIAL_WOLF_IDS.filter((id) => enabledOptional.value.has(id)).length,
)

// Optional wolf roles (the special wolves) take a wolf seat, so they are not gods.
const enabledGodCount = computed(
  () => Array.from(enabledOptional.value).filter((id) => !isWolfRole(id)).length,
)
const godRange = computed(() => ({
  min: Math.max(0, wolfCount.value - 1),
  // -1: the seer and the medium are exclusive, so one god id is never available.
  max: Math.min(GOD_IDS.length - 1, wolfCount.value + 1, totalPlayers.value - wolfCount.value - 1),
}))

// One wolf more/less must keep the gods inside the new range.
const canAddWolf = computed(
  () =>
    wolfCount.value < currentBounds.value.max &&
    enabledGodCount.value >= wolfCount.value &&
    wolfCount.value + 1 + enabledGodCount.value < totalPlayers.value,
)
const canRemoveWolf = computed(
  () =>
    wolfCount.value > Math.max(currentBounds.value.min, enabledSpecialWolfCount.value + 1) &&
    enabledGodCount.value <= wolfCount.value,
)

function wolfIncrement() {
  if (canAddWolf.value) wolfCount.value++
}
function wolfDecrement() {
  if (canRemoveWolf.value) wolfCount.value--
}

// After a player-count change (wolves were just reset): drop gods from the end
// of the list / add from the front until they fit, and drop the special wolves below 9.
function fitRolesToBoard(n: number) {
  const roles = enabledOptional.value
  if (n < SPECIAL_WOLF_MIN_PLAYERS) SPECIAL_WOLF_IDS.forEach((id) => roles.delete(id))
  const enabledGods = () => GOD_IDS.filter((id) => roles.has(id))
  for (const id of [...GOD_IDS].reverse()) {
    if (enabledGods().length <= godRange.value.max) break
    roles.delete(id)
  }
  for (const id of GOD_IDS) {
    if (enabledGods().length >= godRange.value.min) break
    if (!roles.has(EXCLUSIVE_WITH[id] ?? '')) roles.add(id)
  }
}

function isEnabled(roleId: string): boolean {
  const role = ROLE_DEFINITIONS.find((r) => r.id === roleId)
  if (role?.required) return true
  return enabledOptional.value.has(roleId)
}

function canToggle(roleId: string): boolean {
  const on = enabledOptional.value.has(roleId)
  if (SPECIAL_WOLF_IDS.includes(roleId))
    return (
      on ||
      (totalPlayers.value >= SPECIAL_WOLF_MIN_PLAYERS &&
        wolfCount.value >= enabledSpecialWolfCount.value + 2)
    )
  // Swapping SEER <-> MEDIUM keeps the god count, so it is always allowed.
  if (!on && enabledOptional.value.has(EXCLUSIVE_WITH[roleId] ?? '')) return true
  return on
    ? enabledGodCount.value > godRange.value.min
    : enabledGodCount.value < godRange.value.max
}

function toggleHint(roleId: string): string {
  if (canToggle(roleId)) return ''
  if (SPECIAL_WOLF_IDS.includes(roleId))
    return totalPlayers.value < SPECIAL_WOLF_MIN_PLAYERS
      ? t('setup.needPlayers', { n: SPECIAL_WOLF_MIN_PLAYERS })
      : t('setup.specialWolvesNeedWolves', { n: enabledSpecialWolfCount.value + 2 })
  const { min, max } = godRange.value
  return enabledOptional.value.has(roleId)
    ? t('setup.atLeast', { n: min })
    : t('setup.atMost', { n: max })
}

function toggleRole(roleId: string) {
  if (!canToggle(roleId)) return
  if (enabledOptional.value.has(roleId)) enabledOptional.value.delete(roleId)
  else {
    enabledOptional.value.delete(EXCLUSIVE_WITH[roleId] ?? '')
    enabledOptional.value.add(roleId)
  }
}

function roleRowClass(role: RoleDefinition) {
  if (role.required) return role.id === 'WEREWOLF' ? 'row-wolf' : 'row-village'
  return isEnabled(role.id) ? 'row-on' : 'row-off'
}

const compositionRoles = computed(() => [
  'WEREWOLF',
  'VILLAGER',
  ...Array.from(enabledOptional.value),
])

const saving = ref(false)
const error = ref('')

async function save() {
  saving.value = true
  error.value = ''
  try {
    const updated = await roomService.updateBoard(props.room.roomId, {
      totalPlayers: totalPlayers.value,
      wolfCount: wolfCount.value,
      roles: ROLE_DEFINITIONS.filter((r) => isEnabled(r.id)).map((r) => r.id),
    })
    roomStore.setRoom(updated)
    emit('done')
  } catch (e: unknown) {
    const reason = axios.isAxiosError(e)
      ? (e.response?.data as { error?: string } | undefined)?.error
      : undefined
    error.value = reason ?? t('setup.saveFailed')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped src="../assets/room-form.css"></style>

<style scoped>
.role-info-btn {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 0.625rem;
  padding: 0;
  background: none;
  border: none;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.invite-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}
</style>
