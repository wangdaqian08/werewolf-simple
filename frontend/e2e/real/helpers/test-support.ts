import type { Page } from '@playwright/test'

/**
 * Ask the e2e backend to purge every row the test room left behind —
 * DELETE /api/test-support/rooms/{roomCode}, served by the
 * @Profile("(e2e | test) & !prod") TestSupportController. Users and wallets survive
 * by design (guest identities are shared across rooms in a session).
 *
 * Warn-never-throw: cleanup must not fail a suite. CI backends are
 * per-shard create-drop H2 and die with the process anyway — this exists so
 * a locally-reused backend (reuseExistingServer: true) doesn't accumulate
 * rooms/games across runs.
 *
 * Call BEFORE ctx.cleanup() — the request rides the host page's JWT.
 */
export async function cleanupRoomData(hostPage: Page, roomCode: string): Promise<void> {
  try {
    const result = await hostPage.evaluate(async (code: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return { status: 0, body: null as unknown }
      const res = await fetch(`/api/test-support/rooms/${code}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
      return { status: res.status, body: res.ok ? await res.json() : null }
    }, roomCode)
    // eslint-disable-next-line no-console
    console.warn(
      `[test-support] cleanup room=${roomCode} status=${result.status} ` +
        `deleted=${JSON.stringify(result.body)}`,
    )
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(`[test-support] cleanup room=${roomCode} failed: ${(e as Error).message}`)
  }
}

/**
 * Top up [userId]'s wallet by [amount] credits through the e2e-only
 * POST /api/test-support/wallet/grant. Throws on failure: a spec that buys a
 * perk cannot proceed without the credits. Returns the new balance.
 */
export async function grantCredits(page: Page, userId: string, amount: number): Promise<number> {
  const result = await page.evaluate(
    async ([uid, amt]) => {
      const res = await fetch('/api/test-support/wallet/grant', {
        method: 'POST',
        // /api/** requires a JWT (SecurityConfig); ride the page's session like cleanupRoomData.
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('jwt')}`,
        },
        body: JSON.stringify({ userId: uid, amount: amt }),
      })
      return { status: res.status, body: res.ok ? await res.json() : null }
    },
    [userId, amount] as const,
  )
  if (result.status !== 200)
    throw new Error(`grantCredits ${userId} +${amount}: HTTP ${result.status}`)
  return (result.body as { balance: number }).balance
}

/**
 * Swap the dealt roles of two players through the e2e-only
 * POST /api/test-support/games/{gameId}/swap-roles (ROLE_REVEAL only), so a spec
 * can pin a role onto a given player. Throws on failure: the spec depends on it.
 */
export async function swapRoles(
  hostPage: Page,
  gameId: number,
  userIdA: string,
  userIdB: string,
): Promise<void> {
  const status = await hostPage.evaluate(
    async ([gid, a, b]) => {
      const res = await fetch(`/api/test-support/games/${gid}/swap-roles`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('jwt')}`,
        },
        body: JSON.stringify({ userIdA: a, userIdB: b }),
      })
      return res.status
    },
    [gameId, userIdA, userIdB] as const,
  )
  if (status !== 200)
    throw new Error(`swapRoles game=${gameId} ${userIdA}<->${userIdB}: HTTP ${status}`)
}

/**
 * Pin one seat's 单局任务 through the e2e-only
 * POST /api/test-support/games/{gameId}/set-task. The backend only accepts a task
 * that seat's role can draw, so a spec can never stage an impossible one.
 */
export async function setTask(
  hostPage: Page,
  gameId: number,
  userId: string,
  task: string,
): Promise<void> {
  const status = await hostPage.evaluate(
    async ([gid, uid, tk]) => {
      const res = await fetch(`/api/test-support/games/${gid}/set-task`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('jwt')}`,
        },
        body: JSON.stringify({ userId: uid, task: tk }),
      })
      return res.status
    },
    [gameId, userId, task] as const,
  )
  if (status !== 200) throw new Error(`setTask game=${gameId} ${userId}=${task}: HTTP ${status}`)
}
