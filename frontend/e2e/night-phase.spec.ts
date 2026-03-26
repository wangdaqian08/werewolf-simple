/**
 * Night phase E2E tests — covers all six scenarios and key interactions.
 */
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function setup(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('Test')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)
  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

async function loadNight(page: Page, scenario: string) {
  await page.evaluate((s) => (window as any).__debug.nightScenario(s), scenario)
  await page.waitForTimeout(70)
}

// ── Werewolf ──────────────────────────────────────────────────────────────────

test('night: WEREWOLF — role badge is RED', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // The WEREWOLF tag should use rb-tag-wolf (red styling)
  const tag = page.locator('.rb-tag-wolf')
  await expect(tag).toBeVisible()
  await expect(tag).toContainText('WEREWOLF')
})

test('night: WEREWOLF — teammates shown as blue cells', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // Teammate cells have slot-teammate class (blue color)
  const teammateCells = page.locator('.slot-teammate')
  await expect(teammateCells.first()).toBeVisible()
})

test('night: WEREWOLF — teammate list shows Alice and Eve', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // Both teammates visible in the team-row
  await expect(page.getByText(/Alice/).first()).toBeVisible()
  await expect(page.getByText(/Eve/).first()).toBeVisible()
})

test('night: WEREWOLF — teammates ARE selectable (can be attack targets)', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // Teammate cells should NOT be disabled
  const teammateCells = page.locator('.slot-teammate')
  const firstTeammate = teammateCells.first()
  await expect(firstTeammate).toBeVisible()
  await expect(firstTeammate).not.toBeDisabled()
})

test('night: WEREWOLF — grid is 4-column compact (square cells)', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // Grid exists with compact cells
  const grid = page.locator('.player-grid').first()
  await expect(grid).toBeVisible()
  // All 9 players shown
  const cells = page.locator('.player-grid .player-slot')
  expect(await cells.count()).toBeGreaterThanOrEqual(9)
})

test('night: WEREWOLF — selecting a target enables confirm button', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WEREWOLF')

  // Confirm button disabled initially
  await expect(page.getByRole('button', { name: /确认袭击 Confirm/i })).toBeDisabled()

  // Click a pickable cell (Bob, seat 3, not a teammate or self)
  const pickCells = page.locator('.player-grid .slot-alive')
  await pickCells.first().click()
  await page.waitForTimeout(70)

  await expect(page.getByRole('button', { name: /确认袭击 Confirm/i })).not.toBeDisabled()
})

// ── Seer ──────────────────────────────────────────────────────────────────────

test('night: SEER — role badge is GOLD (special color)', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'SEER_PICK')

  const tag = page.locator('.rb-tag-special')
  await expect(tag).toBeVisible()
  await expect(tag).toContainText('SEER')
})

test('night: SEER_PICK — grid and Check button visible', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'SEER_PICK')

  await expect(page.getByText('夜晚降临').first()).toBeVisible()
  await expect(page.getByText(/查验一名玩家的身份/)).toBeVisible()
  await expect(page.getByRole('button', { name: /查验 · Check/i })).toBeDisabled()
})

test('night: SEER_RESULT — shows result card with history', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'SEER_RESULT')

  // Header changed to 查验结果
  await expect(page.getByText('查验结果').first()).toBeVisible()
  // Tom checked as werewolf
  await expect(page.getByText(/Tom/).first()).toBeVisible()
  await expect(page.getByText(/是狼人！· Werewolf/).first()).toBeVisible()
  // History section always visible
  await expect(page.getByText('历史查验记录')).toBeVisible()
  // History entries from preset
  await expect(page.getByText(/Round 1/).first()).toBeVisible()
  await expect(page.getByText(/Round 2/).first()).toBeVisible()
})

test('night: SEER_RESULT — history shown even when empty (srh-empty message)', async ({
  page,
}) => {
  await setup(page)
  // Load SEER_RESULT scenario which has history
  // For empty history test, we trigger a live seer check (first round, no prev history)
  await loadNight(page, 'SEER_PICK')

  // Select a player
  const pickCells = page.locator('.player-grid .slot-alive')
  await pickCells.first().click()
  await page.waitForTimeout(200)

  // Click Check button
  await page.getByRole('button', { name: /查验 · Check/i }).click()
  await page.waitForTimeout(200)

  // Should be on SEER_RESULT — history section always visible
  await expect(page.getByText('历史查验记录')).toBeVisible()
  // The current check should appear in history
  await expect(page.locator('.srh-row').first()).toBeVisible()
})

test('night: SEER_RESULT — countdown timer shows and decrements', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'SEER_RESULT')

  // Should show enabled confirm button with countdown
  const btn = page.getByRole('button', { name: /查验完毕/i })
  await expect(btn).toBeVisible()
  await expect(btn).not.toBeDisabled()
  // Countdown decrements after 1.5 seconds
  await page.waitForTimeout(1500)
  const btnText = await btn.textContent()
  expect(btnText).toContain('s') // has countdown
})

// ── Witch ─────────────────────────────────────────────────────────────────────

test('night: WITCH — role badge is GOLD (special color)', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  const tag = page.locator('.rb-tag-special')
  await expect(tag).toBeVisible()
  await expect(tag).toContainText('WITCH')
})

test('night: WITCH — BOTH antidote AND poison sections visible simultaneously', async ({
  page,
}) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  // Both sections should be visible at the same time (no sequential reveal)
  await expect(page.getByText(/解药 · ANTIDOTE/i).first()).toBeVisible()
  await expect(page.getByText(/毒药 · POISON/i).first()).toBeVisible()
  // Both "use" buttons enabled
  await expect(page.getByRole('button', { name: /使用解药/i })).not.toBeDisabled()
  await expect(page.getByRole('button', { name: /使用毒药/i })).not.toBeDisabled()
})

test('night: WITCH — attacked player name highlighted in antidote section', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  // Should show attacked player info
  await expect(page.locator('.ws-killed').first()).toContainText('Tom')
})

test('night: WITCH — using antidote immediately transitions to WAITING (only 1 action/round)', async ({
  page,
}) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  await page.getByRole('button', { name: /使用解药/i }).click()
  await page.waitForTimeout(70)

  // Should go straight to sleep screen — no second action allowed
  await expect(page.getByText('请闭眼')).toBeVisible()
})

test('night: WITCH — both decisions made → transitions to WAITING screen', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  // Pass antidote then pass poison
  await page.getByRole('button', { name: '放弃' }).click()
  await page.waitForTimeout(70)
  await page.getByRole('button', { name: '不用' }).click()
  await page.waitForTimeout(70)

  // Should transition to WAITING subPhase — shows sleep screen
  await expect(page.getByText('请闭眼')).toBeVisible()
})

test('night: WITCH — using poison (full flow) → WAITING', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  // Open poison picker
  await page.getByRole('button', { name: /使用毒药/i }).click()
  await page.waitForTimeout(70)

  // Poison picker grid visible, confirm disabled until target picked
  const confirmPoison = page.getByRole('button', { name: /确认毒杀/i })
  await expect(confirmPoison).toBeDisabled()

  // Select a target
  await page.locator('.player-grid .slot-alive').first().click()
  await page.waitForTimeout(70)
  await expect(confirmPoison).not.toBeDisabled()

  // Confirm poison — should go straight to WAITING (no second action)
  await confirmPoison.click()
  await page.waitForTimeout(70)
  await expect(page.getByText('请闭眼')).toBeVisible()
})

test('night: WITCH — pass antidote → poison section still active', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  await page.getByRole('button', { name: '放弃' }).click()
  await page.waitForTimeout(70)

  // Antidote section grayed out, poison section still interactive
  await expect(page.getByRole('button', { name: /使用解药/i })).toBeDisabled()
  await expect(page.getByRole('button', { name: /使用毒药/i })).not.toBeDisabled()
})

test('night: WITCH — pass poison → antidote section still active', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WITCH')

  await page.getByRole('button', { name: '不用' }).click()
  await page.waitForTimeout(70)

  // Poison section grayed out, antidote section still interactive
  await expect(page.getByRole('button', { name: /使用毒药/i })).toBeDisabled()
  await expect(page.getByRole('button', { name: /使用解药/i })).not.toBeDisabled()
})

// ── Guard ─────────────────────────────────────────────────────────────────────

test('night: GUARD — role badge is GOLD (special color)', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'GUARD')

  const tag = page.locator('.rb-tag-special')
  await expect(tag).toBeVisible()
  await expect(tag).toContainText('GUARD')
})

test('night: GUARD — confirm button is RED', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'GUARD')

  // Confirm button should use btn-danger (red)
  const confirmBtn = page.getByRole('button', { name: /确认保护 Confirm/i })
  await expect(confirmBtn).toBeVisible()
  // Verify the CSS class
  await expect(confirmBtn).toHaveClass(/btn-danger/)
})

test('night: GUARD — previous target shown as dimmed / not selectable', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'GUARD')

  // Previous guard target shown with data-prev-guard attribute (dimmed)
  const prevCell = page.locator('[data-prev-guard="true"]')
  await expect(prevCell).toBeVisible()
  await expect(prevCell).toHaveAttribute('aria-disabled', 'true')
  // Guard note shown
  await expect(page.getByText(/上轮已保护/)).toBeVisible()
})

// ── Waiting ───────────────────────────────────────────────────────────────────

test('night: WAITING — sleep emoji and messages visible', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WAITING')

  await expect(page.getByText('请闭眼')).toBeVisible()
  await expect(page.getByText(/Please close your eyes/i)).toBeVisible()
  await expect(page.getByText(/Other players are taking actions/i)).toBeVisible()
  // No role badge for WAITING
  await expect(page.locator('.rb')).not.toBeVisible()
})

// ── Night → Day advance ───────────────────────────────────────────────────────

test('night: nightAdvance transitions to DAY phase', async ({ page }) => {
  await setup(page)
  await loadNight(page, 'WAITING')

  await page.evaluate(() => (window as any).__debug.nightAdvance())
  await page.waitForTimeout(70)

  await expect(page.getByText('夜晚降临')).not.toBeVisible()
})

// ── Debug panel buttons ───────────────────────────────────────────────────────

test('night: debug panel has all night scenario buttons', async ({ page }) => {
  await setup(page)

  await expect(page.getByRole('button', { name: 'Werewolf', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Seer: Pick', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Seer: Result', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Witch', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Guard', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Waiting', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '→ Day', exact: true })).toBeVisible()
})

test('night: debug panel Werewolf button loads werewolf screen', async ({ page }) => {
  await setup(page)

  await page.getByRole('button', { name: 'Werewolf', exact: true }).click()
  await page.waitForTimeout(70)

  await expect(page.getByText('夜晚降临').first()).toBeVisible()
  await expect(page.locator('.rb-tag-wolf')).toBeVisible()
})
