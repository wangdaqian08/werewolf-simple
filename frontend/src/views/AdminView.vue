<template>
  <div class="admin-wrap">
    <div class="admin-card">
      <button class="back-btn" data-testid="admin-back-btn" @click="router.push('/account')">
        {{ $t('common.back') }}
      </button>

      <h1 class="title">{{ $t('admin.title') }}</h1>

      <p v-if="loading" class="note">{{ $t('common.loadingShort') }}</p>
      <p v-else-if="!isAdmin" class="note" data-testid="admin-not-admin">
        {{ $t('admin.notAdmin') }}
      </p>

      <template v-else>
        <!-- ── 1. find the player ─────────────────────────────────────── -->
        <form class="search-row" @submit.prevent="search">
          <input
            v-model="query"
            class="input"
            type="search"
            :placeholder="$t('admin.searchPlaceholder')"
            data-testid="admin-search-input"
          />
          <button class="btn btn-secondary search-btn" type="submit" data-testid="admin-search-btn">
            {{ $t('admin.search') }}
          </button>
        </form>

        <p v-if="searched && players.length === 0" class="note" data-testid="admin-no-match">
          {{ $t('admin.noMatch') }}
        </p>
        <template v-else-if="players.length > 0">
          <div class="label">{{ $t('admin.choose') }}</div>
          <ul class="players">
            <li v-for="p in players" :key="p.userId">
              <button
                class="player"
                :class="{ 'player-selected': selected?.userId === p.userId }"
                :data-testid="`admin-player-${p.userId}`"
                @click="selected = p"
              >
                <span class="player-main">
                  <span class="player-name">{{ p.nickname }}</span>
                  <span class="player-id">{{ p.userId }}</span>
                </span>
                <span class="player-balance">◈ {{ p.balance }}</span>
              </button>
            </li>
          </ul>
        </template>

        <!-- ── 2. amount + PIN, confirmed by the backend ──────────────── -->
        <form v-if="selected" class="grant-form" @submit.prevent="grant">
          <label class="label" for="admin-amount">{{ $t('admin.amount') }}</label>
          <input
            id="admin-amount"
            v-model.number="amount"
            class="input"
            type="number"
            inputmode="numeric"
            min="1"
            max="100"
            data-testid="admin-amount-input"
          />
          <label class="label" for="admin-pin">{{ $t('admin.pin') }}</label>
          <input
            id="admin-pin"
            v-model="pin"
            class="input pin"
            type="password"
            inputmode="numeric"
            maxlength="4"
            autocomplete="off"
            data-testid="admin-pin-input"
          />
          <button
            class="btn btn-primary"
            type="submit"
            :class="{ 'is-loading': submitting }"
            :disabled="submitting || !amount || !pin"
            data-testid="admin-grant-btn"
          >
            {{ $t('admin.grant') }} · {{ selected.nickname }}
          </button>
        </form>

        <p v-if="message" class="success" data-testid="admin-success">{{ message }}</p>
        <p v-if="errorText" class="error-msg" data-testid="admin-error">{{ errorText }}</p>
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { isAxiosError } from 'axios'
import { adminService } from '@/services/adminService'
import type { AdminErrorCode, AdminPlayer } from '@/types'

const router = useRouter()
const { t } = useI18n()

const loading = ref(true)
const isAdmin = ref(false)
const query = ref('')
const searched = ref(false)
const players = ref<AdminPlayer[]>([])
const selected = ref<AdminPlayer | null>(null)
const amount = ref<number | ''>('')
const pin = ref('')
const submitting = ref(false)
const message = ref('')
const errorText = ref('')

const ERROR_CODES: AdminErrorCode[] = [
  'NOT_ADMIN',
  'PIN_NOT_CONFIGURED',
  'WRONG_PIN',
  'INVALID_AMOUNT',
  'PLAYER_NOT_FOUND',
]

function showError(e: unknown) {
  const code = isAxiosError(e) ? e.response?.data?.code : undefined
  errorText.value = ERROR_CODES.includes(code) ? t(`admin.error.${code}`) : t('admin.error.generic')
}

async function search() {
  message.value = ''
  errorText.value = ''
  selected.value = null
  try {
    players.value = await adminService.searchPlayers(query.value)
    searched.value = true
  } catch (e) {
    showError(e)
  }
}

async function grant() {
  if (!selected.value) return
  submitting.value = true
  message.value = ''
  errorText.value = ''
  try {
    const p = await adminService.grant(selected.value.userId, Number(amount.value), pin.value)
    message.value = t('admin.success', { name: p.nickname, n: amount.value, balance: p.balance })
    // Show the backend's new balance in the list; clear the PIN either way.
    players.value = players.value.map((x) => (x.userId === p.userId ? p : x))
    selected.value = p
    amount.value = ''
  } catch (e) {
    showError(e)
  } finally {
    pin.value = ''
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    isAdmin.value = await adminService.isAdmin()
  } catch {
    isAdmin.value = false
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.admin-wrap {
  display: flex;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
}

.admin-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem;
  width: 100%;
  max-width: 360px;
  align-self: flex-start;
}

.back-btn {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.8125rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  min-height: 44px;
  min-width: 44px;
  display: inline-flex;
  align-items: center;
  margin-bottom: 0.25rem;
  white-space: nowrap;
}

.title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.75rem;
  color: var(--red);
  text-align: center;
  margin: 0 0 1rem;
}

.note {
  text-align: center;
  color: var(--muted);
  font-size: 0.9375rem;
  margin: 1rem 0;
}

.search-row {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.input {
  flex: 1;
  min-width: 0;
  min-height: 44px;
  padding: 0 0.75rem;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: var(--card);
  color: var(--text);
  font-family: inherit;
  font-size: 1rem; /* 16px: iOS Safari zooms into smaller inputs */
}

.pin {
  letter-spacing: 0.5em;
}

.search-btn {
  width: auto;
  flex-shrink: 0;
  min-height: 44px;
  padding: 0 1rem;
}

.label {
  display: block;
  font-size: 0.8125rem;
  color: var(--muted);
  margin: 0.5rem 0 0.375rem;
}

.players {
  list-style: none;
  margin: 0 0 0.5rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.player {
  width: 100%;
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.player-selected {
  border: 2px solid var(--gold);
  background: rgba(160, 120, 48, 0.08);
}

.player-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.player-name {
  font-size: 0.9375rem;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player-id {
  font-size: 0.75rem;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.player-balance {
  flex-shrink: 0;
  color: var(--gold);
  font-weight: 600;
}

.grant-form {
  display: flex;
  flex-direction: column;
}

.grant-form .btn {
  margin-top: 1rem;
}

.success {
  color: var(--green);
  font-size: 0.875rem;
  text-align: center;
  margin: 0.75rem 0 0;
}

.error-msg {
  color: var(--red);
  font-size: 0.875rem;
  text-align: center;
  margin: 0.75rem 0 0;
}
</style>
