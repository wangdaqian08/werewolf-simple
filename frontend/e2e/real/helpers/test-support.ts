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
        headers: { 'Content-Type': 'application/json' },
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
