/**
 * RoleHistory.vue — private night-action history opened from the role card.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleHistory from '@/components/RoleHistory.vue'
import type { RoleHistory as RoleHistoryData } from '@/types'

const ref = (userId: string, seatIndex: number) => ({ userId, nickname: userId, seatIndex })

async function openPanel(history: RoleHistoryData) {
  const wrapper = mount(RoleHistory, { props: { history } })
  expect(wrapper.find('[data-testid="role-history"]').exists()).toBe(false)
  await wrapper.find('[data-testid="role-history-btn"]').trigger('click')
  return wrapper
}

const rows = (wrapper: Awaited<ReturnType<typeof openPanel>>) =>
  wrapper.findAll('[data-testid="role-history-entry"]').map((r) => r.text().replace(/\s+/g, ' '))

describe('RoleHistory', () => {
  it('renders nothing when there is no history (other roles, dead players)', () => {
    const wrapper = mount(RoleHistory, { props: { history: null } })
    expect(wrapper.find('[data-testid="role-history-btn"]').exists()).toBe(false)
  })

  it('witch: last night victim, antidote save and poison with rounds', async () => {
    const wrapper = await openPanel({
      role: 'WITCH',
      lastNightAttacked: { round: 3, target: ref('Amy', 2) },
      entries: [
        { round: 1, action: 'ANTIDOTE', target: ref('Bob', 4) },
        { round: 2, action: 'POISON', target: ref('Cat', 5) },
      ],
    })
    expect(wrapper.find('[data-testid="role-history-last-night"]').text()).toContain('2号 Amy')
    expect(rows(wrapper)).toEqual(['第1夜 · 解药救了 4号 Bob', '第2夜 · 毒药毒了 5号 Cat'])
  })

  it('witch: no last-night line once the antidote is used', async () => {
    const wrapper = await openPanel({
      role: 'WITCH',
      lastNightAttacked: null,
      entries: [{ round: 1, action: 'ANTIDOTE', target: ref('Bob', 4) }],
    })
    expect(wrapper.find('[data-testid="role-history-last-night"]').exists()).toBe(false)
  })

  it('seer: each check with round and result', async () => {
    const wrapper = await openPanel({
      role: 'SEER',
      entries: [
        { round: 1, action: 'CHECK', target: ref('Wolfy', 3), isWerewolf: true },
        { round: 2, action: 'CHECK', target: ref('Gina', 6), isWerewolf: false },
      ],
    })
    expect(rows(wrapper)).toEqual(['第1夜 · 查验 3号 Wolfy → 狼人', '第2夜 · 查验 6号 Gina → 好人'])
  })

  it('guard: each protected player, skipped nights shown as 空守', async () => {
    const wrapper = await openPanel({
      role: 'GUARD',
      entries: [
        { round: 1, action: 'GUARD', target: ref('Amy', 2) },
        { round: 2, action: 'GUARD', target: null },
      ],
    })
    expect(rows(wrapper)).toEqual(['第1夜 · 守护 2号 Amy', '第2夜 · 空守'])
  })

  it('wolf: each wolf kill, 空刀 nights shown', async () => {
    const wrapper = await openPanel({
      role: 'WOLF',
      entries: [
        { round: 1, action: 'KILL', target: ref('Amy', 2) },
        { round: 2, action: 'KILL', target: null },
      ],
    })
    expect(rows(wrapper)).toEqual(['第1夜 · 刀了 2号 Amy', '第2夜 · 空刀'])
  })

  it('shows 暂无记录 when there are no entries yet', async () => {
    const wrapper = await openPanel({ role: 'SEER', entries: [] })
    expect(wrapper.find('[data-testid="role-history"]').text()).toContain('暂无记录')
  })
})
