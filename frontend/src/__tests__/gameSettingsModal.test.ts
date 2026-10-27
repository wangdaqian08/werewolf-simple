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
import type { GameSettings, RewardRule } from '@/types'

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
    expect(bodyEl('settings-sheriff')?.textContent).toContain('無')
    expect(bodyEl('settings-witch-self-save')?.textContent).toContain('僅首夜')
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

  it('renders CLASSIC win condition as 屠邊 and sheriff-enabled as 有', () => {
    const wrapper = mountModal({
      settings: { ...SETTINGS, hasSheriff: true, winCondition: 'CLASSIC' },
    })

    expect(bodyEl('settings-sheriff')?.textContent).toContain('有')
    expect(bodyEl('settings-win-condition')?.textContent).toContain('屠邊')

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
  it('shows 允許 when the room enables 空刀', () => {
    const wrapper = mountModal({ settings: { ...SETTINGS, noKillAllowed: true } })
    expect(bodyEl('settings-no-kill')?.textContent).toContain('允許')
    wrapper.unmount()
  })
})

describe('GameSettingsModal — 空守 rule', () => {
  it('shows 禁止 by default and 允許 when the room enables 空守', () => {
    const off = mountModal()
    expect(bodyEl('settings-guard-skip')?.textContent).toContain('禁止')
    off.unmount()
    const on = mountModal({ settings: { ...SETTINGS, guardSkipAllowed: true } })
    expect(bodyEl('settings-guard-skip')?.textContent).toContain('允許')
    on.unmount()
  })
})

describe('GameSettingsModal — 積分規則', () => {
  const rule = (
    reason: RewardRule['reason'],
    group: RewardRule['group'],
    label: string,
    amount: number,
    unit: string | null = null,
  ): RewardRule => ({ reason, group, label, detail: `${label}的說明`, amount, unit })

  it('lists the scoring table, marking a per-day rate, a per-occurrence one and a penalty', async () => {
    vi.mocked(walletService.getRewardRules).mockResolvedValue([
      rule('WIN', 'BASE', '勝利方獎勵', 20),
      rule('WOLF_SURVIVAL', 'BASE', '狼人存活', 4, '天'),
      rule('WOLF_VOTE', 'BONUS', '投出狼人', 3, '次'),
      // A future penalty is just another row, in the group that says it costs you.
      rule('PARTICIPATION', 'PENALTY', '投出好人', -4),
    ])
    const wrapper = mountModal()
    await flushPromises()

    const text = bodyEl('settings-rewards')!.textContent ?? ''
    expect(text).toContain('+20')
    // Each rule names its own unit: a rate per day reads 天, a per-occurrence one 次.
    expect(text).toContain('+4 / 天')
    expect(text).toContain('+3 / 次')
    expect(text).toContain('−4')
    expect(text).toContain('本局積分最低 0 分')
    wrapper.unmount()
  })

  it('groups the one-of base rules apart from what stacks on them', async () => {
    vi.mocked(walletService.getRewardRules).mockResolvedValue([
      rule('WIN', 'BASE', '勝利方獎勵', 20),
      rule('SHERIFF', 'BONUS', '當選／持有警徽', 5),
    ])
    const wrapper = mountModal()
    await flushPromises()

    expect(bodyEl('settings-reward-group-BASE')!.textContent).toContain('基礎分')
    expect(bodyEl('settings-reward-group-BONUS')!.textContent).toContain('加分項')
    // Nothing costs points yet, so the penalty heading never appears.
    expect(bodyEl('settings-reward-group-PENALTY')).toBeNull()
    wrapper.unmount()
  })

  it('shows no floor note while nothing can cost you points', async () => {
    vi.mocked(walletService.getRewardRules).mockResolvedValue([
      rule('WIN', 'BASE', '勝利方獎勵', 20),
    ])
    const wrapper = mountModal()
    await flushPromises()

    expect(bodyEl('settings-rewards')!.textContent).not.toContain('本局積分最低')
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
