/**
 * Unit tests for GameSettingsModal.vue — in-game room info modal.
 *
 * Covers: room code + every config field rendered, role aggregation to
 * counts, close emits (backdrop + ✕), null-settings placeholder.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import GameSettingsModal from '@/components/GameSettingsModal.vue'
import { walletService } from '@/services/walletService'
import type { GameSettings } from '@/types'

vi.mock('@/services/walletService', () => ({ walletService: { getRewardRules: vi.fn() } }))

beforeEach(() => {
  vi.clearAllMocks()
  // Most tests do not care about the scoring table, so it loads empty and stays hidden.
  vi.mocked(walletService.getRewardRules).mockResolvedValue([])
})

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
  noKillAllowed: false,
  guardSkipAllowed: false,
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
    expect(bodyEl('settings-witch-self-save')?.textContent).toContain('仅首夜')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠城')
    expect(bodyEl('settings-no-kill')?.textContent).toContain('空刀')
    expect(bodyEl('settings-no-kill')?.textContent).toContain('禁止')

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

describe('GameSettingsModal — 空刀 rule', () => {
  it('shows 允许 when the room enables 空刀', () => {
    const wrapper = mountModal({ settings: { ...SETTINGS, noKillAllowed: true } })
    expect(bodyEl('settings-no-kill')?.textContent).toContain('允许')
    wrapper.unmount()
  })
})

describe('GameSettingsModal — 空守 rule', () => {
  it('shows 禁止 by default and 允许 when the room enables 空守', () => {
    const off = mountModal()
    expect(bodyEl('settings-guard-skip')?.textContent).toContain('禁止')
    off.unmount()
    const on = mountModal({ settings: { ...SETTINGS, guardSkipAllowed: true } })
    expect(bodyEl('settings-guard-skip')?.textContent).toContain('允许')
    on.unmount()
  })
})

describe('GameSettingsModal — 积分规则', () => {
  it('lists the scoring table, marking a per-occurrence line and a penalty', async () => {
    vi.mocked(walletService.getRewardRules).mockResolvedValue([
      { reason: 'WIN', label: '胜利方奖励', detail: '本局获胜的阵营', amount: 20, perUnit: false },
      { reason: 'WOLF_VOTE', label: '投出狼人', detail: '每天最多一次', amount: 3, perUnit: true },
      // A future penalty is just another row with a negative amount.
      {
        reason: 'PARTICIPATION',
        label: '投出好人',
        detail: '把好人投出局',
        amount: -4,
        perUnit: false,
      },
    ])
    const wrapper = mountModal()
    await flushPromises()

    const text = bodyEl('settings-rewards')!.textContent ?? ''
    expect(text).toContain('+20')
    expect(text).toContain('+3 / 次')
    expect(text).toContain('−4')
    expect(text).toContain('本局积分最低 0 分')
    wrapper.unmount()
  })

  it('shows no floor note while nothing can cost you points', async () => {
    vi.mocked(walletService.getRewardRules).mockResolvedValue([
      { reason: 'WIN', label: '胜利方奖励', detail: '本局获胜的阵营', amount: 20, perUnit: false },
    ])
    const wrapper = mountModal()
    await flushPromises()

    expect(bodyEl('settings-rewards')!.textContent).not.toContain('本局积分最低')
    wrapper.unmount()
  })

  it('stays hidden when the table cannot be loaded', async () => {
    vi.mocked(walletService.getRewardRules).mockRejectedValue(new Error('offline'))
    const wrapper = mountModal()
    await flushPromises()

    expect(bodyEl('settings-rewards')).toBeNull()
    wrapper.unmount()
  })
})
