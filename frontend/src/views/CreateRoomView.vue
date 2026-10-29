<template>
  <div class="create-wrap">
    <div class="create-card">
      <!-- Back button -->
      <button class="back-btn" @click="router.push({ name: 'lobby' })">
        {{ $t('common.back') }}
      </button>
      <!-- Header -->
      <div class="create-header">
        <div class="section-lbl">{{ $t('common.host') }}</div>
        <h2 class="create-title">{{ $t('create.title') }}</h2>
      </div>

      <!-- Game settings -->
      <div class="field-lbl">{{ $t('create.settings') }}</div>
      <div class="role-list">
        <div class="role-row row-on">
          <span class="role-emoji">⭐</span>
          <div class="role-names">
            <span class="role-name">{{ $t('game.phase.sheriff') }}</span>
          </div>
          <button
            :class="hasSheriff ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            @click="hasSheriff = !hasSheriff"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="witchSelfSaveAllowed ? 'row-on' : 'row-off'">
          <span class="role-emoji">🧙‍♀️</span>
          <div class="role-names">
            <span class="role-name">{{ $t('create.witchSelfSave') }}</span>
            <span class="win-cond-desc">
              {{
                witchSelfSaveAllowed ? t('create.witchSelfSaveOn') : t('create.witchSelfSaveOff')
              }}
            </span>
          </div>
          <button
            :class="witchSelfSaveAllowed ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            data-testid="witchSelfSave-toggle"
            :data-witch-self-save="witchSelfSaveAllowed"
            @click="witchSelfSaveAllowed = !witchSelfSaveAllowed"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="noKillAllowed ? 'row-on' : 'row-off'">
          <span class="role-emoji">🔪</span>
          <div class="role-names">
            <span class="role-name">{{ $t('create.noKill') }}</span>
            <span class="win-cond-desc">
              {{ noKillAllowed ? t('create.noKillOn') : t('create.noKillOff') }}
            </span>
          </div>
          <button
            :class="noKillAllowed ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            data-testid="noKill-toggle"
            :data-no-kill="noKillAllowed"
            @click="noKillAllowed = !noKillAllowed"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="guardSkipAllowed ? 'row-on' : 'row-off'">
          <span class="role-emoji">🛡️</span>
          <div class="role-names">
            <span class="role-name">{{ $t('create.noProtect') }}</span>
            <span class="win-cond-desc">
              {{ guardSkipAllowed ? t('create.noProtectOn') : t('create.noProtectOff') }}
            </span>
          </div>
          <button
            :class="guardSkipAllowed ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            data-testid="guardSkip-toggle"
            :data-guard-skip="guardSkipAllowed"
            @click="guardSkipAllowed = !guardSkipAllowed"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="perksAllowed ? 'row-on' : 'row-off'">
          <span class="role-emoji">🛡</span>
          <div class="role-names">
            <span class="role-name">{{ $t('account.perks') }}</span>
            <span class="win-cond-desc">
              {{ perksAllowed ? t('create.perksOn') : t('create.perksOff') }}
            </span>
          </div>
          <button
            :class="perksAllowed ? 'toggle-on' : 'toggle-off'"
            class="toggle"
            data-testid="perksAllowed-toggle"
            :data-perks-allowed="perksAllowed"
            @click="perksAllowed = !perksAllowed"
          >
            <span class="toggle-thumb" />
          </button>
        </div>
        <div class="role-row" :class="winCondition === 'HARD_MODE' ? 'row-wolf' : 'row-on'">
          <span class="role-emoji">⚔️</span>
          <div class="role-names">
            <span class="role-name">{{ $t('create.winCondition') }}</span>
            <span class="win-cond-desc">
              {{ winCondition === 'CLASSIC' ? t('create.winClassic') : t('create.winHard') }}
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
            <span class="role-name">{{ $t('create.bgm') }}</span>
            <span class="win-cond-desc">{{ $t('create.bgmDesc') }}</span>
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
                v-for="track in bgmTracks"
                :key="track.id ?? '__none__'"
                role="option"
                :data-filename="track.filename ?? ''"
                :aria-selected="bgmTrack === (track.filename ?? null)"
                :class="['bgm-select-option', { selected: bgmTrack === (track.filename ?? null) }]"
                @click="onSelectBgm(track.filename ?? null)"
              >
                {{ track.filename ? track.displayName : $t('create.bgmNone') }}
              </li>
            </ul>
          </div>
        </div>
      </div>

      <button
        :disabled="loading"
        class="btn btn-primary"
        data-testid="create-room-submit"
        @click="handleCreate"
      >
        {{ loading ? $t('create.creating') : $t('create.title') }}
      </button>

      <p v-if="error" class="error-msg">{{ error }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { t } from '@/i18n'
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

// Page 1 creates the room with the default board; the host sets the real
// headcount and roles on page 2 (RoomSetupPanel) while players join.
const DEFAULT_BOARD = {
  totalPlayers: 9,
  wolfCount: 3,
  roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
}

const hasSheriff = ref(true)
const witchSelfSaveAllowed = ref(true)
const perksAllowed = ref(true)
const noKillAllowed = ref(false)
const guardSkipAllowed = ref(false)
const winCondition = ref<WinConditionMode>('CLASSIC')

const bgmTracks = ref<AudioTrack[]>([{ id: null, filename: null, displayName: 'None' }])
const bgmTrack = ref<string | null>(null)
const bgmLoading = ref(false)
const bgmOpen = ref(false)
const bgmSelectRef = ref<HTMLElement | null>(null)

const bgmDisplayName = computed(() => {
  const match = bgmTracks.value.find((track) => (track.filename ?? null) === bgmTrack.value)
  return match?.filename ? match.displayName : t('create.bgmNone')
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

async function handleCreate() {
  loading.value = true
  error.value = ''
  try {
    const req: import('@/types').CreateRoomRequest = {
      config: {
        ...DEFAULT_BOARD,
        hasSheriff: hasSheriff.value,
        winCondition: winCondition.value,
        bgmTrack: bgmTrack.value,
        witchSelfSaveAllowed: witchSelfSaveAllowed.value,
        perksAllowed: perksAllowed.value,
        noKillAllowed: noKillAllowed.value,
        guardSkipAllowed: guardSkipAllowed.value,
      },
    }
    // Carry the per-room display-name override that the lobby may have set.
    if (userStore.displayName) req.nickname = userStore.displayName
    const room = await roomService.createRoom(req)
    roomStore.setRoom(room)
    router.push({ name: 'room', params: { roomId: room.roomId }, query: { setup: '1' } })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : t('create.failed')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped src="../assets/room-form.css"></style>

<style scoped>
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
  min-height: 44px;
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
