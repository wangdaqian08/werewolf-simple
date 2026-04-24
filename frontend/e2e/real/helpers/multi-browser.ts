/**
 * Multi-browser game fixture.
 *
 * Creates a real game via the browser UI + shell scripts, discovers role
 * assignments, and opens an isolated browser context per unique role.
 *
 * Usage in tests:
 *   const ctx = await setupGame(browser, { totalPlayers: 9, hasSheriff: false })
 *   // ctx.pages.get('WEREWOLF') — wolf's browser page
 *   // ctx.pages.get('SEER')     — seer's browser page
 *   // ctx.hostPage              — host's browser page
 */
import {readFileSync, writeFileSync} from 'fs'
import path from 'path'
import {type Browser, type BrowserContext, expect, type Page} from '@playwright/test'
import {
    act,
    type BotInfo,
    getConsoleLogin,
    getRoles,
    joinBots,
    readStateFile,
    type RoleMap,
    type RoleName,
} from './shell-runner'

const BASE_URL = 'http://localhost:5174'

// ── Types ────────────────────────────────────────────────────────────────────

export interface GameContext {
  roomCode: string
  gameId: string
  hostPage: Page
  hostContext: BrowserContext
  /** One page per role (WEREWOLF, SEER, WITCH, GUARD, VILLAGER, etc.) */
  pages: Map<string, Page>
  /** Corresponding BotInfo per role */
  bots: Map<string, BotInfo>
  /** All bots from the state file */
  allBots: BotInfo[]
  /** Role map from roles.sh (includes host) */
  roleMap: RoleMap
  /** The role assigned to the host player */
  hostRole: RoleName | null
  /** Roles where the host IS the player (use browser, not scripts) */
  isHostRole: (role: RoleName) => boolean
  /** Clean up all browser contexts */
  cleanup: () => Promise<void>
}

export interface GameSetupOptions {
  totalPlayers?: number
  hasSheriff?: boolean
  /** Custom roles to include in the game. If not provided, uses default configuration. */
  roles?: RoleName[]
  /** Which roles to open browsers for. Defaults to all special roles + 1 villager. */
  browserRoles?: RoleName[]
}

// ── Setup ────────────────────────────────────────────────────────────────────

/**
 * Set up a complete game with multi-browser contexts.
 *
 * 1. Host logs in + creates room via browser UI
 * 2. Bots join + ready via shell scripts
 * 3. Host clicks "Start Game"
 * 4. All bots confirm roles via shell scripts
 * 5. Wait for STMP synchronization
 * 6. Discovers roles via roles.sh
 * 7. Opens a browser context per desired role
 */
export async function setupGame(
  browser: Browser,
  opts: GameSetupOptions = {},
): Promise<GameContext> {
  const totalPlayers = opts.totalPlayers ?? 9
  const hasSheriff = opts.hasSheriff ?? false
  const contexts: BrowserContext[] = []

  // ── Step 1: Host logs in and creates room ──────────────────────────────

  const hostContext = await browser.newContext()
  contexts.push(hostContext)
  const hostPage = await hostContext.newPage()

  await hostPage.goto(`${BASE_URL}/`)
  await hostPage.evaluate(() => localStorage.clear())
  await hostPage.goto(`${BASE_URL}/`)

  // Login
  await hostPage.getByPlaceholder('Enter your nickname').fill('Host')
  await hostPage.getByRole('button', { name: /Create Room/i }).first().click()
  await hostPage.waitForURL(/\/create-room/, { timeout: 10_000 })

  // Configure room: set player count
  // The stepper shows the current total. Default is 9.
  // Adjust if needed by clicking +/- buttons.
  const currentCount = await hostPage.locator('.stepper-num').textContent()
  const current = parseInt(currentCount ?? '9', 10)
  if (totalPlayers > current) {
    for (let i = 0; i < totalPlayers - current; i++) {
      await hostPage.locator('.stepper-btn').last().click()
    }
  } else if (totalPlayers < current) {
    for (let i = 0; i < current - totalPlayers; i++) {
      await hostPage.locator('.stepper-btn').first().click()
    }
  }

  // Configure custom roles if provided
  if (opts.roles && opts.roles.length > 0) {
    // Default optional roles that are enabled by default
    const defaultOptional = ['SEER', 'WITCH', 'HUNTER']
    const requiredRoles = ['WEREWOLF', 'VILLAGER']
    const allOptionalRoles = ['SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT']
    
    // For each optional role, toggle to match desired state
    for (const role of allOptionalRoles) {
      const shouldBeEnabled = opts.roles.includes(<"WEREWOLF" | "SEER" | "WITCH" | "GUARD" | "HUNTER" | "IDIOT" | "VILLAGER">role)
      
      // Find the role row
      const roleRow = hostPage.locator('.role-row').filter({ hasText: new RegExp(role, 'i') })
      
      // Check current state - if has toggle-on, it's enabled; toggle-off means disabled
      const toggleOn = roleRow.locator('.toggle-on')
      const toggleOff = roleRow.locator('.toggle-off')
      
      const isCurrentlyEnabled = (await toggleOn.count()) > 0
      
      // Toggle if state doesn't match
      if (isCurrentlyEnabled !== shouldBeEnabled) {
        // Click the appropriate toggle
        const toggleToClick = isCurrentlyEnabled ? toggleOn : toggleOff
        if ((await toggleToClick.count()) > 0) {
          await toggleToClick.click()
          await hostPage.waitForTimeout(300)
        }
      }
    }
  }

  // Toggle sheriff if needed (default is on, we may want to turn it off)
  if (!hasSheriff) {
    // Find the row containing "Sheriff Election" and click its toggle button
    const sheriffRow = hostPage.locator('.role-row').filter({ hasText: /Sheriff|警长竞选/ })
    const toggle = sheriffRow.locator('.toggle-on')
    if ((await toggle.count()) > 0) {
      await toggle.click()
      await hostPage.waitForTimeout(300)
    }
  }

  // Create the room
  await hostPage.getByRole('button', { name: /Create Room/i }).click()
  await hostPage.waitForURL(/\/room\//, { timeout: 10_000 })

  // Get room code
  const roomCode = (await hostPage.locator('[data-testid="room-code"]').textContent()) ?? ''
  if (!roomCode.match(/^[A-Z0-9]{4,6}$/)) {
    throw new Error(`Invalid room code: ${roomCode}`)
  }

  // ── Step 2: Bots join + ready ──────────────────────────────────────────

  joinBots(roomCode, totalPlayers - 1, true)

  // Inject host into state file so scripts (roles.sh) and getRoles() can discover the host's role
  const hostJwt = await hostPage.evaluate(() => localStorage.getItem('jwt'))
  const hostUserId = await hostPage.evaluate(() => localStorage.getItem('userId'))
  if (hostJwt) {
    const stateFilePath = path.join('/tmp', `werewolf-${roomCode.toUpperCase()}.json`)
    const stateData = JSON.parse(readFileSync(stateFilePath, 'utf-8'))
    stateData.hostToken = hostJwt
    stateData.hostNick = 'Host'
    stateData.hostUserId = hostUserId
    // Add host to users array so getRoles() includes the host
    if (!stateData.users) stateData.users = []
    stateData.users.push({
      nick: 'Host',
      token: hostJwt,
      seat: 0, // will be updated after seat claim
      userId: hostUserId,
    })
    writeFileSync(stateFilePath, JSON.stringify(stateData, null, 2))
  }

  // Wait for room to reflect all bots
  await hostPage.waitForTimeout(2_000)

  // ── Step 3: Host claims a seat + starts game ───────────────────────────

  // The host needs to click on an empty seat to claim it.
  // After bots fill seats 1..(N-1), the last seat should be selectable.
  const emptySlot = hostPage.locator('.slot-selectable').first()
  await emptySlot.waitFor({ state: 'visible', timeout: 5_000 })
  await emptySlot.click()
  await hostPage.waitForTimeout(1_000)

  // Host has no Ready button (only guests do). Claiming the seat auto-readies the host.
  // Wait for Start Game to become enabled.
  const startBtn = hostPage.getByRole('button', { name: /Start Game|开始游戏/i })
  await expect(startBtn).toBeEnabled({ timeout: 15_000 })
  await startBtn.click()

  // Wait for redirect to game view
  await hostPage.waitForURL(/\/game\//, { timeout: 15_000 })

  // Extract gameId from URL
  const gameIdMatch = hostPage.url().match(/\/game\/(\d+)/)
  if (!gameIdMatch) throw new Error(`Could not extract gameId from URL: ${hostPage.url()}`)
  const gameId = gameIdMatch[1]

  // ── Step 5: All bots confirm roles ─────────────────────────────────────
  // Note: this confirms ALL users in the state file, including the host.
  // The game logic relies on STMP synchronization to handle state transitions.

  act('CONFIRM_ROLE', undefined, { room: roomCode })

  // ── Step 6: Host confirms role in browser (if still on reveal screen) ──
  // The script confirms roles via API, but we need to ensure the browser state
  // is synchronized. For hosts with hasSheriff=false, the browser should show
  // the role reveal screen until all players are confirmed.

  await hostPage.waitForTimeout(1_000)

  // Check if we're still on the role reveal screen
  const revealWrap = hostPage.locator('.reveal-wrap')
  const revealVisible = await revealWrap.isVisible().catch(() => false)
  
  if (revealVisible) {
    // Still on role reveal screen - need to complete the browser confirm flow
    const revealBtn = hostPage.getByTestId('reveal-role-btn')
    const revealBtnCount = await revealBtn.count()
    
    if (revealBtnCount > 0) {
      const revealBtnVisible = await revealBtn.isVisible().catch(() => false)
      
      if (revealBtnVisible) {
        // Click reveal button
        await revealBtn.click()
        await hostPage.waitForTimeout(300)
        
        // Click confirm button
        const confirmBtn = hostPage.getByTestId('confirm-role-btn')
        try {
          await confirmBtn.waitFor({ state: 'visible', timeout: 2_000 })
          await confirmBtn.click()
        } catch {
          // Confirm button not found - page likely transitioned, which is acceptable
          console.log('Confirm button not found (page may have transitioned), continuing...')
        }
      }
    }
  }
  
  // Additional wait to ensure all STMP events are processed
  await hostPage.waitForTimeout(2_000)

  // ── Step 7: Discover roles ─────────────────────────────────────────────

  // Role assignment is async on the backend — on loaded CI runners the
  // 2s wait above is sometimes not enough and roles.sh returns an empty
  // or partial mapping. Poll up to 8 extra seconds; exit early on the
  // first non-empty result. Fixes observed flake where roleMap.IDIOT was
  // undefined even though IDIOT was in the requested roles list.
  let roleMap = getRoles(roomCode)
  const expectedRoles = (opts.roles ?? []) as RoleName[]
  for (let attempt = 0; attempt < 8; attempt++) {
    const haveAllRequested = expectedRoles.length === 0
      || expectedRoles.every((r) => (roleMap[r]?.length ?? 0) > 0)
    if (Object.keys(roleMap).length > 0 && haveAllRequested) break
    await hostPage.waitForTimeout(1_000)
    roleMap = getRoles(roomCode)
  }
  const state = readStateFile(roomCode)

  // Detect the host's role from the roleMap
  let hostRole: RoleName | null = null
  for (const [role, bots] of Object.entries(roleMap) as [RoleName, BotInfo[]][]) {
    const hostBot = bots.find((b) => b.nick === 'Host')
    if (hostBot) {
      hostRole = role
      break
    }
  }

  // ── Step 8: Open browser contexts per role ─────────────────────────────

  // Determine which roles to open browsers for
  const desiredRoles =
    opts.browserRoles ??
    (['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[])

  const pages = new Map<string, Page>()
  const botsByRole = new Map<string, BotInfo>()

  // Always include host page
  pages.set('HOST', hostPage)

  // If the host has a desired role, map that role to the host's page
  if (hostRole && desiredRoles.includes(hostRole)) {
    pages.set(hostRole, hostPage)
  }

  for (const role of desiredRoles) {
    // Skip if already mapped (host has this role)
    if (pages.has(role)) continue

    const botsForRole = roleMap[role]
    if (!botsForRole || botsForRole.length === 0) continue

    // Pick the first non-host bot for this role
    const bot = botsForRole.find((b) => b.nick !== 'Host') ?? botsForRole[0]
    if (bot.nick === 'Host') continue // host is already mapped above

    const creds = getConsoleLogin(bot.nick, roomCode)

    const ctx = await browser.newContext()
    contexts.push(ctx)
    const page = await ctx.newPage()

    // Login by setting localStorage directly
    await page.goto(`${BASE_URL}/`)
    await page.evaluate(
      ({ jwt, nickname, userId }) => {
        localStorage.setItem('jwt', jwt)
        localStorage.setItem('nickname', nickname)
        localStorage.setItem('userId', userId)
      },
      { jwt: creds.jwt, nickname: creds.nickname, userId: creds.userId },
    )

    // Navigate to game
    await page.goto(`${BASE_URL}/game/${gameId}`)

    // Wait for game view to load (any phase component)
    await page.locator('.game-wrap').waitFor({ state: 'visible', timeout: 15_000 })

    pages.set(role, page)
    botsByRole.set(role, bot)
  }

  const isHostRole = (role: RoleName) => hostRole === role

  return {
    roomCode,
    gameId,
    hostPage,
    hostContext,
    pages,
    bots: botsByRole,
    allBots: state.bots,
    roleMap,
    hostRole,
    isHostRole,
    cleanup: async () => {
      for (const ctx of contexts) {
        try {
          await ctx.close()
        } catch {
          // ignore close errors
        }
      }
    },
  }
}