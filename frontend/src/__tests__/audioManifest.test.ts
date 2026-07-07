/**
 * Unit tests for the pure audio-manifest helpers. Feeds synthetic backend-log
 * lines / STOMP console lines in the exact production formats
 * (NightOrchestrator.kt:666,674,804,874; GameView.vue stomp log) and pins the
 * counting, gameId scoping, order extraction, and violation reporting.
 */
import { describe, expect, it } from 'vitest'
import {
  buildGameAudioManifest,
  countAudioPlaybacks,
  countBackendBroadcasts,
  countRoleAliveLines,
  extractPerNightRoleAudio,
  findAudioManifestViolations,
  findNightOrderViolations,
  NIGHT_ROLE_ORDER,
} from '../../e2e/real/helpers/audio-manifest'

const PREFIX = '2026-07-06T10:00:00.000  INFO 1 --- [pool-1] c.w.game.night.NightOrchestrator :'

/** Backend log lines for one full night of game `gameId`. */
function syntheticNightLines(gameId: number, aliveByRole: Record<string, boolean> = {}): string[] {
  const lines = [
    `${PREFIX} [broadcastNightInit] game=${gameId} day=1 withWaiting=false initialSubPhase=WEREWOLF_PICK audioFiles=[goes_dark_close_eyes.mp3, wolf_howl.mp3] priority=10`,
    `${PREFIX} [nightRoleLoop] game=${gameId}: roles=[WEREWOLF, WITCH, SEER, GUARD]`,
  ]
  const roleOf = (file: string): string =>
    file.startsWith('wolf') ? 'WEREWOLF' : (file.split('_')[0] ?? '').toUpperCase()
  for (const file of NIGHT_ROLE_ORDER) {
    if (file.endsWith('open_eyes.mp3')) {
      const role = roleOf(file)
      const alive = aliveByRole[role] ?? true
      lines.push(`${PREFIX} [nightRoleLoop] game=${gameId}: role=${role} alive=${alive}`)
    }
    lines.push(`${PREFIX} [broadcastAudio] game=${gameId} file=${file} id=${gameId}-1-${file}`)
  }
  return lines
}

function syntheticStompFrames(nights: number, dayCues: number): string[] {
  const frames: string[] = []
  for (let n = 0; n < nights; n++) {
    frames.push('[stomp] received AudioSequence [goes_dark_close_eyes.mp3, wolf_howl.mp3]')
    for (const file of NIGHT_ROLE_ORDER) {
      frames.push(`[stomp] received AudioSequence [${file}]`)
    }
  }
  for (let d = 0; d < dayCues; d++) {
    frames.push('[stomp] received AudioSequence [rooster_crowing.mp3, day_time.mp3]')
  }
  return frames
}

describe('buildGameAudioManifest', () => {
  it('computes the 5-night 60-file spine manifest', () => {
    const m = buildGameAudioManifest({ nights: 5, dayCues: 5 })
    expect(m.totalFiles).toBe(60)
    expect(m.backendRolePerFile['guard_close_eyes.mp3']).toBe(5)
    expect(m.backendNightInitPerFile['wolf_howl.mp3']).toBe(5)
    expect(m.stompPerFile['rooster_crowing.mp3']).toBe(5)
    expect(m.stompPerFile['seer_open_eyes.mp3']).toBe(5)
    expect(Object.keys(m.stompPerFile)).toHaveLength(12)
  })
})

describe('findAudioManifestViolations', () => {
  const manifest = buildGameAudioManifest({ nights: 2, dayCues: 2 })
  const completeBackend = [
    // Decoy: a different game sharing the log must not pollute game 3's counts.
    ...syntheticNightLines(13),
    ...syntheticNightLines(3),
    ...syntheticNightLines(3),
  ]
  const completeStomp = syntheticStompFrames(2, 2)

  it('passes on a complete capture and scopes counts to the gameId', () => {
    expect(
      findAudioManifestViolations({
        backendLines: completeBackend,
        stompFrames: completeStomp,
        manifest,
        gameId: '3',
      }),
    ).toEqual([])
  })

  it('flags a missing role broadcast', () => {
    const short = completeBackend.filter(
      (l, i) =>
        !(
          l.includes('game=3') &&
          l.includes('file=guard_close_eyes.mp3') &&
          i === completeBackend.map((x) => x).lastIndexOf(l)
        ),
    )
    const violations = findAudioManifestViolations({
      backendLines: short,
      stompFrames: completeStomp,
      manifest,
      gameId: '3',
    })
    expect(violations.some((v) => v.includes('guard_close_eyes.mp3'))).toBe(true)
  })

  it('flags an EXCESS role broadcast (double-broadcast regression)', () => {
    const extra = [
      ...completeBackend,
      `${PREFIX} [broadcastAudio] game=3 file=guard_close_eyes.mp3 id=3-9-guard_close_eyes.mp3`,
    ]
    const violations = findAudioManifestViolations({
      backendLines: extra,
      stompFrames: completeStomp,
      manifest,
      gameId: '3',
    })
    expect(violations).toEqual(['[broadcastAudio] guard_close_eyes.mp3: expected exactly 2, got 3'])
  })

  it('flags a short STOMP count but tolerates replay over-delivery', () => {
    const missingRooster = completeStomp.filter(
      (f, i) =>
        !(f.includes('rooster') && i === completeStomp.findIndex((x) => x.includes('rooster'))),
    )
    const short = findAudioManifestViolations({
      backendLines: completeBackend,
      stompFrames: missingRooster,
      manifest,
      gameId: '3',
    })
    expect(short.some((v) => v.startsWith('STOMP rooster_crowing.mp3'))).toBe(true)

    const overDelivered = [...completeStomp, ...completeStomp]
    expect(
      findAudioManifestViolations({
        backendLines: completeBackend,
        stompFrames: overDelivered,
        manifest,
        gameId: '3',
      }),
    ).toEqual([])
  })
})

describe('extractPerNightRoleAudio / findNightOrderViolations', () => {
  it('splits nights and holds the Werewolf→Witch→Seer→Guard order', () => {
    const lines = [...syntheticNightLines(7), ...syntheticNightLines(13), ...syntheticNightLines(7)]
    const nights = extractPerNightRoleAudio(lines, '7')
    expect(nights).toHaveLength(2)
    expect(nights[0]).toEqual([...NIGHT_ROLE_ORDER])
    expect(findNightOrderViolations(lines, '7', 2)).toEqual([])
  })

  it('flags a witch/seer order swap and a wrong night count', () => {
    const swapped = syntheticNightLines(7).map((l) =>
      l.includes('file=witch_open_eyes.mp3')
        ? l.replace('witch_open_eyes.mp3', 'seer_open_eyes.mp3')
        : l.includes('file=seer_open_eyes.mp3')
          ? l.replace('seer_open_eyes.mp3', 'witch_open_eyes.mp3')
          : l,
    )
    const violations = findNightOrderViolations(swapped, '7', 1)
    expect(violations.some((v) => v.includes('night 1 role-audio order'))).toBe(true)

    expect(findNightOrderViolations(syntheticNightLines(7), '7', 2)).toEqual([
      'night blocks: expected 2 [nightRoleLoop] headers, got 1',
    ])
  })
})

describe('countRoleAliveLines', () => {
  it('counts alive/dead night entries per role, scoped to the game', () => {
    const lines = [
      ...syntheticNightLines(5),
      ...syntheticNightLines(5, { SEER: false }),
      ...syntheticNightLines(5, { SEER: false, WITCH: false }),
      ...syntheticNightLines(15), // decoy game — game=5 regex must not match game=15
    ]
    const counts = countRoleAliveLines(lines, '5')
    expect(counts.SEER).toEqual({ alive: 1, dead: 2 })
    expect(counts.WITCH).toEqual({ alive: 2, dead: 1 })
    expect(counts.WEREWOLF).toEqual({ alive: 3, dead: 0 })
    expect(counts.GUARD).toEqual({ alive: 3, dead: 0 })
  })
})

describe('count helpers', () => {
  it('countBackendBroadcasts scopes by gameId boundary (game=1 vs game=13)', () => {
    const lines = [
      `${PREFIX} [broadcastAudio] game=1 file=wolf_open_eyes.mp3 id=a`,
      `${PREFIX} [broadcastAudio] game=13 file=wolf_open_eyes.mp3 id=b`,
    ]
    expect(countBackendBroadcasts(lines, '1')).toEqual({ 'wolf_open_eyes.mp3': 1 })
    expect(countBackendBroadcasts(lines, '13')).toEqual({ 'wolf_open_eyes.mp3': 1 })
    expect(countBackendBroadcasts(lines, '3')).toEqual({})
  })

  it('countAudioPlaybacks parses Starting playback lines', () => {
    const events = [
      '[AudioService] Starting playback: wolf_open_eyes.mp3',
      '[AudioService] Starting playback: wolf_open_eyes.mp3',
      '[AudioService] Finished playback: wolf_open_eyes.mp3',
      '[useAudioService] Skipping duplicate AudioSequence 3-1-x',
    ]
    expect(countAudioPlaybacks(events)).toEqual({ 'wolf_open_eyes.mp3': 2 })
  })
})
