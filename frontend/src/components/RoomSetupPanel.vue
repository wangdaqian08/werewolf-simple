<template>
  <div class="create-wrap" data-testid="room-setup-panel">
    <!-- Both pages stay mounted (v-show): one draft board, kept while the host switches pages. -->
    <div v-show="view === 'main'" class="create-card">
      <div class="top-row">
        <button class="back-btn" @click="emit('done')">{{ $t('setup.backRoom') }}</button>
        <LanguageToggle inline />
      </div>
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

      <!-- Role configuration: summary here, the full list on its own page -->
      <div class="field-lbl">{{ $t('setup.roleConfig') }}</div>
      <RoleComposition
        :total-players="totalPlayers"
        :wolf-count="wolfCount"
        :roles="compositionRoles"
      />

      <button
        type="button"
        class="btn btn-secondary"
        data-testid="open-role-select"
        @click="view = 'roles'"
      >
        {{ $t('setup.chooseRoles') }}
      </button>

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

    <!-- Page 2: choose roles. Grey = off, colour = on; a role that cannot turn on says why when tapped. -->
    <div v-show="view === 'roles'" class="create-card" data-testid="role-select-page">
      <div class="top-row">
        <button class="back-btn" @click="view = 'main'">{{ $t('setup.back') }}</button>
        <LanguageToggle inline />
      </div>
      <div class="create-header">
        <div class="section-lbl">{{ $t('setup.title') }}</div>
        <h2 class="create-title">{{ $t('setup.rolesTitle') }}</h2>
      </div>

      <div class="balance-note count-bar" data-testid="role-count-bar">
        {{ $t('setup.countPlayers', { n: totalPlayers }) }} ·
        {{ $t('setup.countWolves', { n: wolfCount }) }} ·
        <span :class="{ 'count-full': enabledGodCount >= godRange.max }" data-testid="god-range">
          {{ $t('setup.countGods', { n: enabledGodCount, min: godRange.min, max: godRange.max }) }}
        </span>
        · {{ $t('setup.countVillagers', { n: villagerCount }) }}
      </div>

      <template v-for="group in ROLE_GROUPS" :key="group.label">
        <div class="field-lbl group-lbl">{{ $t(group.label) }}</div>
        <div class="role-grid">
          <div v-for="id in group.ids" :key="id" :class="cardClass(id)" class="role-card">
            <button
              type="button"
              class="card-main"
              :data-testid="`role-toggle-${id}`"
              :aria-pressed="isEnabled(id)"
              :aria-disabled="!roleDefinition(id)?.required && !canToggle(id)"
              :title="toggleHint(id)"
              @click="onCardTap(id)"
            >
              <span class="card-emoji">{{ roleDefinition(id)?.emoji }}</span>
              <span class="card-name">{{ roleName(id) }}</span>
              <span v-if="cardNote(id)" class="card-note" :data-testid="`role-hint-${id}`">
                {{ cardNote(id) }}
              </span>
            </button>
            <button
              type="button"
              class="card-info"
              :data-testid="`role-info-${id}`"
              :aria-label="$t('roleCard.info')"
              @click="openRole = id"
            >
              i
            </button>
          </div>
        </div>
      </template>

      <button
        type="button"
        class="btn btn-primary"
        data-testid="role-select-done"
        @click="view = 'main'"
      >
        {{ $t('setup.confirm') }}
      </button>
    </div>

    <RoleCardSheet
      v-if="openRole"
      :role="openRole"
      :title="$t('roleCard.info')"
      @close="openRole = null"
    />
  </div>
</template>

<script lang="ts" setup>
import { t } from '@/i18n'
import LanguageToggle from '@/components/LanguageToggle.vue'
import { computed, ref, watch } from 'vue'
import axios from 'axios'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import type { Room } from '@/types'
import {
  BLOCKED_WITH,
  EXCLUSIVE_WITH,
  ROLE_DEFINITIONS,
  isWolfRole,
  roleDefinition,
  roleName,
} from '@/utils/roleDefinitions'
import { wolfBounds } from '@/utils/wolfBounds'
import RoleComposition from '@/components/RoleComposition.vue'
import ShareButton from '@/components/ShareButton.vue'
import RoleCardSheet from '@/components/RoleCardSheet.vue'
import { ElMessage } from 'element-plus'

// Page 2 of room creation, and the host's "edit players & roles" from the room.
const props = defineProps<{ room: Room }>()
const emit = defineEmits<{ done: [] }>()
const roomStore = useRoomStore()
const openRole = ref<string | null>(null)
const view = ref<'main' | 'roles'>('main')
// The pages swap with v-show, so the document keeps its scroll: start each page at the top.
watch(view, () => window.scrollTo(0, 0))

const MIN_PLAYERS = 6
// 15 = 5 wolves / 5 gods / 5 villagers, the largest board the current roles balance
// (up to 7 gods are possible: GOD_IDS.length - 1, as SEER/MEDIUM are mutually exclusive).
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

// Board limits (UI policy): gods ∈ [wolves − 1, wolves + 1] (at most 7: GOD_IDS.length - 1, SEER/MEDIUM exclusive) and
// always leave ≥ 1 villager seat, and a special wolf (白狼王 / 黑狼王 / 機械狼, any
// may be on) needs 9+ players and each a plain fellow wolf: wolves ≥ specials + 1
// (the backend enforces these rules too).
const GOD_IDS = ROLE_DEFINITIONS.filter((r) => !r.required && !r.wolf).map((r) => r.id)
const SPECIAL_WOLF_IDS = ROLE_DEFINITIONS.filter((r) => !r.required && r.wolf).map((r) => r.id)
const SPECIAL_WOLF_MIN_PLAYERS = 9
function blockedBy(roleId: string): boolean {
  return (BLOCKED_WITH[roleId] ?? []).some((id) => enabledOptional.value.has(id))
}
const names = (ids: string[]) => ids.map((id) => roleName(id)).join('、')
const blockers = (id: string) =>
  (BLOCKED_WITH[id] ?? []).filter((b) => enabledOptional.value.has(b))
// Off and blocked: it cannot be turned on. (Both can be on only in a room saved before the rule.)
function isBlocked(roleId: string): boolean {
  return !enabledOptional.value.has(roleId) && blockedBy(roleId)
}

const enabledSpecialWolfCount = computed(
  () => SPECIAL_WOLF_IDS.filter((id) => enabledOptional.value.has(id)).length,
)

// Optional wolf roles (the special wolves) take a wolf seat, so they are not gods.
const enabledGodCount = computed(
  () => Array.from(enabledOptional.value).filter((id) => !isWolfRole(id)).length,
)
const godRange = computed(() => ({
  min: Math.max(0, wolfCount.value - 1),
  // -1: the seer and the medium are exclusive, so one god id is never available; a blocked god is not either.
  max: Math.min(
    GOD_IDS.filter((id) => !isBlocked(id)).length - 1,
    wolfCount.value + 1,
    totalPlayers.value - wolfCount.value - 1,
  ),
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
    if (!roles.has(EXCLUSIVE_WITH[id] ?? '') && !isBlocked(id)) roles.add(id)
  }
}

function isEnabled(roleId: string): boolean {
  const role = ROLE_DEFINITIONS.find((r) => r.id === roleId)
  if (role?.required) return true
  return enabledOptional.value.has(roleId)
}

function canToggle(roleId: string): boolean {
  const on = enabledOptional.value.has(roleId)
  if (isBlocked(roleId)) return false
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
  if (isBlocked(roleId)) {
    const other = names(blockers(roleId))
    return t('setup.exclusiveReason', { role: roleName(roleId), other })
  }
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

const ROLE_GROUPS = [
  { label: 'setup.groupWolves', ids: ['WEREWOLF', ...SPECIAL_WOLF_IDS] },
  { label: 'setup.groupGods', ids: GOD_IDS },
  { label: 'setup.groupVillagers', ids: ['VILLAGER'] },
]
const villagerCount = computed(() => totalPlayers.value - wolfCount.value - enabledGodCount.value)

// Off and cannot be turned on: blocked by an exclusive role, too few players, or 神職 full.
function cardLocked(id: string): boolean {
  return !roleDefinition(id)?.required && !enabledOptional.value.has(id) && !canToggle(id)
}
function cardClass(id: string) {
  if (isEnabled(id)) {
    const camp = isWolfRole(id) ? 'camp-wolf' : id === 'VILLAGER' ? 'camp-village' : 'camp-god'
    return ['card-on', camp]
  }
  if (isBlocked(id)) return 'card-blocked'
  return cardLocked(id) ? 'card-locked' : 'card-off'
}
function cardNote(id: string): string {
  if (id === 'VILLAGER') return t('setup.villagerAuto', { n: villagerCount.value })
  if (id === 'WEREWOLF') return t('setup.required')
  if (isBlocked(id)) return t('setup.exclusiveTag')
  const excludes = BLOCKED_WITH[id]
  if (enabledOptional.value.has(id) && excludes)
    return t('setup.excludes', { roles: names(excludes) })
  if (!enabledOptional.value.has(id) && enabledOptional.value.has(EXCLUSIVE_WITH[id] ?? ''))
    return t('setup.pairSwap')
  return cardLocked(id) ? '🔒' : ''
}
// Tap = on/off. A required role opens its card; a role that cannot change says why.
function onCardTap(id: string) {
  if (roleDefinition(id)?.required) openRole.value = id
  else if (canToggle(id)) toggleRole(id)
  else ElMessage({ message: toggleHint(id), type: 'warning', duration: 2500, grouping: true })
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
/* Page 2: role cards, three per row */
/* The page scrolls, not .create-wrap (it only has a min-height): without this the
   shared overflow-y: auto makes .create-wrap the sticky container and the bar never pins. */
.create-wrap {
  overflow: visible;
}
.count-bar {
  position: sticky;
  top: 0;
  z-index: 1;
  font-size: 0.75rem;
  color: var(--text);
  background: var(--paper);
}
.count-full {
  color: var(--red);
  font-weight: 600;
}
.group-lbl {
  margin: 0.5rem 0 0;
}
.role-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
}
.role-card {
  position: relative;
  border-radius: 0.5rem;
  border: 1px solid var(--border-l);
  background: var(--paper);
  transition:
    filter 0.2s,
    opacity 0.2s,
    background 0.2s;
}
.card-main {
  width: 100%;
  min-height: 88px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.125rem;
  padding: 0.5rem 0.25rem;
  background: none;
  border: none;
  font: inherit;
  cursor: pointer;
}
.card-emoji {
  font-size: 1.75rem;
  line-height: 1.2;
}
.card-name {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text);
}
.card-note {
  font-size: 0.625rem;
  color: var(--muted);
  /* A two-role note wraps: break after 、, never inside a role name. */
  word-break: keep-all;
}
.card-info {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--muted);
  font:
    italic 600 0.625rem 'Noto Serif SC',
    serif;
  cursor: pointer;
  padding: 0;
}
.card-info::after {
  content: '';
  position: absolute;
  inset: -13px; /* 18px + 2 × 13px = 44px tap area */
}
/* Selected: full colour, camp border */
.card-on {
  background: var(--card);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
.camp-wolf {
  border: 2px solid var(--red);
  background: rgba(181, 37, 26, 0.06);
}
.camp-god {
  border: 2px solid var(--gold);
  background: rgba(160, 120, 48, 0.07);
}
.camp-village {
  border: 2px solid var(--green);
  background: rgba(45, 106, 63, 0.06);
}
/* Not selected: grey, still tappable */
.card-off {
  filter: grayscale(1);
  opacity: 0.6;
}
/* Exclusive / locked: deeper grey */
.card-blocked,
.card-locked {
  filter: grayscale(1);
  opacity: 0.3;
}
.card-blocked {
  background: repeating-linear-gradient(
    -45deg,
    var(--paper),
    var(--paper) 6px,
    var(--border) 6px,
    var(--border) 8px
  );
}

.invite-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}
</style>
