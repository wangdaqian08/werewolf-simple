<template>
  <div class="pay-wrap">
    <div class="pay-card">
      <template v-if="phase === 'cancel'">
        <h1 class="pay-title serif">{{ $t('pay.cancelled') }}</h1>
        <p class="pay-sub">{{ $t('pay.notCharged') }}</p>
      </template>

      <template v-else-if="phase === 'pending'">
        <h1 class="pay-title serif">{{ $t('pay.confirming') }}</h1>
        <p class="pay-sub">{{ $t('pay.waiting') }}</p>
      </template>

      <template v-else-if="phase === 'success'">
        <h1 class="pay-title pay-success serif">{{ $t('pay.success') }}</h1>
        <p class="pay-earned" data-testid="pay-credits">{{ $t('pay.earned', { n: credits }) }}</p>
        <p v-if="userStore.credits !== null" class="pay-sub">
          {{ $t('pay.balance', { n: userStore.credits }) }}
        </p>
      </template>

      <template v-else>
        <h1 class="pay-title serif">{{ $t('pay.unconfirmed') }}</h1>
        <p class="pay-sub">
          {{ $t('pay.timeout') }}
        </p>
      </template>

      <button class="btn btn-primary back-lobby-btn" data-testid="pay-back" @click="goLobby">
        {{ $t('pay.backLobby') }}
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { paymentService } from '@/services/paymentService'
import { useUserStore } from '@/stores/userStore'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

type Phase = 'pending' | 'success' | 'cancel' | 'timeout'
const phase = ref<Phase>('pending')
const credits = ref(0)

let stopped = false

// Fulfillment comes from the Stripe webhook, not this redirect — poll the
// order until the backend has seen the webhook (usually < 2s).
async function pollOrder(orderNo: string) {
  const deadline = Date.now() + 30_000
  while (!stopped && Date.now() < deadline) {
    try {
      const order = await paymentService.getOrder(orderNo)
      if (order.status === 'COMPLETED') {
        credits.value = order.credits
        phase.value = 'success'
        await userStore.refreshWallet()
        return
      }
      if (order.status === 'EXPIRED' || order.status === 'FAILED') {
        phase.value = 'timeout'
        return
      }
    } catch {
      /* transient — keep polling until the deadline */
    }
    await new Promise((r) => setTimeout(r, 1500))
  }
  if (!stopped && phase.value === 'pending') phase.value = 'timeout'
}

onMounted(() => {
  const status = route.query.status as string | undefined
  const orderNo = route.query.orderNo as string | undefined
  if (status === 'cancel' || !orderNo) {
    phase.value = status === 'cancel' ? 'cancel' : 'timeout'
    return
  }
  void pollOrder(orderNo)
})

onUnmounted(() => {
  stopped = true
})

function goLobby() {
  router.push({ name: 'lobby' })
}
</script>

<style scoped>
.pay-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  background: var(--bg);
  padding: 1.5rem;
}

.pay-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 360px;
  text-align: center;
}

.pay-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.75rem;
  color: var(--text);
  margin: 0 0 0.5rem;
}

.pay-success {
  color: var(--green);
}

.pay-earned {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  color: var(--gold);
  font-weight: 600;
  margin: 0 0 0.25rem;
}

.pay-sub {
  font-size: 0.875rem;
  color: var(--muted);
  margin: 0 0 1.5rem;
}

.back-lobby-btn {
  width: 100%;
}
</style>
