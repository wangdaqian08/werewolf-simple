import { describe, expect, it } from 'vitest'
import { nightNarrationKey } from '@/utils/nightNarration'
import type { AudioSequence } from '@/types'

let t = 0
const seq = (...audioFiles: string[]): AudioSequence => ({
  id: `s${++t}`,
  phase: 'NIGHT',
  subPhase: null,
  audioFiles,
  priority: 5,
  timestamp: t,
})

describe('nightNarrationKey', () => {
  it('is "dark" before any line of the night', () => {
    expect(nightNarrationKey([])).toBe('dark')
    expect(nightNarrationKey([undefined, null])).toBe('dark')
  })

  it('follows each open and close line, newest wins', () => {
    const dark = seq('goes_dark_close_eyes.mp3', 'wolf_howl.mp3')
    const open = seq('wolf_open_eyes.mp3')
    const close = seq('wolf_close_eyes.mp3')
    expect(nightNarrationKey([dark])).toBe('dark')
    expect(nightNarrationKey([dark, open])).toBe('wolfOpen')
    expect(nightNarrationKey([dark, open, close])).toBe('wolfClose')
    expect(nightNarrationKey([dark, open, close, seq('medium_open_eyes.mp3')])).toBe('mediumOpen')
    expect(nightNarrationKey([dark, seq('gravekeeper_open_eyes.mp3')])).toBe('gravekeeperOpen')
    expect(nightNarrationKey([dark, seq('gravekeeper_close_eyes.mp3')])).toBe('gravekeeperClose')
  })

  it('orders by timestamp, not array order, and ignores duplicate ids', () => {
    const open = seq('seer_open_eyes.mp3')
    const close = seq('seer_close_eyes.mp3')
    expect(nightNarrationKey([close, open, open])).toBe('seerClose')
  })

  it('takes the last line inside one sequence (close then next open)', () => {
    expect(nightNarrationKey([seq('wolf_close_eyes.mp3', 'seer_open_eyes.mp3')])).toBe('seerOpen')
  })

  it('keeps the previous line through files that have none', () => {
    expect(nightNarrationKey([seq('witch_open_eyes.mp3'), seq('wolf_howl.mp3')])).toBe('witchOpen')
  })

  it("drops last night's lines once dawn has played", () => {
    const lastNight = seq('guard_close_eyes.mp3')
    const dawn = seq('rooster_crowing.mp3', 'day_time.mp3')
    expect(nightNarrationKey([lastNight, dawn, seq('countdown_warning.mp3')])).toBe('dark')
    expect(nightNarrationKey([lastNight, dawn, seq('goes_dark_close_eyes.mp3')])).toBe('dark')
  })
})
