/**
 * Real-backend E2E: 点将卡 (role-pick card).
 *
 * The host buys the WOLF card (20 credits, frozen until game end), five bots
 * choose NONE and ready up (join-room.sh), the host starts, and the dealer
 * honours the card: the host is a wolf and the role-reveal card says the
 * card took effect. A bot's own game state carries no role-pick data
 * (picks are private). Settlement at game end is covered by backend tests.
 *
 * Run locally with:
 *   npx playwright test --config=playwright.real.config.ts role-pick-card
 */
import { rmSync } from 'fs'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { joinBots, readStateFile } from './helpers/shell-runner'
import { fetchGameState, waitForPhase } from './helpers/state-polling'
import { cleanupRoomData, grantCredits } from './helpers/test-support'

const BASE = 'http://localhost:5174'
const API = 'http://localhost:8080/api'
const ci = !!process.env.CI
const NAV_TIMEOUT = ci ? 60_000 : 30_000
const UI_TIMEOUT = ci ? 30_000 : 15_000
const SEATS = 6
const WOLF_ROLES = ['WEREWOLF', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF']

test.describe('Role-pick card', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(ci ? 240_000 : 120_000)

  let hctx: BrowserContext
  let host: Page
  let code = ''
  let gameId = ''

  test.afterAll(async () => {
    if (code) await cleanupRoomData(host, code)
    await hctx?.close()
  })

  test('host buys WOLF, the panel shows the frozen credits and gates nothing else', async ({
    browser,
  }) => {
    hctx = await browser.newContext()
    host = await hctx.newPage()
    await host.goto(`${BASE}/`)
    await host.evaluate(() => localStorage.clear())
    await host.goto(`${BASE}/`)
    const guestToggle = host.getByTestId('guest-toggle')
    if (await guestToggle.count()) await guestToggle.click()
    await host.getByTestId('nickname-input').fill('PickHost')
    await host.getByTestId('create-room-btn').click()
    await host.waitForURL(/\/create-room/, { timeout: NAV_TIMEOUT })

    // Logged in now; top up before the room view loads the balance.
    const userId = await host.evaluate(() => localStorage.getItem('userId'))
    expect(userId, 'host is logged in').toBeTruthy()
    await grantCredits(host, userId!, 100)

    await host.getByTestId('create-room-submit').click()
    await host.waitForURL(/\/room\//, { timeout: NAV_TIMEOUT })
    await host.getByTestId('room-setup-panel').waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    for (let i = 0; i < 12; i++) {
      if ((await host.getByTestId('player-count-value').textContent())?.trim() === `${SEATS}`) break
      await host.getByTestId('player-count-decrement').click()
    }
    await expect(host.getByTestId('player-count-value')).toHaveText(`${SEATS}`)
    await host.getByTestId('room-setup-done').click()
    await host.getByTestId('room-code').waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    code = (await host.getByTestId('room-code').textContent())!.trim()
    await host.getByTestId('room-seat-1').click()
    await expect(host.getByTestId('room-seat-1')).toContainText('PickHost', { timeout: UI_TIMEOUT })

    await host.getByTestId('role-pick-WOLF').click()
    await expect(host.getByTestId('role-pick-frozen')).toContainText('20', { timeout: UI_TIMEOUT })
    await expect(host.getByTestId('role-pick-draw-note')).toBeVisible()
  })

  test('bots join and ready with NONE, host starts', async () => {
    // Room codes recycle and join-room.sh merges into an existing state file (see setupGame).
    rmSync(`/tmp/werewolf-${code.toUpperCase()}.json`, { force: true })
    joinBots(code, SEATS - 1, true)
    const startBtn = host.getByTestId('start-game-btn')
    await expect(startBtn).toBeEnabled({ timeout: UI_TIMEOUT })
    await startBtn.click()
    await host.waitForURL(/\/game\/\d+/, { timeout: NAV_TIMEOUT })
    gameId = host.url().match(/\/game\/(\d+)/)![1]!
  })

  test('host is dealt a wolf and sees the took-effect line', async () => {
    expect(await waitForPhase(host, gameId, 'ROLE_REVEAL'), 'game is in ROLE_REVEAL').toBe(true)

    const state = await fetchGameState(host, gameId)
    expect(WOLF_ROLES, `host role ${state?.myRole}`).toContain(state?.myRole)
    expect(state?.myRolePick).toEqual({ choice: 'WOLF', tookEffect: true, price: 20 })

    await host.getByTestId('reveal-role-btn').click()
    await expect(host.getByTestId('role-pick-result')).toBeVisible({ timeout: UI_TIMEOUT })

    // Privacy: a bot's own state carries its role but no role-pick data.
    const bot = readStateFile(code).bots[0]!
    const res = await fetch(`${API}/game/${gameId}/state`, {
      headers: { Authorization: `Bearer ${bot.token}` },
    })
    expect(res.ok, 'bot state request succeeds').toBe(true)
    const botState = await res.json()
    expect(botState.myRole, 'bot sees its own role').toBeTruthy()
    expect(botState.myRolePick ?? null).toBeNull()
  })
})
