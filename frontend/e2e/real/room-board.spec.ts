/**
 * Real-backend E2E: the host grows a full room instead of recreating it.
 *
 * Before: the player count was fixed at creation, so when one more person
 * turned up the host had to abandon the room and everyone joined a new code.
 * Now: page 1 (game settings) creates the room, page 2 (players & roles, in
 * the room) sets the board and has a one-tap share link, and the host can
 * reopen page 2 from the room until the game starts.
 *
 * Run locally with:
 *   npx playwright test --config=playwright.real.config.ts room-board
 */
import { expect, test, type Page } from '@playwright/test'
import { joinBots } from './helpers/shell-runner'

const BASE = 'http://localhost:5174'
const ci = !!process.env.CI
const NAV_TIMEOUT = ci ? 60_000 : 30_000
const UI_TIMEOUT = ci ? 30_000 : 15_000
const SHOTS = process.env.ROOM_BOARD_SHOTS // optional: a folder for review screenshots

async function shot(page: Page, name: string) {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true })
}

async function enterNickname(page: Page, nick: string, path = '/') {
  await page.goto(`${BASE}/`)
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}${path}`)
  const guestToggle = page.getByTestId('guest-toggle')
  if (await guestToggle.count()) await guestToggle.click()
  await page.getByTestId('nickname-input').fill(nick)
}

async function setPlayerCount(page: Page, n: number) {
  for (let i = 0; i < 12; i++) {
    const v = Number((await page.getByTestId('player-count-value').textContent())?.trim())
    if (v === n) return
    await page.getByTestId(v < n ? 'player-count-increment' : 'player-count-decrement').click()
  }
  throw new Error(`player count never reached ${n}`)
}

test.describe('Room board — grow a full room, share the link', () => {
  test.setTimeout(ci ? 240_000 : 120_000)

  test('a turned-away player opens the shared link and gets in once the host adds a seat', async ({
    browser,
  }) => {
    const viewport = { width: 417, height: 900 }
    const hctx = await browser.newContext({
      viewport,
      permissions: ['clipboard-read', 'clipboard-write'],
    })
    // No share sheet in this browser → the button copies the link (the WeChat path).
    await hctx.addInitScript(() => Object.defineProperty(navigator, 'share', { value: undefined }))
    const host = await hctx.newPage()

    // Page 1: game settings → Create Room.
    await enterNickname(host, 'BoardHost')
    await host.getByTestId('create-room-btn').click()
    await host.waitForURL(/\/create-room/, { timeout: NAV_TIMEOUT })
    await shot(host, '1-game-settings')
    await host.getByTestId('create-room-submit').click()

    // Page 2: players & roles, with the code and a share button already.
    await expect(host.getByTestId('room-setup-panel')).toBeVisible({ timeout: NAV_TIMEOUT })
    const code = (await host.getByTestId('setup-room-code').textContent())!.trim()
    await setPlayerCount(host, 6)
    await shot(host, '2-players-and-roles')
    await host.getByTestId('share-room-btn').click()
    await expect(host.getByTestId('share-room-btn')).toContainText('已复制')
    const link = await host.evaluate(() => navigator.clipboard.readText())
    expect(link).toBe(`${BASE}/?room=${code}`)
    await host.getByTestId('room-setup-done').click()

    // Room fills up: host + 5 ready bots.
    await host.getByTestId('room-seat-1').click()
    await expect(host.getByTestId('room-seat-1')).toContainText('BoardHost', {
      timeout: UI_TIMEOUT,
    })
    joinBots(code, 5, true)
    await expect(host.getByTestId('start-game-btn')).toBeEnabled({ timeout: UI_TIMEOUT })
    await shot(host, '3-room-full')

    // The 7th person opens the shared link: the code is filled in, but the room is full.
    const lctx = await browser.newContext({ viewport })
    const late = await lctx.newPage()
    await enterNickname(late, 'BoardLate', link.slice(BASE.length)) // '/?room=123'
    await expect(late.getByTestId('room-code-input')).toHaveValue(code)
    await late.getByTestId('join-room-btn').click()
    await expect(late.getByTestId('lobby-error')).toHaveText('Room is full', {
      timeout: UI_TIMEOUT,
    })

    // Host adds a seat from the room — same code, same members.
    await host.getByTestId('edit-board-btn').click()
    await expect(host.getByTestId('setup-joined-count')).toContainText('6')
    await expect(
      host.getByTestId('player-count-decrement'),
      'cannot drop below the 6 who joined',
    ).toBeDisabled()
    await host.getByTestId('player-count-increment').click()
    await host.getByTestId('room-setup-done').click()
    await expect(host.getByTestId('room-seat-7')).toBeVisible({ timeout: UI_TIMEOUT })
    await expect(host.getByTestId('room-code')).toHaveText(code)
    // The board changed, so the guests must ready up again.
    await expect(host.getByTestId('start-game-btn')).toBeDisabled({ timeout: UI_TIMEOUT })

    // Now the late player gets in and takes the new seat.
    await late.getByTestId('join-room-btn').click()
    await late.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
    await late.getByTestId('room-seat-7').click()
    await expect(host.getByTestId('room-seat-7')).toContainText('BoardLate', {
      timeout: UI_TIMEOUT,
    })
    await shot(host, '4-room-grown')

    await lctx.close()
    await hctx.close()
  })
})
