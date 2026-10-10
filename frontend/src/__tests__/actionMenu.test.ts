/**
 * Unit tests for ActionMenu.vue
 *
 * Covers:
 * - Wolf in allowed phases sees self-destruct action
 * - Non-wolf sees "暂无操作" (no actions available)
 * - NIGHT phase hides the chip entirely
 * - Confirm modal flow: click self-destruct → confirm modal → click confirm emits
 * - Cancel modal flow: click cancel → no event emitted
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ActionMenu from '@/components/ActionMenu.vue'
import type { GamePhase, GamePlayer, PlayerRole } from '@/types'

function makeProps(overrides: {
  phase?: GamePhase
  subPhase?: string
  myRole?: PlayerRole
  isAlive?: boolean
  players?: GamePlayer[]
  myUserId?: string
}) {
  return {
    phase: overrides.phase ?? 'DAY_DISCUSSION',
    subPhase: overrides.subPhase ?? 'RESULT_REVEALED',
    myRole: overrides.myRole ?? 'WEREWOLF',
    isAlive: overrides.isAlive ?? true,
    players: overrides.players,
    myUserId: overrides.myUserId,
  }
}

describe('ActionMenu', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // ── chip visibility ─────────────────────────────────────────────────────────

  it('chip is visible when phase is DAY_DISCUSSION', () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION' }),
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
  })

  it('chip is visible when phase is DAY_VOTING', () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_VOTING', subPhase: 'VOTING' }),
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
  })

  it('chip is visible when phase is SHERIFF_ELECTION', () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'SHERIFF_ELECTION', subPhase: 'SIGNUP' }),
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
  })

  it('chip is NOT visible when phase is NIGHT', () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'NIGHT', subPhase: 'WEREWOLF_PICK' }),
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(false)
  })

  it('chip is NOT visible when phase is ROLE_REVEAL', () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'ROLE_REVEAL' }),
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(false)
  })

  // ── drop-sheet content ──────────────────────────────────────────────────────

  it('wolf in DAY_DISCUSSION sees self-destruct option after opening menu', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'WEREWOLF', isAlive: true }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    // Teleport'd content is in the document
    expect(document.querySelector('[data-testid="action-menu-self-destruct"]')).toBeTruthy()
    wrapper.unmount()
  })

  it('villager in DAY_DISCUSSION sees "暂无操作" after opening menu', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'VILLAGER' }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(document.querySelector('[data-testid="action-menu-empty"]')).toBeTruthy()
    expect(document.querySelector('[data-testid="action-menu-self-destruct"]')).toBeFalsy()
    wrapper.unmount()
  })

  it('dead wolf sees "暂无操作" instead of self-destruct', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'WEREWOLF', isAlive: false }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(document.querySelector('[data-testid="action-menu-empty"]')).toBeTruthy()
    wrapper.unmount()
  })

  // ── confirm modal flow ──────────────────────────────────────────────────────

  it('clicking self-destruct shows confirm modal', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'WEREWOLF', isAlive: true }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    // Teleport'd content lives in the document, not wrapper
    const selfDestructBtn = document.querySelector(
      '[data-testid="action-menu-self-destruct"]',
    ) as HTMLElement
    selfDestructBtn?.click()
    await wrapper.vm.$nextTick()
    expect(document.querySelector('[data-testid="action-menu-confirm"]')).toBeTruthy()
    expect(document.querySelector('[data-testid="action-menu-cancel"]')).toBeTruthy()
    wrapper.unmount()
  })

  it('clicking confirm emits self-destruct event', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'WEREWOLF', isAlive: true }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    const selfDestructBtn = document.querySelector(
      '[data-testid="action-menu-self-destruct"]',
    ) as HTMLElement
    selfDestructBtn?.click()
    await wrapper.vm.$nextTick()
    const confirmBtn = document.querySelector('[data-testid="action-menu-confirm"]') as HTMLElement
    confirmBtn?.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('self-destruct')).toBeTruthy()
    wrapper.unmount()
  })

  it('clicking cancel does not emit self-destruct event', async () => {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ phase: 'DAY_DISCUSSION', myRole: 'WEREWOLF', isAlive: true }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    const selfDestructBtn = document.querySelector(
      '[data-testid="action-menu-self-destruct"]',
    ) as HTMLElement
    selfDestructBtn?.click()
    await wrapper.vm.$nextTick()
    const cancelBtn = document.querySelector('[data-testid="action-menu-cancel"]') as HTMLElement
    cancelBtn?.click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('self-destruct')).toBeFalsy()
    wrapper.unmount()
  })
})

describe('ActionMenu — 白狼王 take', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const players: GamePlayer[] = [
    { userId: 'k1', nickname: 'King', seatIndex: 1, isAlive: true, isSheriff: false },
    { userId: 'v2', nickname: 'Vic', seatIndex: 2, isAlive: true, isSheriff: false },
    { userId: 'd3', nickname: 'Dead', seatIndex: 3, isAlive: false, isSheriff: false },
  ]

  async function openConfirm(myRole: PlayerRole) {
    const wrapper = mount(ActionMenu, {
      props: makeProps({ myRole, players, myUserId: 'k1' }),
      attachTo: document.body,
    })
    await wrapper.find('[data-testid="action-menu-btn"]').trigger('click')
    ;(document.querySelector('[data-testid="action-menu-self-destruct"]') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    return wrapper
  }

  it('lists only alive players other than the king as take candidates', async () => {
    const wrapper = await openConfirm('WHITE_WOLF_KING')
    expect(document.querySelector('[data-testid="action-menu-take-v2"]')).toBeTruthy()
    expect(document.querySelector('[data-testid="action-menu-take-k1"]')).toBeFalsy()
    expect(document.querySelector('[data-testid="action-menu-take-d3"]')).toBeFalsy()
    wrapper.unmount()
  })

  it('emits the chosen player when the king confirms', async () => {
    const wrapper = await openConfirm('WHITE_WOLF_KING')
    ;(document.querySelector('[data-testid="action-menu-take-v2"]') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    ;(document.querySelector('[data-testid="action-menu-confirm"]') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('self-destruct')?.[0]).toEqual(['v2'])
    wrapper.unmount()
  })

  it('emits no target when the king confirms without choosing', async () => {
    const wrapper = await openConfirm('WHITE_WOLF_KING')
    ;(document.querySelector('[data-testid="action-menu-confirm"]') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('self-destruct')?.[0]).toEqual([undefined])
    wrapper.unmount()
  })

  it('a plain werewolf gets no take list', async () => {
    const wrapper = await openConfirm('WEREWOLF')
    expect(document.querySelector('[data-testid="action-menu-take-v2"]')).toBeFalsy()
    wrapper.unmount()
  })
})
