/**
 * The Stripe return page: it polls the order (the redirect itself is not trusted),
 * shows the credits once COMPLETED, the order's own status once it is final, and
 * after 30 s tells the player they can leave while the payment is confirmed.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PayResultView from '@/views/PayResultView.vue'

const h = vi.hoisted(() => ({ getOrderMock: vi.fn() }))

vi.mock('@/services/paymentService', () => ({
  paymentService: { getOrder: h.getOrderMock },
}))

async function mountAt(query: string) {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div />' } },
      { path: '/pay/result', name: 'pay-result', component: PayResultView },
    ],
  })
  await router.push(`/pay/result?${query}`)
  await router.isReady()
  const wrapper = mount(PayResultView, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  return wrapper
}

describe('PayResultView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    h.getOrderMock.mockReset()
  })
  afterEach(() => vi.useRealTimers())

  it('shows the credits once the order is COMPLETED', async () => {
    h.getOrderMock
      .mockResolvedValueOnce({ orderNo: 'WW1', status: 'CREATED', credits: 300 })
      .mockResolvedValue({ orderNo: 'WW1', status: 'COMPLETED', credits: 300 })
    const wrapper = await mountAt('status=success&orderNo=WW1')
    await vi.advanceTimersByTimeAsync(1500)

    expect(wrapper.find('[data-testid="pay-credits"]').text()).toBe('+300 金幣')
  })

  it.each([
    ['EXPIRED', '已過期（未扣款）'],
    ['FAILED', '失敗（未扣款）'],
    ['REFUNDED', '已退款'],
  ])('stops polling and shows the %s order status', async (status, label) => {
    h.getOrderMock.mockResolvedValue({ orderNo: 'WW1', status, credits: 300 })
    const wrapper = await mountAt('status=success&orderNo=WW1')
    await vi.advanceTimersByTimeAsync(5000)

    expect(wrapper.find('[data-testid="pay-status"]').text()).toBe(label)
    expect(h.getOrderMock).toHaveBeenCalledTimes(1)
  })

  it('after 30 s still processing, says the player can leave', async () => {
    h.getOrderMock.mockResolvedValue({ orderNo: 'WW1', status: 'CREATED', credits: 300 })
    const wrapper = await mountAt('status=success&orderNo=WW1')
    await vi.advanceTimersByTimeAsync(31_000)

    expect(wrapper.text()).toContain(
      '正在確認付款，你可以先離開；金幣到賬後會顯示在餘額和充值記錄裡',
    )
    const calls = h.getOrderMock.mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(h.getOrderMock).toHaveBeenCalledTimes(calls)
  })

  it('a cancelled checkout says nothing was charged and never polls', async () => {
    const wrapper = await mountAt('status=cancel&orderNo=WW1')

    expect(wrapper.text()).toContain('未產生扣款')
    expect(h.getOrderMock).not.toHaveBeenCalled()
  })
})
