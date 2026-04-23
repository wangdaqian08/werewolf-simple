import { expect, test, type BrowserContext, type Page } from '@playwright/test'

// Each test clears localStorage to start fresh
test.beforeEach(async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
})

// ── Login ──────────────────────────────────────────────────────────────────────

test('login with valid nickname stores JWT in localStorage', async ({ page }) => {
  await page.getByPlaceholder('Enter your nickname').fill('Alice')
  await page.getByRole('button', { name: /Create Room/i }).click()
  await expect(page).toHaveURL(/\/create-room/)

  const jwt = await page.evaluate(() => localStorage.getItem('jwt'))
  expect(jwt).not.toBeNull()

  const userId = await page.evaluate(() => localStorage.getItem('userId'))
  expect(userId).toMatch(/^guest:/)

  const nickname = await page.evaluate(() => localStorage.getItem('nickname'))
  expect(nickname).toBe('Alice')
})

test('repeat login with same nickname returns the same userId (idempotent rejoin)', async ({ browser }) => {
  const ctx1 = await browser.newContext()
  const ctx2 = await browser.newContext()

  const id1 = await loginAndGetUserId(ctx1, 'Dave')
  const id2 = await loginAndGetUserId(ctx2, 'Dave')

  // Backend change: /api/user/login is idempotent per-nickname — same nick
  // maps to the same guest userId so a player who loses their JWT can rejoin
  // their ongoing game by re-entering their nickname. Matches the backend
  // integration test `POST user-login with same nickname returns same userId`.
  expect(id1).toBe(id2)
  expect(id1).toMatch(/^guest:/)
  expect(id2).toMatch(/^guest:/)

  await ctx1.close()
  await ctx2.close()
})

// ── Join room ─────────────────────────────────────────────────────────────────

test('joining a non-existent room shows error and stays on lobby', async ({ page }) => {
  await page.getByPlaceholder('Enter your nickname').fill('Alice')
  await page.getByPlaceholder('Room code').fill('BADCD')
  await page.getByRole('button', { name: /Join/i }).click()

  await expect(page.getByText('Room not found. Check the code.')).toBeVisible()
  await expect(page).toHaveURL('/')
})

// ── Create + join room ────────────────────────────────────────────────────────

test('host creates room, guest joins with the room code', async ({ browser }) => {
  const hostCtx = await browser.newContext()
  const guestCtx = await browser.newContext()

  // Host: login → create room → get the room code
  const hostPage = await hostCtx.newPage()
  await hostPage.goto('http://localhost:5174/')
  await hostPage.evaluate(() => localStorage.clear())
  await hostPage.goto('http://localhost:5174/')
  await hostPage.getByPlaceholder('Enter your nickname').fill('Host')
  await hostPage.getByRole('button', { name: /Create Room/i }).click()
  await expect(hostPage).toHaveURL(/\/create-room/)
  await hostPage.getByRole('button', { name: /Create/i }).click()
  await expect(hostPage).toHaveURL(/\/room\//)

  const roomCode = await hostPage.locator('[data-testid="room-code"]').textContent()
  expect(roomCode).toMatch(/^[A-Z0-9]{4,6}$/)

  // Guest: login → join with the room code → lands in room
  const guestPage = await guestCtx.newPage()
  await guestPage.goto('http://localhost:5174/')
  await guestPage.evaluate(() => localStorage.clear())
  await guestPage.goto('http://localhost:5174/')
  await guestPage.getByPlaceholder('Enter your nickname').fill('Guest')
  await guestPage.getByPlaceholder('Room code').fill(roomCode!)
  await guestPage.getByRole('button', { name: /Join/i }).click()
  await expect(guestPage).toHaveURL(/\/room\//)

  const guestJwt = await guestPage.evaluate(() => localStorage.getItem('jwt'))
  expect(guestJwt).not.toBeNull()

  await hostCtx.close()
  await guestCtx.close()
})

// ── Helper ────────────────────────────────────────────────────────────────────

async function loginAndGetUserId(ctx: BrowserContext, nickname: string): Promise<string> {
  const page: Page = await ctx.newPage()
  await page.goto('http://localhost:5174/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('http://localhost:5174/')
  await page.getByPlaceholder('Enter your nickname').fill(nickname)
  await page.getByRole('button', { name: /Create Room/i }).click()
  await expect(page).toHaveURL(/\/create-room/)
  const userId = await page.evaluate(() => localStorage.getItem('userId'))
  return userId!
}
