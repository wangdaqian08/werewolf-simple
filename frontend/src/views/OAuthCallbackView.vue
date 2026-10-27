<template>
  <div class="callback-wrap">
    <div class="callback-card">
      <h1 class="title">{{ $t('oauth.title') }}</h1>
      <p v-if="$i18n.locale === 'zh'" class="subtitle">Signing you in</p>

      <p v-if="loading" class="status" data-testid="oauth-loading">{{ $t('oauth.verifying') }}</p>

      <div v-else-if="error" class="error" data-testid="oauth-error">
        <p class="error-msg">{{ error }}</p>
        <router-link class="back-link" to="/">{{ $t('oauth.back') }}</router-link>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { t } from '@/i18n'
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { consumeOAuthState } from '@/utils/oauth'
import type { OAuthProvider } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const error = ref('')

function isOAuthProvider(p: unknown): p is OAuthProvider {
  return p === 'google' || p === 'wechat'
}

onMounted(async () => {
  const providerParam = route.params.provider
  const provider = Array.isArray(providerParam) ? providerParam[0] : providerParam

  const code = (route.query.code as string | undefined) ?? ''
  const state = (route.query.state as string | undefined) ?? ''
  const errorQuery = route.query.error as string | undefined

  if (!isOAuthProvider(provider)) {
    error.value = t('oauth.unknownProvider')
    loading.value = false
    return
  }

  // Provider returned an error or the user canceled.
  if (errorQuery || !code) {
    // Always consume state to avoid leaving stale storage behind.
    consumeOAuthState(state)
    error.value = t('oauth.canceled')
    loading.value = false
    return
  }

  // CSRF: state must match the value we wrote before redirecting.
  if (!consumeOAuthState(state)) {
    error.value = t('oauth.expired')
    loading.value = false
    return
  }

  try {
    await userStore.loginWithCode(provider, code)
    router.push('/')
  } catch {
    // Don't surface server-side details to the user; backend logs already
    // capture the precise failure.
    error.value = t('oauth.failed')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.callback-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
}

.callback-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 360px;
  text-align: center;
}

.title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.75rem;
  color: var(--red);
  margin: 0 0 0.25rem;
}

.subtitle {
  color: var(--muted);
  font-size: 0.875rem;
  letter-spacing: 0.1em;
  margin: 0 0 1.5rem;
}

.status {
  color: var(--muted);
  font-size: 1rem;
}

.error-msg {
  color: var(--red);
  font-size: 1rem;
  margin: 0 0 1rem;
}

.back-link {
  display: inline-block;
  color: var(--muted);
  text-decoration: none;
  border-bottom: 1px solid var(--border);
  padding-bottom: 2px;
}

.back-link:hover {
  color: var(--text);
}
</style>
