<template>
  <div class="lobby-wrap">
    <div class="lobby-card">
      <h1 class="title">狼人杀</h1>
      <p class="subtitle">Werewolf</p>

      <!-- Logged-in identity card ─────────────────────────────────────── -->
      <div v-if="userStore.isLoggedIn" class="identity" data-testid="signed-in-as">
        <div class="identity-avatar" :class="{ 'has-image': !!safeAvatarUrl }">
          <img v-if="safeAvatarUrl" :src="safeAvatarUrl" alt="" @error="onAvatarError" />
          <span v-else>{{ (userStore.nickname ?? '?').charAt(0) }}</span>
        </div>
        <div class="identity-text">
          <div class="signed-as">已登录 / Signed in as</div>
          <div class="identity-name">{{ userStore.nickname }}</div>
        </div>
        <button class="logout-link" data-testid="logout-link" @click="handleLogout">
          登出 / Logout
        </button>
      </div>

      <!-- OAuth buttons (only when not logged in) ───────────────────────── -->
      <div v-if="!userStore.isLoggedIn && hasAnyOAuth" class="oauth-section">
        <button
          v-if="providers.google"
          class="btn btn-oauth-google"
          data-testid="oauth-google"
          :disabled="loading"
          @click="signInWithGoogle"
        >
          <svg
            class="oauth-logo"
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M22.5 12.27c0-.86-.08-1.68-.22-2.47H12v4.68h5.94c-.26 1.4-1.05 2.59-2.24 3.39v2.82h3.62c2.12-1.96 3.34-4.84 3.34-8.42z"
              fill="#4285F4"
            />
            <path
              d="M12 23c3.02 0 5.55-1 7.4-2.71l-3.62-2.82c-1 .67-2.27 1.07-3.78 1.07-2.91 0-5.37-1.96-6.25-4.6H1.99v2.9C3.83 20.51 7.65 23 12 23z"
              fill="#34A853"
            />
            <path
              d="M5.75 13.94c-.22-.67-.35-1.39-.35-2.13s.13-1.46.35-2.13V6.78H1.99A11 11 0 0 0 1 12c0 1.78.43 3.46 1.19 4.94l3.56-3z"
              fill="#FBBC05"
            />
            <path
              d="M12 5.27c1.64 0 3.11.56 4.27 1.67l3.21-3.21C17.55 1.92 15.02 1 12 1 7.65 1 3.83 3.49 1.99 6.78l3.76 2.9c.88-2.64 3.34-4.6 6.25-4.6z"
              fill="#EA4335"
            />
          </svg>
          使用 Google 登录 / Sign in with Google
        </button>

        <button
          v-if="providers.wechat"
          class="btn btn-oauth-wechat"
          data-testid="oauth-wechat"
          :disabled="loading"
          @click="signInWithWechat"
        >
          <svg
            class="oauth-logo"
            viewBox="0 0 24 24"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M8.5 4C4.36 4 1 6.92 1 10.5c0 2.06 1.13 3.9 2.88 5.07L3 18l2.62-1.4c.85.21 1.74.32 2.69.32h.42c-.27-.74-.41-1.52-.41-2.34 0-3.42 3.21-6.2 7.18-6.2.21 0 .41.01.62.03C15.39 5.74 12.27 4 8.5 4z"
              fill="#fff"
            />
            <path
              d="M22 14.5c0-2.96-2.84-5.36-6.36-5.36-3.61 0-6.46 2.4-6.46 5.36 0 2.96 2.85 5.36 6.46 5.36.78 0 1.53-.11 2.23-.31L20.18 21l-.5-1.91C21.04 18.04 22 16.36 22 14.5z"
              fill="#fff"
            />
          </svg>
          微信登录 / Sign in with WeChat
        </button>

        <div class="oauth-divider">or</div>
      </div>

      <!-- Authenticated → straight to Create / Join ─────────────────────── -->
      <div v-if="userStore.isLoggedIn" class="actions">
        <button
          :class="{ 'is-loading': loading }"
          :disabled="loading"
          class="btn btn-primary"
          data-testid="create-room-btn"
          @click="handleCreateRoom"
        >
          创建房间 / Create Room
        </button>
        <div class="divider">or</div>
        <div class="join-row">
          <input
            v-model="roomCode"
            class="input input-code"
            data-testid="room-code-input"
            maxlength="6"
            placeholder="Room code"
            type="text"
          />
          <button
            :class="{ 'is-loading': loading }"
            :disabled="loading || !roomCode.trim()"
            class="btn btn-secondary join-btn"
            data-testid="join-room-btn"
            @click="handleJoinRoom"
          >
            加入 / Join
          </button>
        </div>
      </div>

      <!-- Guest section. Collapsed under a toggle when at least one OAuth
           provider is enabled (encourages OAuth); shown directly otherwise
           so users aren't forced through an unnecessary click. ─────────── -->
      <div v-else class="guest-section">
        <button
          v-if="hasAnyOAuth"
          class="guest-toggle"
          data-testid="guest-toggle"
          type="button"
          @click="showGuest = !showGuest"
        >
          {{ showGuest ? '收起 / Hide' : '继续以访客身份 / Continue as guest' }}
        </button>

        <div v-if="!hasAnyOAuth || showGuest" class="guest-form">
          <div class="field">
            <label>昵称 / Nickname</label>
            <input
              v-model="nickname"
              class="input"
              data-testid="nickname-input"
              maxlength="32"
              placeholder="Enter your nickname"
              type="text"
              @keyup.enter="handleCreateRoom"
            />
          </div>

          <div class="actions">
            <button
              :class="{ 'is-loading': loading }"
              :disabled="loading || !nickname.trim()"
              class="btn btn-primary"
              data-testid="create-room-btn"
              @click="handleCreateRoom"
            >
              创建房间 / Create Room
            </button>
            <div class="divider">or</div>
            <div class="join-row">
              <input
                v-model="roomCode"
                class="input input-code"
                data-testid="room-code-input"
                maxlength="6"
                placeholder="Room code"
                type="text"
              />
              <button
                :class="{ 'is-loading': loading }"
                :disabled="loading || !nickname.trim() || !roomCode.trim()"
                class="btn btn-secondary join-btn"
                data-testid="join-room-btn"
                @click="handleJoinRoom"
              >
                加入 / Join
              </button>
            </div>
          </div>
        </div>
      </div>

      <p v-if="error" class="error-msg">{{ error }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import axios from 'axios'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import { userService } from '@/services/userService'
import { buildGoogleAuthUrl, generateOAuthState } from '@/utils/oauth'
import type { ProvidersResponse } from '@/types'

const router = useRouter()
const userStore = useUserStore()
const roomStore = useRoomStore()

const nickname = ref(userStore.nickname ?? '')
const roomCode = ref('')
const loading = ref(false)
const error = ref('')
const showGuest = ref(false)
const providers = ref<ProvidersResponse>({ google: null, wechat: null, guest: true })
const avatarFailed = ref(false)

const safeAvatarUrl = computed(() => {
  const url = userStore.avatarUrl
  if (!url || avatarFailed.value) return null
  return url.startsWith('https://') ? url : null
})

const hasAnyOAuth = computed(() => !!providers.value.google || !!providers.value.wechat)

function onAvatarError() {
  avatarFailed.value = true
}

onMounted(async () => {
  try {
    providers.value = await userService.getProviders()
  } catch {
    // Backend down / 404 — keep guest-only fallback. Don't surface this to
    // the user; the guest flow still works.
  }
})

async function ensureGuestSession() {
  if (userStore.isLoggedIn) return
  if (!nickname.value.trim()) throw new Error('Nickname required')
  await userStore.login(nickname.value.trim())
}

async function handleCreateRoom() {
  error.value = ''
  loading.value = true
  try {
    await ensureGuestSession()
    router.push({ name: 'create-room' })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to connect. Try again.'
  } finally {
    loading.value = false
  }
}

async function handleJoinRoom() {
  if (!roomCode.value.trim()) return
  error.value = ''
  loading.value = true
  try {
    await ensureGuestSession()
    const room = await roomService.joinRoom({ roomCode: roomCode.value.trim().toUpperCase() })
    roomStore.setRoom(room)
    router.push({ name: 'room', params: { roomId: room.roomId } })
  } catch (e: unknown) {
    if (axios.isAxiosError(e) && (e.response?.status === 404 || e.response?.status === 400)) {
      error.value = 'Room not found. Check the code.'
    } else {
      error.value = e instanceof Error ? e.message : 'Failed to join. Try again.'
    }
  } finally {
    loading.value = false
  }
}

async function handleLogout() {
  await userStore.logout()
  // Reset local form state.
  nickname.value = ''
  roomCode.value = ''
  showGuest.value = false
  avatarFailed.value = false
}

function signInWithGoogle() {
  if (!providers.value.google) return
  const state = generateOAuthState()
  const redirectUri = `${window.location.origin}/auth/callback/google`
  window.location.href = buildGoogleAuthUrl({
    state,
    redirectUri,
    clientId: providers.value.google.clientId,
  })
}

function signInWithWechat() {
  if (!providers.value.wechat) return
  const state = generateOAuthState()
  const redirectUri = `${window.location.origin}/auth/callback/wechat`
  // WeChat 网页授权: appid + redirect + scope=snsapi_login + state.
  // Anchor #wechat_redirect required by WeChat's docs.
  const params = new URLSearchParams({
    appid: providers.value.wechat.appId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'snsapi_login',
    state,
  })
  window.location.href = `https://open.weixin.qq.com/connect/qrconnect?${params.toString()}#wechat_redirect`
}
</script>

<style scoped>
.lobby-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
}

.lobby-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 360px;
}

.title {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  color: var(--red);
  text-align: center;
  margin: 0 0 0.25rem;
}

.subtitle {
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0 0 1.5rem;
  letter-spacing: 0.1em;
}

/* ── identity ───────────────────────────────────────────────────────── */
.identity {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.75rem;
  padding: 0.75rem;
  margin-bottom: 1rem;
}

.identity-avatar {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--paper);
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  color: var(--gold);
  overflow: hidden;
}

.identity-avatar.has-image {
  background: transparent;
}

.identity-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.identity-text {
  flex: 1;
  min-width: 0;
}

.signed-as {
  font-size: 0.75rem;
  color: var(--muted);
  letter-spacing: 0.05em;
}

.identity-name {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.logout-link {
  background: transparent;
  border: none;
  color: var(--muted);
  font-size: 0.75rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  text-decoration: underline;
}

.logout-link:hover {
  color: var(--red);
}

/* ── OAuth section ──────────────────────────────────────────────────── */
.oauth-section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  margin-bottom: 1rem;
}

.btn-oauth-google,
.btn-oauth-wechat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.625rem;
  border-radius: 0.5rem;
  padding: 0.75rem 1rem;
  font-family: inherit;
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  transition:
    opacity 0.15s,
    background 0.15s;
  min-height: 48px;
  border: 1px solid var(--border);
  width: 100%;
}

.btn-oauth-google {
  background: #ffffff;
  color: #1a140c;
}

.btn-oauth-google:hover:not(:disabled) {
  background: #f8f8f8;
}

.btn-oauth-wechat {
  background: #1aad19;
  color: #ffffff;
  border-color: #1aad19;
}

.btn-oauth-wechat:hover:not(:disabled) {
  background: #199c19;
}

.btn-oauth-google:disabled,
.btn-oauth-wechat:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.oauth-logo {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.oauth-divider {
  text-align: center;
  color: var(--muted);
  font-size: 0.75rem;
  margin: 0.25rem 0 0;
}

/* ── Guest section ──────────────────────────────────────────────────── */
.guest-section {
  margin-top: 0.5rem;
}

.guest-toggle {
  display: block;
  width: 100%;
  background: transparent;
  border: none;
  color: var(--muted);
  font-size: 0.875rem;
  font-family: inherit;
  cursor: pointer;
  padding: 0.5rem;
  text-decoration: underline;
}

.guest-toggle:hover {
  color: var(--text);
}

.guest-form {
  margin-top: 0.75rem;
}

/* ── Form (shared) ──────────────────────────────────────────────────── */
.field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-bottom: 1rem;
}

.field label {
  font-size: 0.875rem;
  color: var(--muted);
}

.input {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  padding: 0.625rem 0.75rem;
  font-size: 1rem;
  font-family: inherit;
  color: var(--text);
  outline: none;
  width: 100%;
  transition: border-color 0.15s;
}

.input:focus {
  border-color: var(--red);
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

.divider {
  text-align: center;
  color: var(--muted);
  font-size: 0.75rem;
}

.join-row {
  display: flex;
  gap: 0.5rem;
}

.input-code {
  flex: 1;
  text-transform: uppercase;
  letter-spacing: 0.15em;
  font-weight: 600;
}

.join-btn {
  width: auto;
  padding: 0 1rem;
  white-space: nowrap;
}

.error-msg {
  color: var(--red);
  font-size: 0.875rem;
  text-align: center;
  margin-top: 0.75rem;
}
</style>
