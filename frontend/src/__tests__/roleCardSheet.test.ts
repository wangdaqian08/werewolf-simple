import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import RoleCardSheet from '@/components/RoleCardSheet.vue'
import RoleComposition from '@/components/RoleComposition.vue'
import type { GamePlayer } from '@/types'

// Unmount every sheet after each test so its page-scroll lock is released.
enableAutoUnmount(afterEach)

const sheet = () => document.body.querySelector('[data-testid="role-card-sheet"]')
const mate = (userId: string, seatIndex: number, nickname: string, isAlive = true) =>
  ({ userId, seatIndex, nickname, isAlive }) as GamePlayer

describe('RoleCardSheet', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('lists the role rules, one per line', () => {
    mount(RoleCardSheet, { props: { role: 'GUARD' }, attachTo: document.body })
    const rules = [...document.body.querySelectorAll('[data-testid="rc-rules"] li')]
    expect(rules).toHaveLength(5)
    expect(rules[0]?.textContent).toBe('陣營：好人陣營（神職）。')
    expect(rules[2]?.textContent).toBe('不能連續兩晚守護同一名玩家。')
  })

  it('shows wolf teammates, a dead one struck through', () => {
    mount(RoleCardSheet, {
      props: { role: 'WEREWOLF', teammates: [mate('u3', 3, 'Cat'), mate('u5', 5, 'Eve', false)] },
      attachTo: document.body,
    })
    const mates = [...document.body.querySelectorAll('[data-testid="rc-teammates"] .rc-mate')]
    expect(mates.map((m) => m.textContent?.trim())).toEqual(['3號 · Cat', '5號 · Eve'])
    expect(mates[1]?.classList).toContain('rc-mate-dead')
  })

  it('has no teammates section without teammates', () => {
    mount(RoleCardSheet, { props: { role: 'SEER' }, attachTo: document.body })
    expect(document.body.querySelector('[data-testid="rc-teammates"]')).toBeNull()
  })

  it('tapping a composition chip opens that role', async () => {
    const w = mount(RoleComposition, {
      props: { totalPlayers: 9, wolfCount: 3, roles: ['WEREWOLF', 'VILLAGER', 'WITCH'] },
      attachTo: document.body,
    })
    expect(sheet()).toBeNull()
    await w.find('[data-role="WITCH"]').trigger('click')
    expect(sheet()?.querySelector('.rc-name-zh')?.textContent).toBe('女巫')
    ;(sheet()?.querySelector('.history-close') as HTMLButtonElement).click()
    await w.vm.$nextTick()
    expect(sheet()).toBeNull()
  })

  it('locks page scroll while open, so a swipe cannot move the page behind it', () => {
    const w = mount(RoleCardSheet, { props: { role: 'SEER' }, attachTo: document.body })
    expect(document.body.style.position).toBe('fixed')
    w.unmount()
    expect(document.body.style.position).toBe('')
  })
})
