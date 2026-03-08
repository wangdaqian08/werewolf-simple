<template>
  <div class="lobby-wrap">
    <div class="lobby-card">
      <!-- Title -->
      <h1 class="title">狼人杀</h1>
      <p class="subtitle">Werewolf</p>

      <!-- Nickname input -->
      <div class="field">
        <label>昵称 / Nickname</label>
        <input
          v-model="nickname"
          class="input"
          maxlength="16"
          placeholder="Enter your nickname"
          type="text"
          @keyup.enter="handleLogin"
        />
      </div>

      <!-- Actions -->
      <div class="actions">
        <button
          :disabled="loading || !nickname.trim()"
          class="btn btn-primary"
          @click="handleCreateRoom"
        >
          创建房间 / Create Room
        </button>
        <div class="divider">or</div>
        <div class="join-row">
          <input
            v-model="roomCode"
            class="input input-code"
            maxlength="6"
            placeholder="Room code"
            type="text"
          />
          <button
            :disabled="loading || !roomCode.trim()"
            class="btn btn-secondary join-btn"
            @click="handleJoinRoom"
          >
            加入 / Join
          </button>
        </div>
      </div>

      <p v-if="error" class="error-msg">{{ error }}</p>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'

// Default config for a new room — user can change this in the Room view later
const DEFAULT_ROOM_CONFIG = {
  totalPlayers: 9,
  roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
}

const router = useRouter()
const userStore = useUserStore()
const roomStore = useRoomStore()

const nickname = ref('')
const roomCode = ref('')
const loading = ref(false)
const error = ref('')

async function ensureLoggedIn() {
  if (!userStore.isLoggedIn) {
    await userStore.login(nickname.value.trim())
  }
}

async function handleCreateRoom() {
  if (!nickname.value.trim()) return
  error.value = ''
  loading.value = true
  try {
    await ensureLoggedIn()
    const room = await roomService.createRoom({ config: DEFAULT_ROOM_CONFIG })
    roomStore.setRoom(room)
    router.push({ name: 'room', params: { roomId: room.roomId } })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to connect. Try again.'
  } finally {
    loading.value = false
  }
}

async function handleJoinRoom() {
  if (!nickname.value.trim() || !roomCode.value.trim()) return
  error.value = ''
  loading.value = true
  try {
    await ensureLoggedIn()
    const room = await roomService.joinRoom({ roomCode: roomCode.value.trim().toUpperCase() })
    roomStore.setRoom(room)
    router.push({ name: 'room', params: { roomId: room.roomId } })
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Room not found. Check the code.'
  } finally {
    loading.value = false
  }
}

async function handleLogin() {
  await handleCreateRoom()
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
  margin: 0 0 1.75rem;
  letter-spacing: 0.1em;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-bottom: 1.25rem;
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
  gap: 0.75rem;
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
