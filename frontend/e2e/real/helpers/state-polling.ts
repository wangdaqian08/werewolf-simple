/**
 * Backend-state polling helpers for real E2E tests.
 *
 * Why this exists: `scripts/act.sh` exits 0 even when the backend rejects the
 * bot action (e.g. "Not in SEER_PICK sub-phase" when a SEER_CHECK is fired
 * while the Kotlin role-loop coroutine is still in WEREWOLF_PICK). Under slow
 * CI those rejections are silent and the game stalls on the sub-phase that was
 * "already handled" from the bot's POV. Memory ref: `e2e-ci-vs-local-env-
 * differences` item 1, and the live reproduction session where a 1s delay
 * between WOLF_KILL and SEER_CHECK deterministically produced a stuck game.
 *
 * Every spec that drives bot night-actions must gate each action on the
 * backend's current sub-phase using these helpers before firing. Local hosts
 * are typically fast enough to skate past this, but CI GH ubuntu-latest runs
 * ~2× slower and reliably trips the race.
 */
import type { Page } from '@playwright/test'

type GameStateMinimal = {
  phase?: string
  nightPhase?: { subPhase?: string } | null
  votingPhase?: { subPhase?: string } | null
  sheriffElection?: { subPhase?: string } | null
}

async function fetchGameState(hostPage: Page, gameId: string): Promise<GameStateMinimal | null> {
  return hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    return res.json()
  }, gameId)
}

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

/**
 * Poll `state.nightPhase.subPhase` until it matches `target`. Use before every
 * bot act() that expects a specific NIGHT sub-phase (WEREWOLF_PICK, SEER_PICK,
 * SEER_RESULT, WITCH_ACT, GUARD_PICK).
 *
 * Returns true when the target sub-phase is reached; false on timeout OR if the
 * game left NIGHT before hitting the target (caller can decide whether that's
 * fatal for their spec).
 *
 * `timeoutMs` is scaled 2× automatically on CI.
 */
export async function waitForNightSubPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  const deadline = Date.now() + scale(timeoutMs)
  while (Date.now() < deadline) {
    const state = await fetchGameState(hostPage, gameId)
    const sp = state?.nightPhase?.subPhase
    const phase = state?.phase
    if (sp === target) return true
    if (phase && phase !== 'NIGHT' && target !== phase) return false
    await hostPage.waitForTimeout(300)
  }
  return false
}

/**
 * Poll `state.phase` until it matches `target`. Use for top-level phase
 * transitions (NIGHT → DAY_DISCUSSION → DAY_VOTING → NIGHT, etc.).
 */
export async function waitForPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  const deadline = Date.now() + scale(timeoutMs)
  while (Date.now() < deadline) {
    const state = await fetchGameState(hostPage, gameId)
    if (state?.phase === target) return true
    await hostPage.waitForTimeout(300)
  }
  return false
}

/**
 * Poll `state.votingPhase.subPhase` until it matches `target` (e.g. VOTING,
 * VOTE_RESULT). Same semantics as waitForNightSubPhase but for the voting leg.
 */
export async function waitForVotingSubPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  const deadline = Date.now() + scale(timeoutMs)
  while (Date.now() < deadline) {
    const state = await fetchGameState(hostPage, gameId)
    const sp = state?.votingPhase?.subPhase
    if (sp === target) return true
    await hostPage.waitForTimeout(300)
  }
  return false
}
