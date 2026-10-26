/**
 * Real-backend E2E regression: a "ghost" member — joined the room but closed
 * the app before picking a seat — must not deadlock the room.
 *
 * Bug (prod rooms 15/47/48/62/68): the ghost was invisible (no seat → not in
 * the grid, and the join sent no ROOM_UPDATE), yet it counted towards the
 * capacity. The last real player got "Room is full" — shown as "Room not found"
 * — for a seat everyone could see was free, the host could not start, and
 * there was no × to kick the ghost. Hosts recreated the room.
 *
 * Fix: the join broadcasts ROOM_UPDATE, RoomView lists unseated members with a
 * host-only kick, and the lobby shows the backend's real join reason.
 *
 * Run locally with:
 *   npx playwright test --config=playwright.real.config.ts ghost-member
 */
import { expect, test } from '@playwright/test'
import { type Browser, type BrowserContext, type Page } from '@playwright/test'
import { joinBots } from './helpers/shell-runner'

const BASE = 'http://localhost:5174'
const API = 'http://localhost:8080/api'
const ci = !!process.env.CI
const NAV_TIMEOUT = ci ? 60_000 : 30_000
const UI_TIMEOUT = ci ? 30_000 : 15_000
const SEATS = 6

async function enterNickname(page: Page, nick: string) {
  await page.goto(`${BASE}/`)
  await page.evaluate(() => localStorage.clear())
  await page.goto(`${BASE}/`)
  // The lobby may show OAuth first with the guest form collapsed.
  const guestToggle = page.getByTestId('guest-toggle')
  if (await guestToggle.count()) await guestToggle.click()
  await page.getByTestId('nickname-input').fill(nick)
}

async function joinByCode(page: Page, code: string) {
  await page.getByTestId('room-code-input').fill(code)
  await page.getByTestId('join-room-btn').click()
}

/** Host creates a 6-seat room, sits at seat 1; 4 ready bots fill 4 more seats → one seat left. */
async function roomWithOneFreeSeat(
  browser: Browser,
  hostNick: string,
): Promise<{ hctx: BrowserContext; host: Page; roomId: string; code: string }> {
  const hctx = await browser.newContext()
  const host = await hctx.newPage()
  await enterNickname(host, hostNick)
  await host.getByTestId('create-room-btn').click()
  await host.waitForURL(/\/create-room/, { timeout: NAV_TIMEOUT })
  await host.getByTestId('create-room-submit').click()
  await host.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
  for (let i = 0; i < 10; i++) {
    if ((await host.getByTestId('player-count-value').textContent())?.trim() === `${SEATS}`) break
    await host.getByTestId('player-count-decrement').click()
  }
  await host.getByTestId('room-setup-done').click()
  const roomId = host.url().match(/\/room\/(\d+)/)![1]!
  const code = (await host.getByTestId('room-code').textContent())!.trim()
  await host.getByTestId('room-seat-1').click()
  await expect(host.getByTestId('room-seat-1')).toContainText(hostNick, { timeout: UI_TIMEOUT })

  joinBots(code, SEATS - 2, true)
  return { hctx, host, roomId, code }
}

async function freeSeat(roomId: string, page: Page): Promise<number> {
  const token = await page.evaluate(() => localStorage.getItem('jwt'))
  const room = await fetch(`${API}/room/${roomId}`, {
    headers: { Authorization: `Bearer ${token}` },
  }).then((r) => r.json())
  const taken = new Set(room.players.map((p: { seatIndex: number | null }) => p.seatIndex))
  const seat = Array.from({ length: SEATS }, (_, i) => i + 1).find((s) => !taken.has(s))
  expect(seat, 'room has a free seat').toBeDefined()
  return seat!
}

test.describe('Ghost member (joined, closed the app before sitting) — real-backend flow', () => {
  test.setTimeout(ci ? 240_000 : 120_000)

  test('host sees the ghost at once, kicks it, and the last player can join and ready up', async ({
    browser,
  }) => {
    const { hctx, host, roomId, code } = await roomWithOneFreeSeat(browser, 'GhostHost')

    // Ghost joins, never sits, closes the app.
    const gctx = await browser.newContext()
    const ghost = await gctx.newPage()
    await enterNickname(ghost, 'GhostKevin')
    await joinByCode(ghost, code)
    await ghost.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
    await expect(
      host.getByTestId('unseated-player'),
      'host sees the joiner before they pick a seat',
    ).toContainText('GhostKevin', { timeout: UI_TIMEOUT })
    await gctx.close()

    // The last real player is told the truth, not "Room not found".
    const lctx = await browser.newContext()
    const last = await lctx.newPage()
    await enterNickname(last, 'GhostLast')
    await joinByCode(last, code)
    await expect(last.getByTestId('lobby-error')).toHaveText('Room is full', {
      timeout: UI_TIMEOUT,
    })

    // Host kicks the ghost from the not-seated list.
    await host.getByTestId('kick-unseated-guest:ghostkevin').click()
    await expect(host.getByTestId('unseated-list')).toBeHidden({ timeout: UI_TIMEOUT })

    // Now the last player gets in, sits and readies; the host can start.
    await last.getByTestId('join-room-btn').click()
    await last.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
    const seat = await freeSeat(roomId, last)
    await last.getByTestId(`room-seat-${seat}`).click()
    await expect(host.getByTestId(`room-seat-${seat}`)).toContainText('GhostLast', {
      timeout: UI_TIMEOUT,
    })
    await last.getByTestId('ready-btn').click()
    await expect(host.getByTestId('start-game-btn')).toBeEnabled({ timeout: UI_TIMEOUT })

    await lctx.close()
    await hctx.close()
  })

  test('a ghost who reopens the app rejoins through the lobby and takes the free seat', async ({
    browser,
  }) => {
    const { hctx, host, roomId, code } = await roomWithOneFreeSeat(browser, 'GhostHost2')

    const gctx = await browser.newContext()
    const tab = await gctx.newPage()
    await enterNickname(tab, 'GhostBack')
    await joinByCode(tab, code)
    await tab.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
    await expect(host.getByTestId('unseated-player')).toContainText('GhostBack', {
      timeout: UI_TIMEOUT,
    })
    await tab.close() // closes the app; the session stays in this device's storage

    const reopened = await gctx.newPage()
    await reopened.goto(`${BASE}/`)
    await reopened.getByTestId('rejoin-room-btn').click()
    await reopened.waitForURL(new RegExp(`/room/${roomId}$`), { timeout: NAV_TIMEOUT })
    const seat = await freeSeat(roomId, reopened)
    await reopened.getByTestId(`room-seat-${seat}`).click()

    await expect(host.getByTestId(`room-seat-${seat}`)).toContainText('GhostBack', {
      timeout: UI_TIMEOUT,
    })
    await expect(host.getByTestId('unseated-list')).toBeHidden({ timeout: UI_TIMEOUT })

    await gctx.close()
    await hctx.close()
  })
})
