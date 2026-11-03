import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MechWolfTurn from '@/components/MechWolfTurn.vue'
import { roleName } from '@/utils/roleDefinitions'
import type { GamePlayer, MechNightSkill, MechWolfResult } from '@/types'

const p = (userId: string, seatIndex: number, isAlive = true): GamePlayer => ({
  userId,
  nickname: userId,
  seatIndex,
  isAlive,
  isSheriff: false,
})
const players = [p('me', 1), p('a', 2), p('b', 3), p('dead', 4, false)]

const mountPick = (skillTonight: MechNightSkill) =>
  mount(MechWolfTurn, {
    props: {
      subPhase: 'MECH_WOLF_PICK',
      mechWolf: { learnedRole: null, learnedPlayerId: null, skillTonight, result: null },
      players,
      myUserId: 'me',
    },
  })

const mountResult = (result: MechWolfResult) =>
  mount(MechWolfTurn, {
    props: {
      subPhase: 'MECH_WOLF_RESULT',
      mechWolf: { learnedRole: null, learnedPlayerId: null, skillTonight: 'NONE', result },
      players,
      myUserId: 'me',
    },
  })

describe('MechWolfTurn', () => {
  it('LEARN shows living other players and emits MECH_LEARN with the target', async () => {
    const w = mountPick('LEARN')
    expect(w.find('[data-testid="mech-learn-target-1"]').exists()).toBe(false) // self
    expect(w.find('[data-testid="mech-learn-target-4"]').exists()).toBe(false) // dead
    expect(w.find('[data-testid="mech-learn-confirm"]').attributes('disabled')).toBeDefined()
    await w.find('[data-testid="mech-learn-target-2"]').trigger('click')
    await w.find('[data-testid="mech-learn-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_LEARN', 'a']])
  })

  it('NONE shows only skip', async () => {
    const w = mountPick('NONE')
    expect(w.find('[data-testid="mech-skip"]').exists()).toBe(true)
    expect(w.find('[data-testid="mech-skill-confirm"]').exists()).toBe(false)
    expect(w.find('[data-testid="mech-skill-target-2"]').exists()).toBe(false)
    await w.find('[data-testid="mech-skip"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_SKIP']])
  })

  it('POISON emits MECH_POISON and excludes self', async () => {
    const w = mountPick('POISON')
    expect(w.find('[data-testid="mech-skill-target-1"]').exists()).toBe(false)
    await w.find('[data-testid="mech-skill-target-3"]').trigger('click')
    await w.find('[data-testid="mech-skill-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_POISON', 'b']])
  })

  it('CHECK_ROLE and CHECK_ALIGNMENT emit MECH_CHECK', async () => {
    for (const skill of ['CHECK_ROLE', 'CHECK_ALIGNMENT'] as const) {
      const w = mountPick(skill)
      await w.find('[data-testid="mech-skill-target-2"]').trigger('click')
      await w.find('[data-testid="mech-skill-confirm"]').trigger('click')
      expect(w.emitted('action')).toEqual([['MECH_CHECK', 'a']])
    }
  })

  it('PROTECT lists self as a target and emits MECH_PROTECT', async () => {
    const w = mountPick('PROTECT')
    await w.find('[data-testid="mech-skill-target-1"]').trigger('click')
    await w.find('[data-testid="mech-skill-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_PROTECT', 'me']])
  })

  it('a skill night can still be skipped', async () => {
    const w = mountPick('POISON')
    await w.find('[data-testid="mech-skip"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_SKIP']])
  })

  it('result shows learned role name', async () => {
    const w = mountResult({
      kind: 'LEARN',
      targetPlayerId: 'a',
      targetNickname: 'a',
      targetSeatIndex: 2,
      role: 'SEER',
    })
    const r = w.find('[data-testid="mech-result"]')
    expect(r.attributes('data-kind')).toBe('LEARN')
    expect(r.attributes('data-role')).toBe('SEER')
    expect(r.text()).toContain(roleName('SEER'))
    await w.find('[data-testid="mech-result-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MECH_CONFIRM']])
  })

  it('CHECK_ALIGNMENT result shows wolf/good only', () => {
    const w = mountResult({
      kind: 'CHECK',
      targetPlayerId: 'a',
      targetNickname: 'a',
      targetSeatIndex: 2,
      isWerewolf: true,
    })
    const r = w.find('[data-testid="mech-result"]')
    expect(r.attributes('data-kind')).toBe('CHECK')
    expect(r.attributes('data-role') ?? '').toBe('')
    expect(r.text()).toContain('狼人')
    expect(r.text()).not.toContain(roleName('SEER'))
  })

  it('SKIP result says no action tonight', () => {
    const w = mountResult({ kind: 'SKIP' })
    expect(w.find('[data-testid="mech-result"]').attributes('data-kind')).toBe('SKIP')
    expect(w.find('[data-testid="mech-result"]').text()).toContain('今晚無行動')
  })
})
