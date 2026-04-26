<template>
  <div class="unsupported-wrap">
    <div class="unsupported-card">
      <div class="icon" aria-hidden="true">🌐</div>
      <h1 class="title">浏览器不兼容</h1>
      <p class="subtitle">Browser not supported</p>

      <p class="body">
        这个游戏需要使用 <strong>Chrome 浏览器</strong>（或其他基于 Chromium 的浏览器，例如 Edge、Brave）。
        iPhone / iPad 用户也可以使用 Safari。
      </p>
      <p class="body en">
        This game runs on <strong>Chrome</strong> or other Chromium-based browsers
        (Edge, Brave). iOS Safari is also supported.
      </p>

      <div class="hint">
        <p class="hint-line">在 Chrome 中打开：</p>
        <p class="hint-line en">Open this URL in Chrome:</p>
        <code class="url">{{ currentUrl }}</code>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

// Show the user the URL they should re-open in Chrome.
const currentUrl = computed(() =>
  typeof window === 'undefined' ? '' : window.location.href,
)
</script>

<style scoped>
.unsupported-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
}

.unsupported-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 420px;
  text-align: center;
}

.icon {
  font-size: 2.75rem;
  margin-bottom: 0.5rem;
}

.title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.625rem;
  color: var(--red);
  margin: 0 0 0.25rem;
}

.subtitle {
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0 0 1.5rem;
  letter-spacing: 0.1em;
}

.body {
  font-size: 0.95rem;
  line-height: 1.5;
  color: var(--text);
  margin: 0 0 0.75rem;
}

.body strong {
  color: var(--red);
}

.body.en {
  color: var(--muted);
  font-size: 0.85rem;
  margin-bottom: 1.5rem;
}

.hint {
  background: var(--card);
  border: 1px dashed var(--border-l);
  border-radius: 0.5rem;
  padding: 0.875rem;
  margin-top: 0.5rem;
}

.hint-line {
  font-size: 0.8rem;
  color: var(--muted);
  margin: 0 0 0.25rem;
}

.hint-line.en {
  font-size: 0.75rem;
  margin-bottom: 0.5rem;
}

.url {
  display: block;
  font-family: monospace;
  font-size: 0.85rem;
  color: var(--text);
  word-break: break-all;
  background: var(--paper);
  padding: 0.5rem;
  border-radius: 0.25rem;
}
</style>
