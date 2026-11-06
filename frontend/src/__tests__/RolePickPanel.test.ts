import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import RolePickPanel from '@/components/RolePickPanel.vue'
import type { RolePickChoice, RolePickView } from '@/types'

function view(opts: Partial<RolePickView> = {}): RolePickView {
  return {
    choice: null,
    frozenCredits: 0,
    prices: { WOLF: 20, GOD: 20, VILLAGER: 20 },
    seats: { WOLF: 3, GOD: 3, VILLAGER: 3 },
    ...opts,
  }
}

async function mountPanel(
  opts: {
    view?: RolePickView
    balance?: number | null
    locked?: boolean
    busy?: boolean
    immunityPrice?: number | null
  } = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/account', name: 'account', component: { template: '<div />' } }],
  })
  await router.push('/account')
  return mount(RolePickPanel, {
    props: {
      view: opts.view ?? view(),
      balance: opts.balance === undefined ? 100 : opts.balance,
      locked: opts.locked ?? false,
      busy: opts.busy ?? false,
      immunityPrice: opts.immunityPrice ?? null,
    },
    global: { plugins: [router] },
  })
}

const card = (w: Awaited<ReturnType<typeof mountPanel>>, c: RolePickChoice) =>
  w.find(`[data-testid="role-pick-${c}"]`)
const disabled = (w: Awaited<ReturnType<typeof mountPanel>>, c: RolePickChoice) =>
  (card(w, c).element as HTMLButtonElement).disabled

describe('RolePickPanel', () => {
  it('renders four cards; paid ones show price and seat count', async () => {
    const w = await mountPanel()
    for (const c of ['NONE', 'WOLF', 'GOD', 'VILLAGER'] as const)
      expect(card(w, c).exists()).toBe(true)
    expect(card(w, 'WOLF').text()).toContain('◈20')
    expect(card(w, 'WOLF').text()).toContain('本局 3 個名額')
    expect(card(w, 'NONE').text()).not.toContain('個名額')
    expect(card(w, 'NONE').text()).toContain('無所畏懼，哪個角色都玩得好')
  })

  it('disables a card whose camp has 0 seats', async () => {
    const w = await mountPanel({ view: view({ seats: { WOLF: 0, GOD: 3, VILLAGER: 3 } }) })
    expect(disabled(w, 'WOLF')).toBe(true)
    expect(card(w, 'WOLF').text()).toContain('本局無此陣營')
    expect(disabled(w, 'GOD')).toBe(false)
  })

  it('disables an unaffordable card with a top-up link to the account route', async () => {
    const w = await mountPanel({ balance: 10 })
    expect(disabled(w, 'GOD')).toBe(true)
    expect(card(w, 'GOD').text()).toContain('餘額不足')
    expect(w.find('[data-testid="role-pick-topup-GOD"]').attributes('href')).toBe('/account')
    expect(disabled(w, 'NONE')).toBe(false)
  })

  it('keeps the current choice enabled even when the balance is low', async () => {
    const w = await mountPanel({ balance: 0, view: view({ choice: 'WOLF' }) })
    expect(disabled(w, 'WOLF')).toBe(false)
  })

  it('disables a card missing from prices as unavailable', async () => {
    const w = await mountPanel({ view: view({ prices: { WOLF: 20, GOD: 20 } }) })
    expect(disabled(w, 'VILLAGER')).toBe(true)
    expect(card(w, 'VILLAGER').text()).toContain('暫不可用')
  })

  it('NONE is disabled only when locked or busy', async () => {
    expect(disabled(await mountPanel({ balance: 0 }), 'NONE')).toBe(false)
    expect(disabled(await mountPanel({ busy: true }), 'NONE')).toBe(true)
    expect(disabled(await mountPanel({ locked: true }), 'NONE')).toBe(true)
  })

  it('emits choose with the card value', async () => {
    const w = await mountPanel()
    await card(w, 'GOD').trigger('click')
    expect(w.emitted('choose')).toEqual([['GOD']])
  })

  it('locked disables every card and shows the lock note', async () => {
    const w = await mountPanel({ locked: true })
    for (const c of ['NONE', 'WOLF', 'GOD', 'VILLAGER'] as const) expect(disabled(w, c)).toBe(true)
    expect(w.find('[data-testid="role-pick-locked"]').exists()).toBe(true)
  })

  it('labels the balance', async () => {
    const w = await mountPanel({ balance: 80 })
    expect(w.find('[data-testid="rp-balance"]').text()).toBe('餘額 ◈80')
  })

  it('shows available balance next to the frozen amount', async () => {
    const w = await mountPanel({ balance: 60, view: view({ frozenCredits: 20 }) })
    const t = w.find('[data-testid="rp-balance"]').text()
    expect(t).toContain('餘額 ◈60')
    expect(t).toContain('（凍結 20）')
  })

  it('shows frozen credits only when positive', async () => {
    expect((await mountPanel()).find('[data-testid="role-pick-frozen"]').exists()).toBe(false)
    const w = await mountPanel({ view: view({ frozenCredits: 50 }) })
    expect(w.find('[data-testid="role-pick-frozen"]').text()).toBe('（凍結 50）')
  })

  it('a paid choice shows the draw note with camp, seats and price', async () => {
    const w = await mountPanel({ view: view({ choice: 'WOLF' }) })
    const note = w.find('[data-testid="role-pick-draw-note"]').text()
    expect(note).toContain('狼')
    expect(note).toContain('3')
    expect(note).toContain('20')
    expect(
      (await mountPanel({ view: view({ choice: 'NONE' }) }))
        .find('[data-testid="role-pick-draw-note"]')
        .exists(),
    ).toBe(false)
  })

  it('WOLF plus a held immunity perk shows the immunity hint with its price', async () => {
    const w = await mountPanel({ view: view({ choice: 'WOLF' }), immunityPrice: 30 })
    expect(w.find('[data-testid="role-pick-immunity-hint"]').text()).toContain('30')
    const g = await mountPanel({ view: view({ choice: 'GOD' }), immunityPrice: 30 })
    expect(g.find('[data-testid="role-pick-immunity-hint"]').exists()).toBe(false)
  })

  it('renders the top-up link outside any button and it navigates to account', async () => {
    const w = await mountPanel({ balance: 10 })
    const link = w.find('[data-testid="role-pick-topup-GOD"]')
    expect(link.element.closest('button')).toBeNull()
    await link.trigger('click')
    await flushPromises()
    expect(w.vm.$router.currentRoute.value.name).toBe('account')
  })
})
