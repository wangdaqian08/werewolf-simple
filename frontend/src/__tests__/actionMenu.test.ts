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
import type { GamePhase, PlayerRole } from '@/types'

function makeProps(overrides: {
  phase?: GamePhase
  subPhase?: string
  myRole?: PlayerRole
  isAlive?: boolean
}) {
  return {
    phase: overrides.phase ?? 'DAY_DISCUSSION',
    subPhase: overrides.subPhase ?? 'RESULT_REVEALED',
    myRole: overrides.myRole ?? 'WEREWOLF',
    isAlive: overrides.isAlive ?? true,
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
