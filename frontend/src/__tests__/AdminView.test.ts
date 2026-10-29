/**
 * AdminView (/admin): non-admins see only the "admin only" note; the admin
 * searches, picks a player, enters amount + PIN, and the page shows what the
 * backend answered — the new balance, or the translated error code.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { AxiosError, AxiosHeaders } from 'axios'
import AdminView from '@/views/AdminView.vue'
import type { AdminPlayer } from '@/types'

const h = vi.hoisted(() => ({
  isAdmin: vi.fn(),
  searchPlayers: vi.fn(),
  grant: vi.fn(),
}))

vi.mock('@/services/adminService', () => ({
  adminService: { isAdmin: h.isAdmin, searchPlayers: h.searchPlayers, grant: h.grant },
}))

const ALICE: AdminPlayer = { userId: 'guest:alice', nickname: 'Alice', balance: 10 }

function apiError(status: number, code: string) {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('fail', String(status), config, null, {
    status,
    statusText: '',
    headers: {},
    config,
    data: { error: 'x', code },
  })
}

async function mountAdmin() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/account', component: { template: '<div />' } },
      { path: '/admin', component: AdminView },
    ],
  })
  await router.push('/admin')
  await router.isReady()
  const wrapper = mount(AdminView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

async function pickAlice(wrapper: Awaited<ReturnType<typeof mountAdmin>>) {
  await wrapper.find('[data-testid="admin-search-input"]').setValue('ali')
  await wrapper.find('form.search-row').trigger('submit')
  await flushPromises()
  await wrapper.find('[data-testid="admin-player-guest:alice"]').trigger('click')
}

describe('AdminView', () => {
  beforeEach(() => {
    h.isAdmin.mockReset().mockResolvedValue(true)
    h.searchPlayers.mockReset().mockResolvedValue([ALICE])
    h.grant.mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it('shows only the admin-only note to a non-admin', async () => {
    h.isAdmin.mockResolvedValue(false)
    const wrapper = await mountAdmin()
    expect(wrapper.find('[data-testid="admin-not-admin"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="admin-search-input"]').exists()).toBe(false)
  })

  it('searches, grants, and shows the new balance from the backend', async () => {
    h.grant.mockResolvedValue({ ...ALICE, balance: 60 })
    const wrapper = await mountAdmin()
    await pickAlice(wrapper)
    expect(h.searchPlayers).toHaveBeenCalledWith('ali')

    await wrapper.find('[data-testid="admin-amount-input"]').setValue('50')
    await wrapper.find('[data-testid="admin-pin-input"]').setValue('4321')
    await wrapper.find('form.grant-form').trigger('submit')
    await flushPromises()

    expect(h.grant).toHaveBeenCalledWith('guest:alice', 50, '4321')
    expect(wrapper.find('[data-testid="admin-success"]').text()).toMatch(/Alice/)
    expect(wrapper.find('[data-testid="admin-success"]').text()).toMatch(/60/)
    expect(wrapper.find('[data-testid="admin-player-guest:alice"]').text()).toContain('60')
    expect(
      (wrapper.find('[data-testid="admin-pin-input"]').element as HTMLInputElement).value,
    ).toBe('')
  })

  it('shows the translated backend error and clears the PIN', async () => {
    h.grant.mockRejectedValue(apiError(403, 'WRONG_PIN'))
    const wrapper = await mountAdmin()
    await pickAlice(wrapper)
    await wrapper.find('[data-testid="admin-amount-input"]').setValue('5')
    await wrapper.find('[data-testid="admin-pin-input"]').setValue('0000')
    await wrapper.find('form.grant-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="admin-error"]').text()).toMatch(/密碼錯誤|Wrong PIN/)
    expect(wrapper.find('[data-testid="admin-success"]').exists()).toBe(false)
    expect(
      (wrapper.find('[data-testid="admin-pin-input"]').element as HTMLInputElement).value,
    ).toBe('')
  })

  it('shows "no matching players" for an empty search result', async () => {
    h.searchPlayers.mockResolvedValue([])
    const wrapper = await mountAdmin()
    await wrapper.find('[data-testid="admin-search-input"]').setValue('zzz')
    await wrapper.find('form.search-row').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-testid="admin-no-match"]').exists()).toBe(true)
  })
})
