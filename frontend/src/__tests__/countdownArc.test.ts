import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CountdownArc from '@/components/CountdownArc.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function make(overrides: {
  remainingMs?: number
  durationMs?: number
  running?: boolean
  isHost?: boolean
}) {
  return mount(CountdownArc, {
    props: {
      remainingMs: 0,
      durationMs: 0,
      running: false,
      isHost: false,
      ...overrides,
    },
  })
}

describe('CountdownArc — idle state', () => {
  it('renders "—" when idle (running=false, durationMs=0)', () => {
    const w = make({ remainingMs: 0, durationMs: 0, running: false })
    expect(w.find('.ca-inner').text()).toBe('—')
  })

  it('host sees 1:00 and 2:00 preset pills when idle', () => {
    const w = make({ remainingMs: 0, durationMs: 0, running: false, isHost: true })
    expect(w.find('[data-testid="phase-countdown-start-60"]').exists()).toBe(true)
    expect(w.find('[data-testid="phase-countdown-start-120"]').exists()).toBe(true)
    expect(w.find('[data-testid="phase-countdown-stop"]').exists()).toBe(false)
  })

  it('non-host does not see preset pills', () => {
    const w = make({ remainingMs: 0, durationMs: 0, running: false, isHost: false })
    expect(w.find('[data-testid="phase-countdown-start-60"]').exists()).toBe(false)
  })
})

describe('CountdownArc — running state', () => {
  it('renders time label and no urgent class when remainingMs > 10_000', () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true })
    expect(w.find('.ca-inner').text()).toBe('0:30')
    expect(w.find('.ca-inner').classes()).not.toContain('urgent')
  })

  it('applies urgent class when remainingMs <= 10_000', () => {
    const w = make({ remainingMs: 8_000, durationMs: 60_000, running: true })
    expect(w.find('.ca-inner').classes()).toContain('urgent')
  })

  it('host sees Stop pill when running', () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true, isHost: true })
    expect(w.find('[data-testid="phase-countdown-stop"]').exists()).toBe(true)
    expect(w.find('[data-testid="phase-countdown-start-60"]').exists()).toBe(false)
  })
})

describe('CountdownArc — stopped state (running=false, durationMs>0)', () => {
  it('renders muted text when stopped', () => {
    const w = make({ remainingMs: 0, durationMs: 60_000, running: false })
    // idle from host perspective: pill is displayed (stopped renders as idle for host)
    expect(w.find('.ca-inner').classes()).toContain('idle')
  })
})

describe('CountdownArc — testid and data-remaining-ms', () => {
  it('root has data-testid="phase-countdown"', () => {
    const w = make({})
    expect(w.find('[data-testid="phase-countdown"]').exists()).toBe(true)
  })

  it('data-remaining-ms reflects localRemaining', () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true })
    const root = w.find('[data-testid="phase-countdown"]')
    const attr = Number(root.attributes('data-remaining-ms'))
    expect(attr).toBeGreaterThanOrEqual(29_800)
    expect(attr).toBeLessThanOrEqual(30_200)
  })
})

describe('CountdownArc — tick decrement', () => {
  it('decreases data-remaining-ms by ~1000 after 1 second', async () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true })
    const before = Number(w.find('[data-testid="phase-countdown"]').attributes('data-remaining-ms'))

    vi.advanceTimersByTime(1000)
    await flushPromises()
    await w.vm.$nextTick()

    const after = Number(w.find('[data-testid="phase-countdown"]').attributes('data-remaining-ms'))
    expect(before - after).toBeGreaterThanOrEqual(800)
    expect(before - after).toBeLessThanOrEqual(1200)
  })
})

describe('CountdownArc — prop reset', () => {
  it('resets snapshot when remainingMs prop changes', async () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true })

    // Advance 2 ticks so localRemaining has decremented a bit
    vi.advanceTimersByTime(400)
    await flushPromises()
    await w.vm.$nextTick()

    // Server sends a new remainingMs update
    await w.setProps({ remainingMs: 55_000, durationMs: 60_000, running: true })
    await w.vm.$nextTick()

    const attr = Number(w.find('[data-testid="phase-countdown"]').attributes('data-remaining-ms'))
    // Should be close to the new prop value (snapshot reset)
    expect(attr).toBeGreaterThanOrEqual(54_500)
    expect(attr).toBeLessThanOrEqual(55_500)
  })
})

describe('CountdownArc — emit events', () => {
  it('emits start-timer with 60 when host clicks 1:00 pill', async () => {
    const w = make({ remainingMs: 0, durationMs: 0, running: false, isHost: true })
    await w.find('[data-testid="phase-countdown-start-60"]').trigger('click')
    expect(w.emitted('start-timer')?.[0]).toEqual([60])
  })

  it('emits start-timer with 120 when host clicks 2:00 pill', async () => {
    const w = make({ remainingMs: 0, durationMs: 0, running: false, isHost: true })
    await w.find('[data-testid="phase-countdown-start-120"]').trigger('click')
    expect(w.emitted('start-timer')?.[0]).toEqual([120])
  })

  it('emits stop-timer when host clicks Stop pill', async () => {
    const w = make({ remainingMs: 30_000, durationMs: 60_000, running: true, isHost: true })
    await w.find('[data-testid="phase-countdown-stop"]').trigger('click')
    expect(w.emitted('stop-timer')).toBeTruthy()
  })
})
