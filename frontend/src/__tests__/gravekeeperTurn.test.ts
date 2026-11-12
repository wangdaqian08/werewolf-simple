import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GravekeeperTurn from '@/components/GravekeeperTurn.vue'
import type { NightPhaseState } from '@/types'

type Result = NonNullable<NightPhaseState['gravekeeperResult']>

const exiled = (result: 'GOOD' | 'WOLF'): Result => ({
  result,
  exiledPlayerId: 'a',
  exiledNickname: 'Amy',
  exiledSeatIndex: 2,
  history: [{ round: 2, nickname: 'Amy', isWerewolf: result === 'WOLF' }],
})
const none: Result = {
  result: 'NONE',
  exiledPlayerId: null,
  exiledNickname: null,
  exiledSeatIndex: null,
  history: [],
}
const card = (w: ReturnType<typeof mount>) => w.find('[data-testid="gravekeeper-result"]')

describe('GravekeeperTurn', () => {
  it.each([
    { gravekeeperResult: exiled('WOLF'), dayNumber: 2, verdict: 'WOLF', text: '狼人', seat: '2' },
    { gravekeeperResult: exiled('GOOD'), dayNumber: 3, verdict: 'GOOD', text: '好人', seat: '2' },
    { gravekeeperResult: none, dayNumber: 2, verdict: 'NO_EXILE', text: '昨日無人被放逐' },
    { gravekeeperResult: none, dayNumber: 1, verdict: 'FIRST_NIGHT', text: '首夜無信息' },
  ])(
    'renders the verdict from the nightPhase prop: $verdict on night $dayNumber',
    ({ gravekeeperResult, dayNumber, verdict, text, seat }) => {
      const w = mount(GravekeeperTurn, { props: { gravekeeperResult, dayNumber } })
      expect(card(w).attributes('data-verdict')).toBe(verdict)
      expect(card(w).attributes('data-exiled-seat')).toBe(seat)
      expect(w.find('[data-testid="gravekeeper-verdict"]').text()).toBe(text)
      const who = w.find('[data-testid="gravekeeper-exiled"]')
      if (seat) expect(who.text()).toBe('2號 · Amy')
      else expect(who.exists()).toBe(false)
    },
  )

  it('lists the earlier exiles with their camp, or says there are none', () => {
    const w = mount(GravekeeperTurn, {
      props: {
        gravekeeperResult: {
          ...exiled('GOOD'),
          history: [
            { round: 2, nickname: 'Wes', isWerewolf: true },
            { round: 3, nickname: 'Amy', isWerewolf: false },
          ],
        },
        dayNumber: 3,
      },
    })
    const rows = w.findAll('[data-testid="gravekeeper-history-entry"]')
    expect(rows.map((r) => r.findAll('span').map((s) => s.text()))).toEqual([
      ['第 2 夜', 'Wes', '→ 狼人'],
      ['第 3 夜', 'Amy', '→ 好人'],
    ])
    const empty = mount(GravekeeperTurn, { props: { gravekeeperResult: none, dayNumber: 1 } })
    expect(empty.find('[data-testid="gravekeeper-history-entry"]').exists()).toBe(false)
    expect(empty.find('[data-testid="gravekeeper-history"]').text()).toContain('暫無歷史記錄')
  })

  it('confirm emits GRAVEKEEPER_CONFIRM', async () => {
    const w = mount(GravekeeperTurn, { props: { gravekeeperResult: exiled('WOLF'), dayNumber: 2 } })
    await w.find('[data-testid="gravekeeper-result-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['GRAVEKEEPER_CONFIRM']])
  })

  it('confirm button renders even without a result', async () => {
    const w = mount(GravekeeperTurn, { props: { dayNumber: 2 } })
    expect(card(w).exists()).toBe(false)
    await w.find('[data-testid="gravekeeper-result-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['GRAVEKEEPER_CONFIRM']])
  })

  it('actionPending disables the confirm button', () => {
    const w = mount(GravekeeperTurn, {
      props: { gravekeeperResult: none, dayNumber: 1, actionPending: true },
    })
    expect(
      w.find('[data-testid="gravekeeper-result-confirm"]').attributes('disabled'),
    ).toBeDefined()
  })
})
