/**
 * Real-backend E2E regression: the host must not miss a player's ready/undo
 * that lands while the host's STOMP socket is briefly down.
 *
 * Bug: a ROOM_UPDATE broadcast sent while the host is disconnected is dropped by
 * the SimpleBroker, and RoomView's reconnect handler only redirected on
 * GAME_STARTED — it never re-fetched the WAITING-room player list. So the host's
 * ready count / Start button went stale (reported: "bot3 ready/undo, host
 * doesn't notice, Start Game not updated"). Fix: RoomView.checkActiveGame now
 * re-syncs the WAITING-room players on reconnect.
 *
 * Run locally with:
 *   npx playwright test --config=playwright.real.config.ts room-ready-sync
 */
import { expect, test } from '@playwright/test'
import { type Browser, type BrowserContext, type Page } from '@playwright/test'
import { joinBots, readStateFile } from './helpers/shell-runner'

const BASE = 'http://localhost:5174'
const API = 'http://localhost:8080/api'
const ci = !!process.env.CI

async function createWaitingRoom(browser: Browser): Promise<{
  hctx: BrowserContext
  host: Page
  roomId: string
  roomCode: string
}> {
  const hctx = await browser.newContext()
  const host = await hctx.newPage()
  await host.goto(`${BASE}/`)
  await host.evaluate(() => localStorage.clear())
  await host.goto(`${BASE}/`)
  // The real-backend lobby may show OAuth with a collapsed guest section —
  // reveal it before filling the nickname.
  const contAsGuest = host.getByRole('button', { name: /Continue as guest|继续以访客/i })
  if (await contAsGuest.count()) await contAsGuest.first().click().catch(() => {})
  await host.getByPlaceholder('Enter your nickname').fill('Host')
  await host.getByRole('button', { name: /Create Room/i }).first().click()
  await host.waitForURL(/\/create-room/, { timeout: ci ? 60_000 : 30_000 })

  // Use the minimum (6) players: decrement from the default until the value is 6.
  for (let i = 0; i < 10; i++) {
    if ((await host.getByTestId('player-count-value').textContent())?.trim() === '6') break
    await host.getByTestId('player-count-decrement').click()
  }
  // Turn sheriff off to keep setup minimal.
  const sheriffRow = host.locator('.role-row').filter({ hasText: /Sheriff|警长竞选/ })
  const sToggle = sheriffRow.locator('.toggle-on')
  if ((await sToggle.count()) > 0) await sToggle.click()

  await host.getByRole('button', { name: /Create Room/i }).click()
  await host.waitForURL(/\/room\//, { timeout: ci ? 30_000 : 15_000 })
  const roomId = host.url().match(/\/room\/(\d+)/)![1]
  const roomCode = (await host.locator('[data-testid="room-code"]').textContent())!.trim()

  // Host claims a seat (auto-readies the host).
  await host.locator('.slot-selectable').first().click()
  await host.waitForTimeout(800)
  return { hctx, host, roomId, roomCode }
}

async function setReady(token: string, roomId: string, ready: boolean) {
  await fetch(`${API}/room/ready`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ ready, roomId: Number(roomId) }),
  })
}

test.describe('Room ready-sync across a STOMP disconnect — real-backend flow', () => {
  test.setTimeout(ci ? 240_000 : 120_000)

  test('host recovers a bot ready/undo that landed while its socket was down', async ({
    browser,
  }) => {
    const { hctx, host, roomId, roomCode } = await createWaitingRoom(browser)

    // 5 bots join + ready → room is host + 5 ready bots = 6/6.
    joinBots(roomCode, 5, true)
    const bot = readStateFile(roomCode).bots[4]

    const startBtn = host.getByRole('button', { name: /Start Game|开始游戏/i })
    await expect(startBtn, 'host sees all-ready before disconnect').toBeEnabled({
      timeout: ci ? 30_000 : 15_000,
    })

    // ── CONTROL: host ONLINE — a bot undo-ready reaches the host live. ──
    await setReady(bot.token, roomId, false)
    await expect(startBtn, 'online: undo-ready → Start disabled').toBeDisabled({ timeout: 10_000 })
    await setReady(bot.token, roomId, true)
    await expect(startBtn, 'online: re-ready → Start enabled').toBeEnabled({ timeout: 10_000 })

    // ── DISCONNECT WINDOW: drop the host socket, undo-ready while it's down. ──
    await hctx.setOffline(true)
    await host.waitForTimeout(1_000)
    await setReady(bot.token, roomId, false)
    const snapshot = await fetch(`${API}/room/${roomId}`, {
      headers: { Authorization: `Bearer ${bot.token}` },
    }).then((r) => r.json())
    const botStatus = snapshot.players.find((p: { userId: string }) => p.userId === bot.userId)
      ?.status
    expect(botStatus, 'backend recorded the bot NOT_READY during the offline window').toBe(
      'NOT_READY',
    )

    // Host comes back online → STOMP auto-reconnects (reconnectDelay 3s) and
    // RoomView re-syncs the WAITING-room players, recovering the missed undo.
    await hctx.setOffline(false)
    await host.waitForTimeout(1_500)

    await expect(
      startBtn,
      'after reconnect the host reflects the missed undo-ready (Start disabled)',
    ).toBeDisabled({ timeout: ci ? 30_000 : 15_000 })

    await hctx.close()
  })
})
