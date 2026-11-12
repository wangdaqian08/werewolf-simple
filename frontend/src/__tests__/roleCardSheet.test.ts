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

  it('role card shows the knight rules', () => {
    mount(RoleCardSheet, { props: { role: 'KNIGHT' }, attachTo: document.body })
    expect(document.body.querySelector('.rc-name-zh')?.textContent).toBe('騎士')
    const rules = [...document.body.querySelectorAll('[data-testid="rc-rules"] li')]
    expect(rules).toHaveLength(7)
    expect(rules[0]?.textContent).toBe('陣營：好人陣營（神職）。')
    expect(rules[5]?.textContent).toBe('死者是警長時照常移交警徽。')
    expect(rules[6]?.textContent).toBe('騎士不能和機械狼、守墓人同時出現在同一個房間。')
  })

  it('gravekeeper role card shows the rules text', () => {
    mount(RoleCardSheet, { props: { role: 'GRAVEKEEPER' }, attachTo: document.body })
    expect(document.body.querySelector('.rc-name-zh')?.textContent).toBe('守墓人')
    const rules = [...document.body.querySelectorAll('[data-testid="rc-rules"] li')]
    expect(rules).toHaveLength(4)
    expect(rules[0]?.textContent).toBe('陣營：好人陣營（神職）。')
    expect(rules[1]?.textContent).toContain('第一晚沒有信息')
    expect(rules[2]?.textContent).toContain('昨日無人被放逐')
    expect(rules[3]?.textContent).toBe('守墓人不能和機械狼、騎士同時出現在同一個房間。')
  })

  it('mechanical wolf rules say it never shares a room with the knight or the gravekeeper', () => {
    mount(RoleCardSheet, { props: { role: 'MECHANICAL_WOLF' }, attachTo: document.body })
    const text = document.body.querySelector('[data-testid="rc-rules"]')?.textContent
    expect(text).toContain('機械狼不能和騎士、守墓人同時出現在同一個房間。')
    expect(text).not.toContain('學到騎士')
    expect(text).not.toContain('被騎士決鬥除外')
  })

  it('black wolf king rules mention 被騎士決鬥', () => {
    mount(RoleCardSheet, { props: { role: 'BLACK_WOLF_KING' }, attachTo: document.body })
    expect(document.body.querySelector('[data-testid="rc-rules"]')?.textContent).toContain(
      '被女巫毒死、自爆、被白狼王帶走或被騎士決鬥時不能開槍',
    )
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
