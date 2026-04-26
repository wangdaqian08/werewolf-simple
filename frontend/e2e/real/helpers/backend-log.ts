/**
 * Backend log attachment + ERROR-line sentinel.
 *
 * Backend stdout is captured to a file via `tee` in the webServer command in
 * playwright.real.config.ts. Two consumers in this file:
 *
 *  1. attachBackendLogOnFailure — on failure, attaches the last 500 lines
 *     of the log to the Playwright report. The missing link between a
 *     frontend assertion ("stuck on NIGHT/WEREWOLF_PICK") and the backend
 *     reality (`action.submit ... -> SUCCESS`, `PhaseChanged`, `Exception`).
 *
 *  2. assertNoBackendErrorsSince — fails the test in afterEach if any
 *     ERROR / FATAL line appeared in the log during the test window, even
 *     when the gameplay-level assertions otherwise passed. Backend bugs
 *     that are recovered by retry/coroutine restart are still bugs.
 *
 * Default tail size is 500 lines, which on this codebase covers ~30 s of
 * backend activity — enough context for any single sub-phase stall without
 * blowing up the report artifact size.
 */
import { existsSync, readFileSync, statSync } from 'node:fs'
import type { TestInfo } from '@playwright/test'

const DEFAULT_LOG_PATH = '/tmp/werewolf-e2e-backend.log'
const DEFAULT_TAIL_LINES = 500

export async function attachBackendLogOnFailure(
  testInfo: TestInfo,
  options?: { tailLines?: number; logPath?: string },
): Promise<void> {
  if (testInfo.status !== 'failed') return
  const path = options?.logPath ?? DEFAULT_LOG_PATH
  const tailLines = options?.tailLines ?? DEFAULT_TAIL_LINES
  if (!existsSync(path)) return
  try {
    // Best-effort: very large logs (multi-MB) can blow up if read in one shot.
    // We accept that risk on E2E machines — files larger than 50 MB are a
    // separate problem that this attachment can't solve.
    const size = statSync(path).size
    const content = readFileSync(path, 'utf-8')
    const all = content.split('\n')
    const tail = all.slice(-tailLines).join('\n')
    await testInfo.attach('backend.log', {
      body:
        `# tail of ${path} (last ${Math.min(tailLines, all.length)} of ${all.length} lines, total ${size} bytes)\n` +
        `# captured at ${new Date().toISOString()}\n\n` +
        tail,
      contentType: 'text/plain',
    })
  } catch {
    // best-effort — don't crash the afterEach
  }
}

// ─── Sentinel #6 — fail on ERROR / FATAL lines during the test window ────────

/**
 * Patterns that count as "the backend logged an error event". Each pattern
 * matches the timestamp+level prefix Spring Boot uses, not the stack-frame
 * lines that follow — we want one match per error event, not one per frame.
 *
 * Examples that match:
 *   2026-04-26 10:00:00.456 ERROR 12345 --- [    main] o.s.b.SpringApplication ...
 *   2026-04-26 10:00:00.789 FATAL 12345 --- [scheduler] o.s.s.s.TaskScheduler ...
 *
 * Examples that intentionally do NOT match:
 *   "  at com.werewolf.GameService.handleAction(GameService.kt:123)"
 *   "Caused by: java.lang.RuntimeException"
 *   "java.lang.RuntimeException: ..."
 *
 * Cause-by / stack-frame lines are useful context — they appear in the
 * report attachment — but we don't double-count them as separate errors.
 */
const ERROR_LINE_PATTERNS: RegExp[] = [
  / ERROR \d+ --- /, // Spring Boot 3 default pattern
  / FATAL \d+ --- /,
]

/**
 * Read the current line count of the backend log, or 0 if the file does
 * not exist (e.g. running unit tests, or the log not yet flushed). Use
 * this to mark the position at the start of a test.
 */
export function readBackendLogLineCount(logPath = DEFAULT_LOG_PATH): number {
  if (!existsSync(logPath)) return 0
  try {
    return readFileSync(logPath, 'utf-8').split('\n').length
  } catch {
    return 0
  }
}

/** Read the lines of the backend log written since `startLine`. */
export function readBackendLogSince(
  startLine: number,
  logPath = DEFAULT_LOG_PATH,
): string[] {
  if (!existsSync(logPath)) return []
  try {
    const all = readFileSync(logPath, 'utf-8').split('\n')
    return all.slice(Math.max(0, startLine))
  } catch {
    return []
  }
}

/**
 * Filter `lines` to those that match an ERROR_LINE_PATTERN and are not
 * matched by any allow-list pattern. Exposed for unit testing and for
 * specs that want to inspect the same set the assertion would fail on.
 */
export function findBackendErrorLines(
  lines: string[],
  allowPatterns: RegExp[] = [],
): string[] {
  return lines.filter((line) => {
    if (!ERROR_LINE_PATTERNS.some((p) => p.test(line))) return false
    if (allowPatterns.some((p) => p.test(line))) return false
    return true
  })
}

/**
 * Fail the current test if any ERROR/FATAL line appeared in the backend
 * log since `startLine`. Attaches a `backend-errors` report artifact
 * with each matched line plus the immediately following stack-trace
 * fragments (up to 8 lines) so debugging from CI doesn't require local
 * reproduction.
 *
 * Pass `allowPatterns` for known intentional 5xx paths in negative-case
 * tests; default is strict (every ERROR fails).
 */
export async function assertNoBackendErrorsSince(
  startLine: number,
  testInfo: TestInfo,
  options?: { allowPatterns?: RegExp[]; logPath?: string },
): Promise<void> {
  const path = options?.logPath ?? DEFAULT_LOG_PATH
  const allowPatterns = options?.allowPatterns ?? []
  const lines = readBackendLogSince(startLine, path)
  const errorLines = findBackendErrorLines(lines, allowPatterns)
  if (errorLines.length === 0) return

  // Build a richer attachment: each error line + up to 8 following lines
  // (the cause-by / stack-frame block). Use the line index in `lines` so
  // we don't re-grep the whole file.
  const blocks: string[] = []
  for (let i = 0; i < lines.length; i++) {
    if (
      ERROR_LINE_PATTERNS.some((p) => p.test(lines[i])) &&
      !allowPatterns.some((p) => p.test(lines[i]))
    ) {
      const trace = lines.slice(i, Math.min(i + 9, lines.length)).join('\n')
      blocks.push(trace)
    }
  }

  await testInfo.attach('backend-errors', {
    body:
      `Backend log recorded ${errorLines.length} ERROR/FATAL line(s) during this test.\n` +
      `(Stack frame and Caused-by lines that follow each error are included for context.)\n\n` +
      blocks.map((b, i) => `── [${i + 1}] ──\n${b}`).join('\n\n'),
    contentType: 'text/plain',
  })

  throw new Error(
    `Backend log: ${errorLines.length} ERROR/FATAL line(s) during test — see backend-errors attachment. ` +
      `First: ${errorLines[0].slice(0, 200)}`,
  )
}
