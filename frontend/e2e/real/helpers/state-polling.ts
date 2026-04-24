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

/**
 * Return the userIds of all currently-alive players in the game.
 *
 * Use this to pre-filter role bots BEFORE firing `act()`: a dead bot returns
 * `rejected: Actor is dead` and the for-loop wastes time iterating through
 * known-dead entries. `roleMap` is populated at game start and never updates
 * when players die, so without this filter every night re-tries the full
 * original role-bot list.
 */
export async function readAlivePlayerIds(
  hostPage: Page,
  gameId: string,
): Promise<Set<string>> {
  const ids = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return [] as string[]
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return [] as string[]
    const state = await res.json()
    return ((state?.players ?? []) as Array<{ isAlive: boolean; userId: string }>)
      .filter((p) => p.isAlive)
      .map((p) => p.userId)
  }, gameId)
  return new Set(ids)
}

/**
 * Return the userIds of alive players who have NOT yet voted in the current
 * voting round. Combines `state.players[*].isAlive` with
 * `state.votingPhase.votedPlayerIds` (the backend's per-round voter set).
 *
 * Use this to pre-filter bots BEFORE firing `act('SUBMIT_VOTE', ...)`: without
 * this filter, the retry loop inside `act()` sees the fan-out call's mixed
 * rejection output ("Already voted this round" / "Dead players cannot vote")
 * and retries all players up to 3 times — wasting ~6s per redundant call.
 *
 * Returns an empty Set when no voting round is active (phase ≠ DAY_VOTING
 * or state fetch failed) — callers should interpret that as "no candidate
 * voters" and skip the fan-out entirely.
 */
export async function readUnvotedAlivePlayerIds(
  hostPage: Page,
  gameId: string,
): Promise<Set<string>> {
  const ids = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return [] as string[]
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return [] as string[]
    const state = await res.json()
    // Only return candidates when a voting round is actually open. Outside
    // DAY_VOTING the backend rejects SUBMIT_VOTE with "Not in voting phase";
    // returning alive players here would feed the fan-out and burn retries.
    if (state?.phase !== 'DAY_VOTING') return [] as string[]
    const subPhase = state?.votingPhase?.subPhase
    if (subPhase !== 'VOTING' && subPhase !== 'RE_VOTING') return [] as string[]
    const voted = new Set<string>(state?.votingPhase?.votedPlayerIds ?? [])
    return ((state?.players ?? []) as Array<{ isAlive: boolean; userId: string }>)
      .filter((p) => p.isAlive && !voted.has(p.userId))
      .map((p) => p.userId)
  }, gameId)
  return new Set(ids)
}

/**
 * Return the host's userId — read from localStorage on the host page. Use
 * this together with `readAlivePlayerIds` / `readUnvotedAlivePlayerIds` to
 * exclude the host from bulk bot-action fan-outs, since the host's JWT is
 * stored in the browser (not the shell state file) and host-only actions
 * (START_NIGHT, DAY_ADVANCE, VOTING_REVEAL_TALLY, VOTING_CONTINUE) must be
 * driven either through the UI or via a dedicated host-token `act.sh` call
 * rather than through the default "all bots" fan-out.
 */
export async function readHostUserId(hostPage: Page): Promise<string | null> {
  return hostPage.evaluate(() => localStorage.getItem('userId'))
}
