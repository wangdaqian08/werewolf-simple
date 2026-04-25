/**
 * Backend log attachment helper.
 *
 * Backend stdout is captured to a file via `tee` in the webServer command in
 * playwright.real.config.ts. On test failure we attach the tail of that file
 * to the Playwright report — this is the missing link between a frontend
 * assertion ("stuck on NIGHT/WEREWOLF_PICK") and the backend reality
 * (`action.submit ... -> SUCCESS`, `PhaseChanged`, `game.state ... waitingOn=
 * [...]`, `Exception`).
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
