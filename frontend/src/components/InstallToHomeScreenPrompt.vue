<template>
  <!--
    Self-gating: this component renders only on iOS Safari outside standalone mode
    and only when the user has not permanently dismissed it. All three conditions
    are checked here rather than in the parent so LobbyView stays clean.
  -->
  <div v-if="visible" class="install-prompt" data-testid="install-prompt">
    <div class="install-prompt-header">
      <h3 class="install-prompt-title">获得更稳定的音频体验 / Get reliable audio</h3>
    </div>
    <p class="install-prompt-body">
      房主请将本应用添加到主屏幕，避免锁屏后音频中断 / Hosts: install to Home Screen so audio keeps
      playing when the screen locks.
    </p>
    <ol class="install-prompt-steps">
      <li>Tap Safari's Share button (&#x2610;&#x2191;)</li>
      <li>Scroll, tap "添加到主屏幕 / Add to Home Screen"</li>
      <li>Launch from the new icon on your Home Screen</li>
    </ol>
    <div class="install-prompt-actions">
      <button class="btn-secondary" data-testid="install-prompt-dismiss" @click="dismiss">
        稍后 / Later
      </button>
      <button class="btn-outline" data-testid="install-prompt-never" @click="neverShow">
        不再提示 / Don't show again
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { isIosSafari, isStandalonePwa } from '@/composables/useBrowserCompat'

const DISMISSED_KEY = 'pwa-install-dismissed'

function shouldShow(): boolean {
  if (!isIosSafari()) return false
  if (isStandalonePwa()) return false
  if (localStorage.getItem(DISMISSED_KEY) === 'true') return false
  return true
}

const visible = ref(shouldShow())

function dismiss() {
  visible.value = false
}

function neverShow() {
  localStorage.setItem(DISMISSED_KEY, 'true')
  visible.value = false
}
</script>

<style scoped>
.install-prompt {
  background: var(--paper, #f5f0e8);
  border: 1px solid var(--border, #ccc2b0);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.install-prompt-header {
  margin-bottom: 8px;
}

.install-prompt-title {
  font-family: 'Noto Serif SC', 'Songti SC', serif;
  font-size: 15px;
  font-weight: 700;
  color: var(--text, #1a140c);
  margin: 0;
}

.install-prompt-body {
  font-size: 13px;
  color: var(--muted, #8a7a65);
  margin: 0 0 10px;
  line-height: 1.5;
}

.install-prompt-steps {
  font-size: 13px;
  color: var(--text, #1a140c);
  margin: 0 0 12px 0;
  padding: 0 0 0 22px;
  line-height: 1.7;
}

.install-prompt-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-secondary {
  background: var(--paper, #f5f0e8);
  border: 1px solid var(--border, #ccc2b0);
  color: var(--muted, #8a7a65);
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}

.btn-outline {
  background: transparent;
  border: 1px solid var(--border, #ccc2b0);
  color: var(--muted, #8a7a65);
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}
</style>
