<template>
  <div class="account-wrap">
    <div class="account-card">
      <button class="back-btn" data-testid="account-back-btn" @click="router.push('/')">
        ← 返回 / Back
      </button>

      <h1 class="title">我的账户</h1>
      <p class="subtitle">Account</p>

      <p v-if="!userStore.isLoggedIn" class="login-prompt" data-testid="account-login-prompt">
        请先登录 / Please sign in
      </p>

      <template v-else>
        <p v-if="loading" class="loading-note">加载中… / Loading…</p>

        <!-- ── Section 1: credits + ledger ─────────────────────────────── -->
        <section class="section" data-testid="account-wallet">
          <h2 class="section-title">积分 / Credits</h2>
          <p v-if="!walletError" class="balance" data-testid="account-balance">
            ◈ {{ balanceText }}
          </p>
          <p v-if="walletError" class="error-msg">无法加载积分记录 / Failed to load credits</p>
          <ul v-else-if="wallet && wallet.recent.length > 0" class="rows">
            <li
              v-for="tx in wallet.recent"
              :key="`${tx.type}-${tx.createdAt}-${tx.balanceAfter}`"
              class="row"
              data-testid="ledger-row"
            >
              <div class="row-main">
                <span class="row-name">{{ TX_LABELS[tx.type] ?? tx.type }}</span>
                <span class="row-amount" :class="tx.amount >= 0 ? 'amount-pos' : 'amount-neg'">
                  {{ signed(tx.amount) }}
                </span>
              </div>
              <div class="row-sub">
                <span>余额 {{ tx.balanceAfter }}</span>
                <span>{{ fmtDate(tx.createdAt) }}</span>
              </div>
            </li>
          </ul>
          <p v-else-if="!loading" class="empty">暂无积分记录 / No transactions yet</p>
        </section>

        <!-- ── Section 2: perk history ─────────────────────────────────── -->
        <section class="section" data-testid="account-perks">
          <h2 class="section-title">道具 / Perks</h2>
          <p v-if="perksError" class="error-msg">无法加载道具记录 / Failed to load perks</p>
          <ul v-else-if="perks.length > 0" class="rows">
            <li
              v-for="perk in perks"
              :key="`${perk.roomId}-${perk.perkCode}-${perk.createdAt}`"
              class="row"
              data-testid="perk-row"
            >
              <div class="row-main">
                <span class="row-name">{{ perk.perkName }}</span>
                <span
                  class="status-badge"
                  :class="`status-${perk.status.toLowerCase()}`"
                  data-testid="perk-status"
                >
                  {{ PERK_STATUS_LABELS[perk.status] ?? perk.status }}
                </span>
              </div>
              <div class="row-sub">
                <span>◈{{ perk.pricePaid }}</span>
                <span
                  >{{ fmtDate(perk.createdAt)
                  }}<template v-if="perk.settledAt">
                    · 结算 {{ fmtDate(perk.settledAt) }}</template
                  ></span
                >
              </div>
            </li>
          </ul>
          <p v-else-if="!loading" class="empty">暂无道具记录 / No perks yet</p>
        </section>

        <!-- ── Section 3: payment history ──────────────────────────────── -->
        <section class="section" data-testid="account-payments">
          <h2 class="section-title">充值记录 / Payments</h2>
          <p v-if="userStore.isGuest" class="guest-note" data-testid="payments-guest-note">
            访客账号无法充值，请使用 Google 登录。 / Purchases require a signed-in account — sign in
            with Google to buy credits.
          </p>
          <template v-else>
            <p v-if="ordersError" class="error-msg">无法加载充值记录 / Failed to load payments</p>
            <ul v-else-if="orders.length > 0" class="rows">
              <li v-for="order in orders" :key="order.orderNo" class="row" data-testid="order-row">
                <div class="row-main">
                  <span class="row-name">{{ order.productName }}</span>
                  <span class="row-amount amount-gold">◈{{ order.credits }}</span>
                </div>
                <div class="row-sub">
                  <span>
                    {{ fmtMoney(order) }} ·
                    {{ ORDER_STATUS_LABELS[order.status] ?? order.status }}
                  </span>
                  <span>{{ fmtDate(order.createdAt) }}</span>
                </div>
              </li>
            </ul>
            <p v-else-if="!loading" class="empty">暂无充值记录 / No payments yet</p>
          </template>
        </section>
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { walletService } from '@/services/walletService'
import { paymentService } from '@/services/paymentService'
import type {
  CreditTxType,
  MyPerkActivation,
  PaymentOrderSummary,
  PerkActivationStatus,
  Wallet,
} from '@/types'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const wallet = ref<Wallet | null>(null)
const perks = ref<MyPerkActivation[]>([])
const orders = ref<PaymentOrderSummary[]>([])
// Per-section error fallbacks — one failed fetch must not blank the page.
const walletError = ref(false)
const perksError = ref(false)
const ordersError = ref(false)

const TX_LABELS: Record<CreditTxType, string> = {
  PURCHASE: '充值',
  GAME_REWARD: '对局奖励',
  PERK_SPEND: '道具购买',
  REFUND: '退款',
}

const PERK_STATUS_LABELS: Record<PerkActivationStatus, string> = {
  ACTIVE: '启用中',
  CONSUMED: '已使用',
  REFUNDED: '已退还',
  VOID: '未生效（结算后退还）',
}

const ORDER_STATUS_LABELS: Record<PaymentOrderSummary['status'], string> = {
  COMPLETED: '已完成',
  CREATED: '处理中',
  EXPIRED: '已过期',
  FAILED: '失败',
}

const balanceText = computed(() => wallet.value?.balance ?? userStore.credits ?? '—')

function signed(amount: number): string {
  return amount >= 0 ? `+${amount}` : `−${Math.abs(amount)}`
}

// Stripe minor units are not always hundredths: zero-decimal currencies store
// whole units, three-decimal currencies store thousandths (per Stripe docs).
const ZERO_DECIMAL_CURRENCIES = new Set([
  'bif',
  'clp',
  'djf',
  'gnf',
  'jpy',
  'kmf',
  'krw',
  'mga',
  'pyg',
  'rwf',
  'ugx',
  'vnd',
  'vuv',
  'xaf',
  'xof',
  'xpf',
])
const THREE_DECIMAL_CURRENCIES = new Set(['bhd', 'jod', 'kwd', 'omr', 'tnd'])

function fmtMoney(order: { amountCents: number; currency: string }): string {
  const cur = order.currency.toLowerCase()
  const amount = ZERO_DECIMAL_CURRENCIES.has(cur)
    ? String(order.amountCents)
    : THREE_DECIMAL_CURRENCIES.has(cur)
      ? (order.amountCents / 1000).toFixed(3)
      : (order.amountCents / 100).toFixed(2)
  return cur === 'usd' ? `$${amount}` : `${amount} ${order.currency.toUpperCase()}`
}

function fmtDate(iso: string): string {
  // Backend serializes zone-less LocalDateTime (UTC wall-clock in the prod
  // containers) — treat a missing zone designator as UTC so the browser
  // renders the viewer's local time instead of the server's wall-clock.
  const hasZone = /[Zz]$|[+-]\d{2}:?\d{2}$/.test(iso)
  const d = new Date(hasZone ? iso : `${iso}Z`)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

onMounted(async () => {
  if (!userStore.isLoggedIn) return
  loading.value = true
  const tasks: Promise<unknown>[] = [
    walletService
      .getWallet()
      .then((w) => {
        wallet.value = w
      })
      .catch(() => {
        walletError.value = true
      }),
    walletService
      .getMyPerks()
      .then((p) => {
        perks.value = p
      })
      .catch(() => {
        perksError.value = true
      }),
    // Keeps the lobby chip in sync; never throws (errors swallowed in store).
    userStore.refreshWallet(),
  ]
  // Guests can't purchase — skip the orders call entirely.
  if (!userStore.isGuest) {
    tasks.push(
      paymentService
        .listOrders()
        .then((o) => {
          orders.value = o
        })
        .catch(() => {
          ordersError.value = true
        }),
    )
  }
  await Promise.all(tasks)
  loading.value = false
})
</script>

<style scoped>
.account-wrap {
  display: flex;
  justify-content: center;
  min-height: 100dvh;
  padding: 1.5rem;
  background: var(--bg);
}

.account-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem;
  width: 100%;
  max-width: 360px;
}

/* 44px min tap target (codebase floor for secondary controls) — the visual
   stays a compact text link, the extra height is transparent hit area. */
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
  margin: 0 0 0.25rem;
}

.subtitle {
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0 0 1.25rem;
  letter-spacing: 0.1em;
}

.login-prompt {
  text-align: center;
  color: var(--muted);
  font-size: 0.9375rem;
  margin: 1.5rem 0;
}

.loading-note {
  text-align: center;
  color: var(--muted);
  font-size: 0.8125rem;
  margin: 0 0 0.75rem;
}

/* ── sections ───────────────────────────────────────────────────────── */
.section {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.75rem;
  padding: 0.875rem;
  margin-bottom: 1rem;
}

.section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  color: var(--text);
  margin: 0 0 0.5rem;
}

.balance {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  color: var(--gold);
  font-weight: 600;
  margin: 0 0 0.5rem;
}

.rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.row {
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.5rem 0.625rem;
}

.row-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--text);
}

.row-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-amount {
  font-weight: 600;
}

.amount-pos {
  color: var(--green);
}

.amount-neg {
  color: var(--red);
}

.amount-gold {
  color: var(--gold);
}

.row-sub {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: var(--muted);
  margin-top: 0.125rem;
}

.status-badge {
  font-size: 0.75rem;
  white-space: nowrap;
}

.status-active {
  color: var(--gold);
}

.status-consumed,
.status-void {
  color: var(--muted);
}

.status-refunded {
  color: var(--green);
}

.guest-note,
.empty {
  font-size: 0.75rem;
  color: var(--muted);
  margin: 0;
}

.error-msg {
  color: var(--red);
  font-size: 0.75rem;
  margin: 0;
}
</style>
