<template>
  <div class="account-wrap">
    <div class="account-card">
      <button class="back-btn" data-testid="account-back-btn" @click="router.push('/')">
        {{ $t('common.back') }}
      </button>

      <h1 class="title">{{ $t('account.title') }}</h1>

      <p v-if="!userStore.isLoggedIn" class="login-prompt" data-testid="account-login-prompt">
        {{ $t('account.signIn') }}
      </p>

      <template v-else>
        <p v-if="loading" class="loading-note">{{ $t('common.loadingShort') }}</p>

        <!-- Admin only: the backend says who the admin is -->
        <button
          v-if="isAdmin"
          class="btn btn-gold admin-link"
          data-testid="account-admin-link"
          @click="router.push('/admin')"
        >
          {{ $t('account.adminLink') }}
        </button>

        <StatsCard v-if="stats" :stats="stats" />
        <p v-if="userStore.isGuest" class="guest-note" data-testid="account-guest-note">
          {{ $t('account.guestNote') }}
        </p>

        <!-- ── Section 1: credits + ledger ─────────────────────────────── -->
        <section class="section" data-testid="account-wallet">
          <h2 class="section-title">{{ $t('account.credits') }}</h2>
          <p v-if="!walletError" class="balance" data-testid="account-balance">
            ◈ {{ balanceText }}
          </p>
          <p
            v-if="!walletError && balance !== null && balance < 0"
            class="error-msg"
            data-testid="negative-balance-hint"
          >
            {{ $t('account.negativeBalance') }}
          </p>
          <p v-if="walletError" class="error-msg">{{ $t('account.creditsFail') }}</p>
          <ul v-else-if="wallet && wallet.recent.length > 0" class="rows">
            <li
              v-for="tx in wallet.recent"
              :key="`${tx.type}-${tx.createdAt}-${tx.balanceAfter}`"
              class="row"
              data-testid="ledger-row"
            >
              <div class="row-main">
                <span class="row-name">{{
                  TX_LABELS[tx.type] ? $t(TX_LABELS[tx.type]) : tx.type
                }}</span>
                <span class="row-amount" :class="tx.amount >= 0 ? 'amount-pos' : 'amount-neg'">
                  {{ signed(tx.amount) }}
                </span>
              </div>
              <div class="row-sub">
                <span
                  >{{ $t('account.balanceAfter', { n: tx.balanceAfter })
                  }}<template v-if="tx.orderNo">
                    · <span data-testid="ledger-order">{{ tx.orderNo }}</span></template
                  ></span
                >
                <span>{{ fmtDate(tx.createdAt) }}</span>
              </div>
            </li>
          </ul>
          <p v-else-if="!loading" class="empty">{{ $t('account.noTx') }}</p>
        </section>

        <!-- ── Section 2: perk history ─────────────────────────────────── -->
        <section class="section" data-testid="account-perks">
          <h2 class="section-title">{{ $t('account.perks') }}</h2>
          <p v-if="perksError" class="error-msg">{{ $t('account.perksFail') }}</p>
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
                  {{
                    PERK_STATUS_LABELS[perk.status]
                      ? $t(PERK_STATUS_LABELS[perk.status])
                      : perk.status
                  }}
                </span>
              </div>
              <div class="row-sub">
                <span>◈{{ perk.pricePaid }}</span>
                <span class="dates">
                  <span class="nowrap" data-testid="perk-created">{{
                    fmtDate(perk.createdAt)
                  }}</span>
                  <span v-if="perk.settledAt" class="nowrap" data-testid="perk-settled">{{
                    $t('account.settled', { date: fmtDate(perk.settledAt) })
                  }}</span>
                </span>
              </div>
            </li>
          </ul>
          <p v-else-if="!loading" class="empty">{{ $t('account.noPerks') }}</p>
        </section>

        <!-- ── Section 3: payment history ──────────────────────────────── -->
        <section class="section" data-testid="account-payments">
          <h2 class="section-title">{{ $t('account.payments') }}</h2>
          <p v-if="userStore.isGuest" class="guest-note" data-testid="payments-guest-note">
            {{ $t('lobby.guestBuy') }}
          </p>
          <template v-else>
            <p v-if="ordersError" class="error-msg">{{ $t('account.paymentsFail') }}</p>
            <template v-else-if="orders.length > 0">
              <ul class="rows">
                <li
                  v-for="order in orders"
                  :key="order.orderNo"
                  class="row"
                  data-testid="order-row"
                >
                  <div class="row-main">
                    <span class="row-name">{{ order.productName }}</span>
                    <span class="row-amount amount-gold">◈{{ order.credits }}</span>
                  </div>
                  <div class="row-sub">
                    <span>
                      {{ fmtMoney(order) }} ·
                      <span data-testid="order-status">{{
                        ORDER_STATUS_LABELS[order.status]
                          ? $t(ORDER_STATUS_LABELS[order.status])
                          : order.status
                      }}</span>
                      <template v-if="order.receiptUrl">
                        ·
                        <a
                          :href="order.receiptUrl"
                          class="receipt-link"
                          data-testid="order-receipt"
                          rel="noopener noreferrer"
                          target="_blank"
                          >{{ $t('account.receipt') }}</a
                        >
                      </template>
                    </span>
                    <span>{{ fmtDate(order.createdAt) }}</span>
                  </div>
                </li>
              </ul>
              <button
                v-if="ordersHasMore"
                class="btn btn-outline more-btn"
                data-testid="orders-more"
                :disabled="ordersLoadingMore"
                @click="loadMoreOrders"
              >
                {{ $t('account.loadMore') }}
              </button>
            </template>
            <p v-else-if="!loading" class="empty">{{ $t('account.noPayments') }}</p>
          </template>
        </section>
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getLocale } from '@/i18n'
import { useUserStore } from '@/stores/userStore'
import { walletService } from '@/services/walletService'
import { paymentService, type PaymentOrderStatus } from '@/services/paymentService'
import { adminService } from '@/services/adminService'
import { userService } from '@/services/userService'
import StatsCard from '@/components/StatsCard.vue'
import type {
  CreditTxType,
  MyPerkActivation,
  PaymentOrderSummary,
  PerkActivationStatus,
  UserStats,
  Wallet,
} from '@/types'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const wallet = ref<Wallet | null>(null)
const stats = ref<UserStats | null>(null)
const perks = ref<MyPerkActivation[]>([])
const orders = ref<PaymentOrderSummary[]>([])
const ordersPage = ref(0)
const ordersHasMore = ref(false)
const ordersLoadingMore = ref(false)
// Per-section error fallbacks — one failed fetch must not blank the page.
const walletError = ref(false)
const perksError = ref(false)
const ordersError = ref(false)
const isAdmin = ref(false)

const TX_LABELS: Record<CreditTxType, string> = {
  ADMIN_GRANT: 'account.tx.ADMIN_GRANT',
  PURCHASE: 'account.tx.PURCHASE',
  GAME_REWARD: 'account.tx.GAME_REWARD',
  PERK_SPEND: 'account.tx.PERK_SPEND',
  REFUND: 'account.tx.REFUND',
  PURCHASE_REVERSAL: 'account.tx.PURCHASE_REVERSAL',
  DISPUTE_REVERSAL: 'account.tx.DISPUTE_REVERSAL',
  PURCHASE_REINSTATED: 'account.tx.PURCHASE_REINSTATED',
}

const PERK_STATUS_LABELS: Record<PerkActivationStatus, string> = {
  ACTIVE: 'account.perk.ACTIVE',
  CONSUMED: 'account.perk.CONSUMED',
  REFUNDED: 'account.perk.REFUNDED',
  VOID: 'account.perk.VOID',
}

const ORDER_STATUS_LABELS: Record<PaymentOrderSummary['status'], string> = {
  COMPLETED: 'account.order.COMPLETED',
  CREATED: 'account.order.CREATED',
  EXPIRED: 'account.order.EXPIRED',
  FAILED: 'account.order.FAILED',
  REFUNDED: 'account.order.REFUNDED',
  DISPUTED: 'account.order.DISPUTED',
  DISPUTE_LOST: 'account.order.DISPUTE_LOST',
}

const balance = computed(() => wallet.value?.balance ?? userStore.credits)
const balanceText = computed(() =>
  balance.value === null ? '—' : balance.value < 0 ? signed(balance.value) : balance.value,
)

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
  return d.toLocaleString(getLocale() === 'zh' ? 'zh-CN' : 'en-GB', {
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
    // A failed stats load just hides the card.
    userService
      .getStats()
      .then((s) => {
        stats.value = s
      })
      .catch(() => {
        stats.value = null
      }),
    // Keeps the lobby chip in sync; never throws (errors swallowed in store).
    userStore.refreshWallet(),
    adminService
      .isAdmin()
      .then((a) => {
        isAdmin.value = a
      })
      .catch(() => {
        isAdmin.value = false
      }),
  ]
  // Guests can't purchase — skip the orders call entirely.
  if (!userStore.isGuest) {
    tasks.push(
      paymentService
        .listOrders(0)
        .then((p) => {
          orders.value = p.orders
          ordersHasMore.value = p.hasMore
          void recheckProcessing(p.orders)
        })
        .catch(() => {
          ordersError.value = true
        }),
    )
  }
  await Promise.all(tasks)
  loading.value = false
})

async function loadMoreOrders() {
  ordersLoadingMore.value = true
  try {
    const p = await paymentService.listOrders(ordersPage.value + 1)
    ordersPage.value += 1
    // A checkout since the last page shifts every row down, so skip repeats.
    const shown = new Set(orders.value.map((o) => o.orderNo))
    const fresh = p.orders.filter((o) => !shown.has(o.orderNo))
    orders.value = [...orders.value, ...fresh]
    ordersHasMore.value = p.hasMore
    void recheckProcessing(fresh)
  } catch {
    // The button stays, so the player can try again.
  } finally {
    ordersLoadingMore.value = false
  }
}

// The order endpoint asks Stripe about a processing order, so a missed webhook
// settles here too; a settled one changes the balance, so reload the wallet.
async function recheckProcessing(list: PaymentOrderSummary[]) {
  const results = await Promise.all(
    list
      .filter((o) => o.status === 'CREATED')
      .map((o) => paymentService.getOrder(o.orderNo).catch(() => null)),
  )
  const settled = results.filter(
    (r): r is PaymentOrderStatus => r !== null && r.status !== 'CREATED',
  )
  if (settled.length === 0) return
  orders.value = orders.value.map((o) => {
    const r = settled.find((s) => s.orderNo === o.orderNo)
    return r ? { ...o, status: r.status, receiptUrl: r.receiptUrl } : o
  })
  wallet.value = await walletService.getWallet().catch(() => wallet.value)
}
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

.admin-link {
  margin-bottom: 1rem;
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

/* Created + settled dates: each date stays on one line; the pair may wrap between them. */
.dates {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  column-gap: 0.375rem;
  text-align: right;
}

.nowrap {
  white-space: nowrap;
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

.account-card > .guest-note {
  margin: -0.5rem 0 1rem;
}

.guest-note,
.empty {
  font-size: 0.75rem;
  color: var(--muted);
  margin: 0;
}

.receipt-link {
  color: var(--gold);
}

.more-btn {
  width: 100%;
  margin-top: 0.5rem;
}

.error-msg {
  color: var(--red);
  font-size: 0.75rem;
  margin: 0;
}
</style>
