import type { AudioSequence } from '@/types'

// The line each night narration file speaks, as a `night.narration.*` i18n key.
// Shown on the host's screen so the host can read the night aloud when the phone has no sound.
const NARRATION: Record<string, string> = {
  'goes_dark_close_eyes.mp3': 'dark',
  'wolf_open_eyes.mp3': 'wolfOpen',
  'wolf_close_eyes.mp3': 'wolfClose',
  'seer_open_eyes.mp3': 'seerOpen',
  'seer_close_eyes.mp3': 'seerClose',
  'witch_open_eyes.mp3': 'witchOpen',
  'witch_close_eyes.mp3': 'witchClose',
  'guard_open_eyes.mp3': 'guardOpen',
  'guard_close_eyes.mp3': 'guardClose',
  'mech_wolf_open_eyes.mp3': 'mechWolfOpen',
  'mech_wolf_close_eyes.mp3': 'mechWolfClose',
  'medium_open_eyes.mp3': 'mediumOpen',
  'medium_close_eyes.mp3': 'mediumClose',
  'gravekeeper_open_eyes.mp3': 'gravekeeperOpen',
  'gravekeeper_close_eyes.mp3': 'gravekeeperClose',
}

// Dawn cues: anything older belongs to the previous night.
const DAWN = new Set(['rooster_crowing.mp3', 'day_time.mp3'])

/**
 * The narration key of the newest line heard tonight. Files without a line
 * (wolf_howl, countdown_warning) keep the previous one. Before the first line
 * of the night it is "dark" (天黑請閉眼).
 */
export function nightNarrationKey(sequences: (AudioSequence | null | undefined)[]): string {
  const byId = new Map<string, AudioSequence>()
  for (const s of sequences) if (s) byId.set(s.id, s)
  const newestFirst = [...byId.values()].sort((a, b) => b.timestamp - a.timestamp)
  for (const seq of newestFirst) {
    for (const file of [...seq.audioFiles].reverse()) {
      if (NARRATION[file]) return NARRATION[file]
      if (DAWN.has(file)) return 'dark'
    }
  }
  return 'dark'
}
