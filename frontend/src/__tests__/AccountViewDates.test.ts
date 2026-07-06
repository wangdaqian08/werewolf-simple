/**
 * fmtDate contract for AccountView: the backend serializes zone-less
 * LocalDateTime strings (UTC wall-clock in the prod containers), so the view
 * must treat a timestamp without a zone designator as UTC and render it in
 * the viewer's local time. TZ is pinned to Asia/Shanghai (UTC+8) so the
 * assertion stays meaningful on UTC CI runners; the `wrong !== expected`
 * guard fails loudly if the pin ever stops taking effect.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AccountView from '@/views/AccountView.vue'

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

async function mountAccount() {
  localStorage.setItem('jwt', makeJwt(3600))
  localStorage.setItem('userId', 'google:abc')
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
  return wrapper
}

const ORIGINAL_TZ = process.env.TZ

describe('AccountView date rendering (zone-less backend timestamps)', () => {
  beforeAll(() => {
    process.env.TZ = 'Asia/Shanghai'
  })
  afterAll(() => {
    if (ORIGINAL_TZ === undefined) delete process.env.TZ
    else process.env.TZ = ORIGINAL_TZ
  })
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    h.getWalletMock.mockReset().mockResolvedValue({ balance: 0, recent: [] })
    h.getMyPerksMock.mockReset().mockResolvedValue([])
    h.listOrdersMock.mockReset().mockResolvedValue([])
  })
  afterEach(() => vi.clearAllMocks())

  it('treats a zone-less timestamp as UTC and renders the viewer-local time', async () => {
    const iso = '2026-06-10T20:00:00'
    h.getMyPerksMock.mockResolvedValue([
      {
        perkCode: 'NIGHT1_IMMUNITY',
        perkName: '首夜免死',
        status: 'ACTIVE',
        pricePaid: 30,
        roomId: 1,
        gameId: 101,
        createdAt: iso,
        settledAt: null,
      },
    ])
    const opts = {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    } as const
    // 20:00 UTC = 2026/06/11 04:00 in Asia/Shanghai (next calendar day).
    const expected = new Date(`${iso}Z`).toLocaleString('zh-CN', opts)
    const wrong = new Date(iso).toLocaleString('zh-CN', opts)
    expect(wrong).not.toBe(expected) // TZ pin took effect — assertion below is meaningful

    const wrapper = await mountAccount()
    const rowText = wrapper.find('[data-testid="perk-row"]').text()
    expect(rowText).toContain(expected)
    expect(rowText).not.toContain(wrong)
  })

  it('renders a timestamp that already carries a zone designator unchanged', async () => {
    const iso = '2026-06-10T20:00:00Z'
    h.listOrdersMock.mockResolvedValue([
      {
        orderNo: 'TZ1',
        productName: 'Pack',
        credits: 100,
        amountCents: 499,
        currency: 'usd',
        status: 'COMPLETED',
        createdAt: iso,
      },
    ])
    const opts = {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    } as const
    const expected = new Date(iso).toLocaleString('zh-CN', opts)

    const wrapper = await mountAccount()
    expect(wrapper.find('[data-testid="order-row"]').text()).toContain(expected)
  })
})
