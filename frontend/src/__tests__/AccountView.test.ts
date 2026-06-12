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

function perk(status: MyPerkActivation['status']): MyPerkActivation {
  return {
    perkCode: 'NIGHT1_IMMUNITY',
    perkName: '首夜免死',
    status,
    pricePaid: 30,
    roomId: 1,
    gameId: 101,
    createdAt: '2026-06-10T20:00:00',
    settledAt: null,
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
    // Other sections are unaffected.
    expect(wrapper.find('[data-testid="perk-row"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="order-row"]').exists()).toBe(true)
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
