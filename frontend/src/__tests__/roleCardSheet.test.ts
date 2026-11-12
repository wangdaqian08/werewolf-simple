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
    expect(rules).toHaveLength(6)
    expect(rules[0]?.textContent).toBe('陣營：好人陣營（神職）。')
    expect(rules[5]?.textContent).toBe('死者是警長時照常移交警徽。')
  })

  it('gravekeeper role card shows the rules text', () => {
    mount(RoleCardSheet, { props: { role: 'GRAVEKEEPER' }, attachTo: document.body })
    expect(document.body.querySelector('.rc-name-zh')?.textContent).toBe('守墓人')
    const rules = [...document.body.querySelectorAll('[data-testid="rc-rules"] li')]
    expect(rules).toHaveLength(3)
    expect(rules[0]?.textContent).toBe('陣營：好人陣營（神職）。')
    expect(rules[1]?.textContent).toContain('第一晚沒有信息')
    expect(rules[2]?.textContent).toContain('昨日無人被放逐')
  })

  // Which roles can never share a board: its own section, from the same table the setup page enforces.
  const excluded = () =>
    [...document.body.querySelectorAll('[data-testid="rc-excludes"] [data-role]')].map((el) =>
      el.getAttribute('data-role'),
    )

  it.each([
    ['MECHANICAL_WOLF', ['KNIGHT', 'GRAVEKEEPER']],
    ['KNIGHT', ['MECHANICAL_WOLF', 'GRAVEKEEPER']],
    ['GRAVEKEEPER', ['MECHANICAL_WOLF', 'KNIGHT']],
    ['SEER', ['MEDIUM']],
    ['MEDIUM', ['SEER']],
  ])('%s card lists its excluded roles', (role, others) => {
    mount(RoleCardSheet, { props: { role }, attachTo: document.body })
    expect(excluded()).toEqual(others)
  })

  it('the excluded roles are named, and the rules list does not repeat them', () => {
    mount(RoleCardSheet, { props: { role: 'MECHANICAL_WOLF' }, attachTo: document.body })
    const section = document.body.querySelector('[data-testid="rc-excludes"]')
    expect(section?.textContent).toContain('互斥角色')
    expect(section?.textContent).toContain('騎士')
    expect(section?.textContent).toContain('守墓人')
    const rules = document.body.querySelector('[data-testid="rc-rules"]')?.textContent
    expect(rules).not.toContain('同時出現')
    expect(rules).not.toContain('學到騎士')
    expect(rules).not.toContain('被騎士決鬥除外')
  })

  it('a role with no excluded roles has no such section', () => {
    mount(RoleCardSheet, { props: { role: 'WITCH' }, attachTo: document.body })
    expect(document.body.querySelector('[data-testid="rc-excludes"]')).toBeNull()
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

  it('my own role card shows my task', () => {
    mount(RoleCardSheet, {
      props: { role: 'WITCH', task: { code: 'WITCH_SAVE_GOOD', score: 5, done: null } },
      attachTo: document.body,
    })
    expect(document.body.querySelector('[data-testid="task-name"]')?.textContent).toBe(
      '用解藥救下一名好人',
    )
  })

  it('a role card with no task shows no task line', () => {
    mount(RoleCardSheet, { props: { role: 'WITCH' }, attachTo: document.body })
    expect(document.body.querySelector('[data-testid="task-line"]')).toBeNull()
  })
})
