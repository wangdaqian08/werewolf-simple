/**
 * SPEECH before the host has set the order: the host draws a random first
 * speaker (tap to start, tap to stop, redraw) and then picks a direction.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SheriffElection from '@/components/SheriffElection.vue'
import type { GamePlayer, SheriffElectionState } from '@/types'

const PLAYERS = [
  { userId: 'u1', nickname: 'Host', seatIndex: 1 },
  { userId: 'u2', nickname: 'Alice', seatIndex: 2 },
  { userId: 'u5', nickname: 'Eve', seatIndex: 5 },
  { userId: 'u7', nickname: 'Gus', seatIndex: 7 },
] as unknown as GamePlayer[]

function election(running: string[], quit: string[] = []): SheriffElectionState {
  const nick = (id: string) => PLAYERS.find((p) => p.userId === id)!.nickname
  return {
    subPhase: 'SPEECH',
    timeRemaining: 0,
    candidates: [
      ...running.map((id) => ({ userId: id, nickname: nick(id), status: 'RUNNING' as const })),
      ...quit.map((id) => ({ userId: id, nickname: nick(id), status: 'QUIT' as const })),
    ],
    speakingOrder: [...running, ...quit],
    canVote: true,
  }
}

function mountAs(myUserId: string, isHost: boolean, el = election(['u2', 'u5', 'u7'])) {
  return mount(SheriffElection, {
    props: { election: el, myUserId, isHost, players: PLAYERS, actionPending: false },
  })
}

const tid = (id: string) => `[data-testid="${id}"]`

describe('SheriffElection — host draws the speech order', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('host: draw → stop → redraw; directions stay disabled until the number stops', async () => {
    const w = mountAs('u1', true)
    const cw = () => w.find(tid('sheriff-order-clockwise'))
    expect(w.find(tid('sheriff-order-number')).text()).toBe('?')
    expect(cw().attributes('disabled')).toBeDefined()

    await w.find(tid('sheriff-order-spin')).trigger('click') // start
    const seen = new Set<string>()
    for (let i = 0; i < 20; i++) {
      seen.add(w.find(tid('sheriff-order-number')).text())
      await vi.advanceTimersByTimeAsync(100)
    }
    // Only running candidates' seats appear, and the number really moves
    expect([...seen].every((s) => ['2', '5', '7'].includes(s))).toBe(true)
    expect(seen.size).toBeGreaterThan(1)
    expect(cw().attributes('disabled')).toBeDefined()

    await w.find(tid('sheriff-order-spin')).trigger('click') // stop
    const stopped = w.find(tid('sheriff-order-number')).text()
    await vi.advanceTimersByTimeAsync(500)
    expect(w.find(tid('sheriff-order-number')).text()).toBe(stopped)
    expect(cw().attributes('disabled')).toBeUndefined()

    await w.find(tid('sheriff-order-spin')).trigger('click') // redraw
    expect(cw().attributes('disabled')).toBeDefined()
  })

  it('host: picking a direction emits the drawn player and the direction', async () => {
    const w = mountAs('u1', true)
    await w.find(tid('sheriff-order-spin')).trigger('click')
    await vi.advanceTimersByTimeAsync(300)
    await w.find(tid('sheriff-order-spin')).trigger('click')
    const seat = w.find(tid('sheriff-order-number')).text()
    const drawnId = PLAYERS.find((p) => String(p.seatIndex) === seat)!.userId

    await w.find(tid('sheriff-order-counter_clockwise')).trigger('click')

    expect(w.emitted('setSpeechOrder')).toEqual([[drawnId, 'COUNTER_CLOCKWISE']])
  })

  it('host not running: no quit button; host running: quit button too', () => {
    expect(mountAs('u1', true).find(tid('sheriff-quit')).exists()).toBe(false)
    const running = mountAs('u2', true)
    expect(running.find(tid('sheriff-order-spin')).exists()).toBe(true)
    expect(running.find(tid('sheriff-quit')).exists()).toBe(true)
  })

  it('players see only the waiting message; a running player keeps the quit button', () => {
    const viewer = mountAs('u1', false)
    expect(viewer.find(tid('sheriff-order-waiting')).exists()).toBe(true)
    expect(viewer.find(tid('sheriff-order-spin')).exists()).toBe(false)
    expect(viewer.find(tid('sheriff-quit')).exists()).toBe(false)
    expect(mountAs('u5', false).find(tid('sheriff-quit')).exists()).toBe(true)
  })

  it('host: the drawn player quits before a direction is picked → back to draw, with a notice', async () => {
    const w = mountAs('u1', true)
    await w.find(tid('sheriff-order-spin')).trigger('click')
    await w.find(tid('sheriff-order-spin')).trigger('click')
    const seat = w.find(tid('sheriff-order-number')).text()
    const drawnId = PLAYERS.find((p) => String(p.seatIndex) === seat)!.userId
    const rest = ['u2', 'u5', 'u7'].filter((id) => id !== drawnId)

    await w.setProps({ election: election(rest, [drawnId]) })

    expect(w.find(tid('sheriff-order-number')).text()).toBe('?')
    expect(w.find(tid('sheriff-order-quit-notice')).text()).toContain(seat)
    expect(w.find(tid('sheriff-order-clockwise')).attributes('disabled')).toBeDefined()
  })

  it('once the order is set, the usual speech screen replaces the draw', () => {
    const w = mountAs('u1', true, { ...election(['u2', 'u5', 'u7']), currentSpeakerId: 'u5' })
    expect(w.find(tid('sheriff-order-spin')).exists()).toBe(false)
    expect(w.find(tid('sheriff-advance-speech')).exists()).toBe(true)
  })
})
