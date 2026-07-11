/**
 * Unit tests for GameSettingsModal.vue — in-game room info modal.
 *
 * Covers: room code + every config field rendered, role aggregation to
 * counts, close emits (backdrop + ✕), null-settings placeholder.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GameSettingsModal from '@/components/GameSettingsModal.vue'
import type { GameSettings } from '@/types'

const SETTINGS: GameSettings = {
  roomCode: '358',
  totalPlayers: 9,
  wolfCount: 3,
  roles: [
    'WEREWOLF',
    'WEREWOLF',
    'WEREWOLF',
    'SEER',
    'WITCH',
    'GUARD',
    'VILLAGER',
    'VILLAGER',
    'VILLAGER',
  ],
  hasSheriff: false,
  witchSelfSaveAllowed: true,
  winCondition: 'HARD_MODE',
}

function mountModal(overrides: Partial<{ settings: GameSettings | null; open: boolean }> = {}) {
  return mount(GameSettingsModal, {
    props: { settings: SETTINGS, open: true, ...overrides },
    attachTo: document.body,
  })
}

const bodyEl = (testid: string) => document.body.querySelector(`[data-testid="${testid}"]`)

describe('GameSettingsModal — content', () => {
  it('renders room code and all config fields when open', () => {
    const wrapper = mountModal()

    expect(bodyEl('settings-modal')).not.toBeNull()
    expect(bodyEl('settings-room-code')?.textContent).toContain('358')
    expect(bodyEl('settings-player-count')?.textContent).toContain('9')
    expect(bodyEl('settings-sheriff')?.textContent).toContain('无')
    expect(bodyEl('settings-witch-self-save')?.textContent).toContain('允许')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠城')

    wrapper.unmount()
  })

  it('aggregates the roles multiset into per-role counts', () => {
    const wrapper = mountModal()

    expect(bodyEl('settings-role-WEREWOLF')?.textContent).toContain('狼人')
    expect(bodyEl('settings-role-WEREWOLF')?.textContent).toContain('×3')
    expect(bodyEl('settings-role-SEER')?.textContent).toContain('×1')
    expect(bodyEl('settings-role-VILLAGER')?.textContent).toContain('×3')
    // Roles not in the game are not listed
    expect(bodyEl('settings-role-HUNTER')).toBeNull()
    expect(bodyEl('settings-role-IDIOT')).toBeNull()

    wrapper.unmount()
  })

  it('renders CLASSIC win condition as 屠边 and sheriff-enabled as 有', () => {
    const wrapper = mountModal({
      settings: { ...SETTINGS, hasSheriff: true, winCondition: 'CLASSIC' },
    })

    expect(bodyEl('settings-sheriff')?.textContent).toContain('有')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠边')

    wrapper.unmount()
  })

  it('renders a placeholder (no crash) when settings is null', () => {
    const wrapper = mountModal({ settings: null })

    expect(bodyEl('settings-modal')).not.toBeNull()
    expect(bodyEl('settings-empty')).not.toBeNull()
    expect(bodyEl('settings-room-code')).toBeNull()

    wrapper.unmount()
  })

  it('renders nothing when closed', () => {
    const wrapper = mountModal({ open: false })
    expect(bodyEl('settings-modal')).toBeNull()
    wrapper.unmount()
  })
})

describe('GameSettingsModal — close behaviour', () => {
  it('emits close when the ✕ button is clicked', async () => {
    const wrapper = mountModal()
    ;(bodyEl('settings-close') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('emits close when the backdrop is clicked', async () => {
    const wrapper = mountModal()
    ;(bodyEl('settings-backdrop') as HTMLElement).click()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
