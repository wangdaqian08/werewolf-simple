/**
 * Unit tests for the backend-log ERROR-line sentinel.
 *
 * Verifies the pure-logic parts: `findBackendErrorLines` (which lines
 * count as errors, what the allow-list does) and `assertNoBackendErrorsSince`
 * (the throwing/attachment behavior). Real-backend integration is left to
 * the Playwright run.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  assertNoBackendErrorsSince,
  findBackendErrorLines,
  readBackendLogLineCount,
  readBackendLogSince,
} from '../../e2e/real/helpers/backend-log'

const SAMPLE_OK_LINES = [
  '2026-04-26 10:00:00.001  INFO 12345 --- [    main] o.s.boot.SpringApplication: Started',
  '2026-04-26 10:00:00.123  INFO 12345 --- [scheduler] c.w.GameService: NIGHT/WEREWOLF_PICK ready',
  '2026-04-26 10:00:00.456  WARN 12345 --- [   ws-1] c.w.WsHandler: heartbeat slow (200ms)',
]

const SAMPLE_ERROR =
  '2026-04-26 10:00:00.789 ERROR 12345 --- [scheduler] c.w.GameService: nightmare'
const SAMPLE_FATAL = '2026-04-26 10:00:00.999 FATAL 12345 --- [    main] c.w.Boot: kernel panic'
const SAMPLE_STACK_FRAME = '\tat com.werewolf.GameService.handleAction(GameService.kt:123)'
const SAMPLE_CAUSED_BY = 'Caused by: java.lang.RuntimeException: nightmare'

type TestInfoMock = { attach: ReturnType<typeof vi.fn> }
const mkTestInfo = (): TestInfoMock => ({ attach: vi.fn().mockResolvedValue(undefined) })

let tmpDir: string
let logPath: string

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), 'backend-log-test-'))
  logPath = join(tmpDir, 'werewolf-e2e-backend.log')
})

afterEach(() => {
  try {
    rmSync(tmpDir, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
})

describe('findBackendErrorLines', () => {
  it('returns no matches for a clean info/warn-only log', () => {
    const matches = findBackendErrorLines(SAMPLE_OK_LINES)
    expect(matches).toEqual([])
  })

  it('matches an ERROR-level line with the Spring prefix', () => {
    const lines = [...SAMPLE_OK_LINES, SAMPLE_ERROR]
    const matches = findBackendErrorLines(lines)
    expect(matches).toHaveLength(1)
    expect(matches[0]).toBe(SAMPLE_ERROR)
  })

  it('matches a FATAL-level line', () => {
    const lines = [...SAMPLE_OK_LINES, SAMPLE_FATAL]
    expect(findBackendErrorLines(lines)).toHaveLength(1)
  })

  it('does NOT match stack-frame lines (would inflate the count)', () => {
    const lines = [SAMPLE_ERROR, SAMPLE_STACK_FRAME, SAMPLE_STACK_FRAME, SAMPLE_CAUSED_BY]
    const matches = findBackendErrorLines(lines)
    // Only the level-prefixed line counts as an event.
    expect(matches).toHaveLength(1)
    expect(matches[0]).toBe(SAMPLE_ERROR)
  })

  it('respects the allowPatterns filter', () => {
    const allowed =
      '2026-04-26 10:00:01.000 ERROR 12345 --- [scheduler] c.w.NoiseSource: known-flake xyz'
    const real = '2026-04-26 10:00:02.000 ERROR 12345 --- [scheduler] c.w.GameService: real bug'
    const matches = findBackendErrorLines([allowed, real], [/known-flake/])
    expect(matches).toHaveLength(1)
    expect(matches[0]).toBe(real)
  })
})

describe('readBackendLogLineCount + readBackendLogSince', () => {
  it('returns 0 when the file does not exist', () => {
    expect(readBackendLogLineCount(logPath)).toBe(0)
    expect(readBackendLogSince(0, logPath)).toEqual([])
  })

  it('reads the line count of an existing log', () => {
    writeFileSync(logPath, [...SAMPLE_OK_LINES, ''].join('\n'))
    // Note: trailing newline → split('\n') yields an extra empty string.
    expect(readBackendLogLineCount(logPath)).toBe(SAMPLE_OK_LINES.length + 1)
  })

  it('returns only lines added since startLine', () => {
    writeFileSync(logPath, SAMPLE_OK_LINES.join('\n'))
    const before = readBackendLogLineCount(logPath)
    writeFileSync(logPath, [...SAMPLE_OK_LINES, SAMPLE_ERROR, SAMPLE_STACK_FRAME].join('\n'))
    const tail = readBackendLogSince(before, logPath)
    expect(tail).toContain(SAMPLE_ERROR)
    // SAMPLE_OK_LINES already existed before, so they should NOT appear
    expect(tail).not.toContain(SAMPLE_OK_LINES[0])
  })
})

describe('assertNoBackendErrorsSince', () => {
  it('passes silently on a clean window', async () => {
    writeFileSync(logPath, SAMPLE_OK_LINES.join('\n'))
    const info = mkTestInfo()
    await expect(assertNoBackendErrorsSince(0, info as never, { logPath })).resolves.toBeUndefined()
    expect(info.attach).not.toHaveBeenCalled()
  })

  it('throws and attaches when an ERROR appears in the window', async () => {
    writeFileSync(logPath, [...SAMPLE_OK_LINES, SAMPLE_ERROR, SAMPLE_STACK_FRAME].join('\n'))
    const info = mkTestInfo()
    await expect(assertNoBackendErrorsSince(0, info as never, { logPath })).rejects.toThrow(
      /ERROR\/FATAL/,
    )
    expect(info.attach).toHaveBeenCalledTimes(1)
    const body = String(info.attach.mock.calls[0]?.[1]?.body)
    expect(body).toContain('nightmare')
    // Stack frame block is included as context
    expect(body).toContain('GameService.kt:123')
  })

  it('starts from the snapshot point — pre-existing errors do not fail the test', async () => {
    // First, the log already has an old error from a previous test.
    writeFileSync(logPath, [SAMPLE_ERROR, SAMPLE_STACK_FRAME].join('\n'))
    const startLine = readBackendLogLineCount(logPath)
    // Then the current test logs only OK lines.
    writeFileSync(logPath, [SAMPLE_ERROR, SAMPLE_STACK_FRAME, ...SAMPLE_OK_LINES].join('\n'))
    const info = mkTestInfo()
    await expect(
      assertNoBackendErrorsSince(startLine, info as never, { logPath }),
    ).resolves.toBeUndefined()
  })

  it('detects a NEW error after the snapshot even if older errors exist', async () => {
    writeFileSync(logPath, [SAMPLE_ERROR].join('\n'))
    const startLine = readBackendLogLineCount(logPath)
    writeFileSync(logPath, [SAMPLE_ERROR, SAMPLE_FATAL, SAMPLE_STACK_FRAME].join('\n'))
    const info = mkTestInfo()
    await expect(
      assertNoBackendErrorsSince(startLine, info as never, { logPath }),
    ).rejects.toThrow()
  })
})
