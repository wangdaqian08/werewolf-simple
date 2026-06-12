/**
 * Component tests for AccountView (/account): the three sections + balance,
 * the four perk status badges, the guest payments gate (note shown, orders
 * never fetched), back navigation, and the per-section error fallback when
 * the wallet fetch fails.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AccountView from '@/views/AccountView.vue'
import type { MyPerkActivation, PaymentOrderSummary, Wallet } from '@/types'

const h = vi.hoisted(() => ({
  getWalletMock: vi.fn(),
  getMyPerksMock: vi.fn(),
  listOrdersMock: vi.fn(),
}))

vi.mock('@/services/walletService', () => ({
  walletService: { getWallet: h.getWalletMock, getMyPerks: h.getMyPerksMock },
}))
vi.mock('@/services/paymentService', () => ({
  paymentService: { listOrders: h.listOrdersMock },
}))

function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}

const WALLET: Wallet = {
  balance: 250,
  recent: [
    {
      type: 'PERK_SPEND',
      amount: -30,
      balanceAfter: 220,
      note: null,
      createdAt: '2026-06-10T20:00:00',
    },
    {
      type: 'GAME_REWARD',
      amount: 20,
      balanceAfter: 250,
      note: null,
      createdAt: '2026-06-09T22:15:00',
    },
  ],
}

function perk(
  status: MyPerkActivation['status'],
  settledAt: string | null = null,
): MyPerkActivation {
  return {
    perkCode: 'NIGHT1_IMMUNITY',
    perkName: '首夜免死',
    status,
    pricePaid: 30,
    roomId: 1,
    gameId: 101,
    createdAt: '2026-06-10T20:00:00',
    settledAt,
  }
}

const ORDERS: PaymentOrderSummary[] = [
  {
    orderNo: 'WW1',
    productName: 'Starter Pack',
    credits: 100,
    amountCents: 499,
    currency: 'usd',
    status: 'COMPLETED',
    createdAt: '2026-06-10T19:55:00',
  },
  {
    orderNo: 'WW2',
    productName: 'Value Pack',
    credits: 300,
    amountCents: 999,
    currency: 'usd',
    status: 'CREATED',
    createdAt: '2026-06-09T10:00:00',
  },
  {
    orderNo: 'WW3',
    productName: 'Big Pack',
    credits: 700,
    amountCents: 1999,
    currency: 'usd',
    status: 'EXPIRED',
    createdAt: '2026-06-08T10:00:00',
  },
  {
    orderNo: 'WW4',
    productName: 'Fail Pack',
    credits: 100,
    amountCents: 499,
    currency: 'usd',
    status: 'FAILED',
    createdAt: '2026-06-07T10:00:00',
  },
  {
    orderNo: 'WW5',
    productName: 'Euro Pack',
    credits: 100,
    amountCents: 999,
    currency: 'eur',
    status: 'COMPLETED',
    createdAt: '2026-06-06T10:00:00',
  },
]

async function mountAccount(userId = 'google:abc') {
  localStorage.setItem('jwt', makeJwt(3600))
  localStorage.setItem('userId', userId)
  localStorage.setItem('nickname', 'Daniel')
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: AccountView },
    ],
  })
  await router.push('/account')
  await router.isReady()
  const wrapper = mount(AccountView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('AccountView', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    h.getWalletMock.mockReset().mockResolvedValue(WALLET)
    h.getMyPerksMock.mockReset().mockResolvedValue([perk('ACTIVE')])
    h.listOrdersMock.mockReset().mockResolvedValue(ORDERS)
  })
  afterEach(() => vi.clearAllMocks())

  it('renders the three sections, the balance and the ledger rows', async () => {
    const { wrapper } = await mountAccount()
    expect(wrapper.find('[data-testid="account-wallet"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="account-perks"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="account-payments"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="account-balance"]').text()).toContain('250')
    const ledger = wrapper.findAll('[data-testid="ledger-row"]')
    expect(ledger).toHaveLength(2)
    expect(ledger[0]!.text()).toContain('道具购买')
    expect(ledger[0]!.text()).toContain('−30')
    expect(ledger[0]!.text()).toContain('余额 220')
    expect(ledger[1]!.text()).toContain('对局奖励')
    expect(ledger[1]!.text()).toContain('+20')
    const order = wrapper.find('[data-testid="order-row"]')
    expect(order.text()).toContain('Starter Pack')
    expect(order.text()).toContain('$4.99')
    expect(order.text()).toContain('已完成')
  })

  it('renders the Chinese label for all four perk statuses', async () => {
    h.getMyPerksMock.mockResolvedValue([
      perk('ACTIVE'),
      perk('CONSUMED'),
      perk('VOID'),
      perk('REFUNDED'),
    ])
    const { wrapper } = await mountAccount()
    const badges = wrapper.findAll('[data-testid="perk-status"]')
    expect(badges.map((b) => b.text())).toEqual([
      '启用中',
      '已使用',
      '未生效（结算后退还）',
      '已退还',
    ])
  })

  it('guest: shows the payments note and never calls listOrders', async () => {
    const { wrapper } = await mountAccount('guest:abc')
    expect(wrapper.find('[data-testid="payments-guest-note"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="order-row"]').exists()).toBe(false)
    expect(h.listOrdersMock).not.toHaveBeenCalled()
  })

  it('back button navigates to the lobby', async () => {
    const { wrapper, router } = await mountAccount()
    await wrapper.find('[data-testid="account-back-btn"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('still renders the page when the wallet fetch fails (per-section fallback)', async () => {
    h.getWalletMock.mockRejectedValue(new Error('network'))
    const { wrapper } = await mountAccount()
    expect(wrapper.find('[data-testid="account-wallet"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="account-wallet"]').text()).toContain('无法加载积分记录')
    // Balance line is hidden on error so stale credits from userStore are not shown.
    expect(wrapper.find('[data-testid="account-balance"]').exists()).toBe(false)
    // Other sections are unaffected.
    expect(wrapper.find('[data-testid="perk-row"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="order-row"]').exists()).toBe(true)
  })

  it('renders all four order status labels', async () => {
    const { wrapper } = await mountAccount()
    const rows = wrapper.findAll('[data-testid="order-row"]')
    expect(rows).toHaveLength(5)
    const texts = rows.map((r) => r.text())
    expect(texts.some((t) => t.includes('已完成'))).toBe(true)
    expect(texts.some((t) => t.includes('处理中'))).toBe(true)
    expect(texts.some((t) => t.includes('已过期'))).toBe(true)
    expect(texts.some((t) => t.includes('失败'))).toBe(true)
  })

  it('formats USD orders with $ prefix and non-USD orders with amount + code', async () => {
    const { wrapper } = await mountAccount()
    const rows = wrapper.findAll('[data-testid="order-row"]')
    // First row: USD $4.99
    expect(rows[0]!.text()).toContain('$4.99')
    expect(rows[0]!.text()).not.toMatch(/USD/i)
    // Last row: EUR — no $ prefix, shows code
    expect(rows[4]!.text()).toContain('9.99 EUR')
    expect(rows[4]!.text()).not.toMatch(/^\$/)
  })

  it('ledger rows use amount-neg for debits and amount-pos for credits', async () => {
    const { wrapper } = await mountAccount()
    const ledger = wrapper.findAll('[data-testid="ledger-row"]')
    // First row: PERK_SPEND, amount -30 → amount-neg
    const debitSpan = ledger[0]!.find('.row-amount')
    expect(debitSpan.classes()).toContain('amount-neg')
    // Second row: GAME_REWARD, amount +20 → amount-pos
    const creditSpan = ledger[1]!.find('.row-amount')
    expect(creditSpan.classes()).toContain('amount-pos')
  })

  it('settled perk row shows settledAt date; ACTIVE row does not', async () => {
    h.getMyPerksMock.mockResolvedValue([
      perk('CONSUMED', '2026-06-09T22:00:00'),
      perk('ACTIVE', null),
    ])
    const { wrapper } = await mountAccount()
    const rows = wrapper.findAll('[data-testid="perk-row"]')
    expect(rows[0]!.text()).toContain('结算')
    expect(rows[1]!.text()).not.toContain('结算')
  })

  it('not logged in: shows the sign-in prompt and fetches nothing', async () => {
    localStorage.clear()
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'lobby', component: { template: '<div />' } },
        { path: '/account', name: 'account', component: AccountView },
      ],
    })
    await router.push('/account')
    await router.isReady()
    const wrapper = mount(AccountView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="account-login-prompt"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="account-wallet"]').exists()).toBe(false)
    expect(h.getWalletMock).not.toHaveBeenCalled()
    expect(h.getMyPerksMock).not.toHaveBeenCalled()
    expect(h.listOrdersMock).not.toHaveBeenCalled()
  })
})
