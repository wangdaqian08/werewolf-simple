/**
 * Targeted tests for the Role Badge + Vote History panel added in the
 * "check-role-log" branch.
 *
 * Navigates directly to /game/game-001 (the mock game ID) to bypass the
 * STOMP-based game-start flow which is a pre-existing E2E infra limitation.
 */
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function goToVotingScenario(page: Page, scenario: string) {
  // Set a mock token so the auth guard doesn't redirect
  await page.goto('/')
  await page.evaluate(() => {
    localStorage.setItem('jwt', 'mock-jwt-token-abc123')
    localStorage.setItem('userId', 'u1')
    localStorage.setItem('nickname', 'You')
  })
  // Navigate directly to the game view (mock returns state for game-001)
  await page.goto('/game/game-001')
  await page.waitForTimeout(300) // allow mock state to load

  // Click the scenario button in the debug voting buttons section
  await page
    .locator('[data-testid="debug-voting-btns"]')
    .getByRole('button', { name: scenario })
    .click()
  await page.waitForTimeout(100)
}

// ── Feature 1: My Role badge ──────────────────────────────────────────────────

test('role chip shows locked state by default', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  const chip = page.locator('.my-role-chip')
  await expect(chip).toBeVisible()
  await expect(chip).toContainText('🔒')
  await expect(chip).toContainText('身份')
  await expect(chip).toContainText('Tap to reveal')
})

test('role chip has my-role-locked class', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await expect(page.locator('.my-role-chip')).toHaveClass(/my-role-locked/)
})

test('role card opens on chip tap and shows role info', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  const sheet = page.locator('.role-card-sheet')
  await expect(sheet).toBeVisible()
  // SEER role in mock data
  await expect(sheet).toContainText('预言家')
  await expect(sheet).toContainText('SEER')
  await expect(sheet).toContainText('你的身份')
})

test('role card closes when X button clicked', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  await expect(page.locator('.role-card-sheet')).toBeVisible()
  await page.locator('.role-card-overlay .history-close').click()
  await expect(page.locator('.role-card-sheet')).not.toBeVisible()
})

test('role card closes when clicking overlay backdrop', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  await expect(page.locator('.role-card-sheet')).toBeVisible()
  await page.locator('.role-card-overlay').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.role-card-sheet')).not.toBeVisible()
})

test('role chip is visible on Revealed scenario', async ({ page }) => {
  // Both VOTING and VOTING_REVEALED scenarios use MOCK_GAME_STATE which has myRole: SEER
  // The role chip should still be visible (locked)
  await goToVotingScenario(page, 'Revealed')
  await expect(page.locator('.my-role-chip')).toBeVisible()
})

// ── Feature 2: Vote History panel ────────────────────────────────────────────

test('history button is visible on voting screen', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await expect(page.locator('.history-btn')).toBeVisible()
  await expect(page.locator('.history-btn')).toContainText('历史')
})

test('history panel opens on button click', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await expect(page.locator('.history-sheet')).toBeVisible()
  await expect(page.locator('.history-sheet')).toContainText('投票记录')
  await expect(page.locator('.history-sheet')).toContainText('Vote History')
})

test('history panel shows day 1 round data', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const sheet = page.locator('.history-sheet')
  await expect(sheet).toContainText('第 1 天')
  await expect(sheet).toContainText('Day 1')
  // Dave was eliminated in mock history
  await expect(sheet).toContainText('Dave')
  // Dave's role was VILLAGER
  await expect(sheet).toContainText('村民')
})

test('history panel shows vote tallies for day 1', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const sheet = page.locator('.history-sheet')
  // Dave got 4 votes (top of tally)
  await expect(sheet).toContainText('4')
  // Alice got 3 votes
  await expect(sheet).toContainText('3')
})

test('history panel shows vote-out banner for day 1', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const sheet = page.locator('.history-sheet')
  // Vote-out banner: Dave eliminated
  await expect(sheet).toContainText('出局')
  await expect(sheet).toContainText('Dave')
})

test('history panel shows hunter-shot banner for day 1', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const sheet = page.locator('.history-sheet')
  // Hunter shot Alice
  await expect(sheet).toContainText('猎人开枪')
  await expect(sheet).toContainText('Alice')
  await expect(sheet).toContainText('狼人')
})

test('history panel closes when X button clicked', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await expect(page.locator('.history-sheet')).toBeVisible()
  await page.locator('.history-close').click()
  await expect(page.locator('.history-sheet')).not.toBeVisible()
})

test('history panel closes when clicking overlay backdrop', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await expect(page.locator('.history-sheet')).toBeVisible()
  // Click the overlay (not the sheet itself)
  await page.locator('.history-overlay').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.history-sheet')).not.toBeVisible()
})

test('role-history row hidden on HUNTER_SHOOT screen (different template)', async ({ page }) => {
  await goToVotingScenario(page, 'Hunter')
  // The role+history row is only inside the isVotingScreen template block
  await expect(page.locator('.role-history-row')).not.toBeVisible()
})

// ── HIGH: Body scroll lock ────────────────────────────────────────────────────

test('body scroll locks when history panel opens', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  const before = await page.evaluate(() => document.body.style.overflow)
  expect(before).not.toBe('hidden')

  await page.locator('.history-btn').click()
  const locked = await page.evaluate(() => document.body.style.overflow)
  expect(locked).toBe('hidden')
})

test('body scroll restores when history panel closes via X', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await page.locator('.history-close').click()
  const overflow = await page.evaluate(() => document.body.style.overflow)
  expect(overflow).toBe('')
})

test('body scroll locks when role card opens', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  const locked = await page.evaluate(() => document.body.style.overflow)
  expect(locked).toBe('hidden')
})

test('body scroll restores when role card closes via X', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  await page.locator('.role-card-overlay .history-close').click()
  const overflow = await page.evaluate(() => document.body.style.overflow)
  expect(overflow).toBe('')
})

// ── HIGH: Hunter-shot banner absent when round has no hunter shot ─────────────

test('hunter-shot banner absent for Day 3 (no hunter shot in that round)', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  // Rounds are reversed — Day 3 is first in the DOM
  const day3 = page.locator('.history-round').first()
  await expect(day3).toContainText('第 3 天')
  await expect(day3).not.toContainText('猎人开枪')
})

// ── HIGH: History button hidden / row absent when data is missing ─────────────

test('history button hidden when voteHistory is empty (chip still visible)', async ({ page }) => {
  await goToVotingScenario(page, 'No History')
  await expect(page.locator('.my-role-chip')).toBeVisible()
  await expect(page.locator('.history-btn')).not.toBeVisible()
})

test('role-history row absent when neither myRole nor voteHistory set', async ({ page }) => {
  await goToVotingScenario(page, 'No Data')
  await expect(page.locator('.role-history-row')).not.toBeVisible()
})

// ── MEDIUM: Rounds rendered newest-first ──────────────────────────────────────

test('history rounds are rendered newest-day first', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const rounds = page.locator('.history-round')
  await expect(rounds.first()).toContainText('第 3 天')
  await expect(rounds.nth(1)).toContainText('第 2 天')
  await expect(rounds.last()).toContainText('第 1 天')
})

// ── MEDIUM: Voter rows inside history columns ──────────────────────────────────

test('history columns show voter rows with avatar, seat, and name', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  // Day 1 is last in DOM (reversed); its first column is Dave (4 votes, 4 voters)
  const day1 = page.locator('.history-round').last()
  const firstCol = day1.locator('.vote-col').first()
  await expect(firstCol.locator('.vcol-row')).toHaveCount(4)
  await expect(firstCol).toContainText('Alice')
  await expect(firstCol).toContainText('#2') // Alice is seat 2
})

test('history winner column has vote-col-winner class, others do not', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const day1 = page.locator('.history-round').last()
  await expect(day1.locator('.vote-col').first()).toHaveClass(/vote-col-winner/)
  await expect(day1.locator('.vote-col').nth(1)).not.toHaveClass(/vote-col-winner/)
})

// ── Fix verification: role card is centered modal, not bottom sheet ────────────

test('role card renders as centered modal (not bottom sheet)', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  const overlay = page.locator('.role-card-overlay')
  const sheet = page.locator('.role-card-sheet')
  await expect(overlay).toBeVisible()
  await expect(sheet).toBeVisible()
  // Verify the card is visually centered: its bounding box should not touch the bottom edge
  const overlayBox = await overlay.boundingBox()
  const sheetBox = await sheet.boundingBox()
  expect(overlayBox).not.toBeNull()
  expect(sheetBox).not.toBeNull()
  // Card should be somewhere in the middle of the screen, not pinned to the bottom
  const screenHeight = overlayBox!.height
  const cardBottom = sheetBox!.y + sheetBox!.height
  expect(cardBottom).toBeLessThan(screenHeight * 0.9) // not pinned to bottom
})

// ── Fix verification: history shows Day 2 and Day 3 ──────────────────────────

test('history panel shows Day 2 and Day 3 round data', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  const sheet = page.locator('.history-sheet')
  // Day 2 — Frank eliminated (hunter), shot Grace
  await expect(sheet).toContainText('第 2 天')
  await expect(sheet).toContainText('Frank')
  // Day 3 — Eve eliminated (werewolf, no hunter shot)
  await expect(sheet).toContainText('第 3 天')
  await expect(sheet).toContainText('Eve')
  await expect(sheet).toContainText('狼人')
})

// ── Vote column horizontal scroll ────────────────────────────────────────────

test('history vote columns are horizontally scrollable (Day 1 has 6 candidates)', async ({ page }) => {
  // Use mobile viewport — design is 417px wide; 6 cols × 104px = 664px > 417px → must scroll
  await page.setViewportSize({ width: 417, height: 740 })
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await expect(page.locator('.history-sheet')).toBeVisible()

  // Day 1 is the last round in the reversed list — it has 6 tally entries
  const columns = page.locator('.history-columns').last()
  await expect(columns).toBeVisible()

  // scrollWidth > clientWidth means content overflows → horizontal scroll is active
  const isScrollable = await columns.evaluate((el) => el.scrollWidth > el.clientWidth)
  expect(isScrollable).toBe(true)
})

test('history vote columns can be scrolled to reveal hidden candidates', async ({ page }) => {
  await page.setViewportSize({ width: 417, height: 740 })
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  await expect(page.locator('.history-sheet')).toBeVisible()

  const columns = page.locator('.history-columns').last() // Day 1 — 6 candidates
  const initialScroll = await columns.evaluate((el) => el.scrollLeft)
  expect(initialScroll).toBe(0)

  // Scroll right by 200px — should reveal Carol and Grace columns
  await columns.evaluate((el) => { el.scrollLeft = 200 })
  const afterScroll = await columns.evaluate((el) => el.scrollLeft)
  expect(afterScroll).toBeGreaterThan(0)
})

// ── LOW: Role card description text and team label colour class ───────────────

test('role card shows description text for SEER', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  // SEER description contains 查验 (check/verify)
  await expect(page.locator('.rc-desc')).toContainText('查验')
})

test('role card SEER has rc-label-special class (gold colour)', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.my-role-chip').click()
  await expect(page.locator('.rc-label')).toHaveClass(/rc-label-special/)
  await expect(page.locator('.rc-label')).not.toHaveClass(/rc-label-wolf/)
  await expect(page.locator('.rc-label')).not.toHaveClass(/rc-label-village/)
})

// ── LOW: Day 2 hunter-shot banner shows correct role (VILLAGER → 村民) ─────────

test('hunter-shot banner shows 村民 for Day 2 (Grace is VILLAGER)', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  await page.locator('.history-btn').click()
  // Reversed order: Day3 first, Day2 second
  const day2 = page.locator('.history-round').nth(1)
  await expect(day2).toContainText('第 2 天')
  await expect(day2).toContainText('猎人开枪')
  await expect(day2).toContainText('Grace')
  await expect(day2).toContainText('村民') // VILLAGER role translated
})

// ── Fix verification: SunArc not obscured by role-history-row ────────────────

test('sun arc element does not overlap role-history-row', async ({ page }) => {
  await goToVotingScenario(page, 'Voting')
  const arcWrap = page.locator('.sun-arc-wrap')
  const roleRow = page.locator('.role-history-row')
  await expect(arcWrap).toBeVisible()
  await expect(roleRow).toBeVisible()
  const arcBox = await arcWrap.boundingBox()
  const rowBox = await roleRow.boundingBox()
  expect(arcBox).not.toBeNull()
  expect(rowBox).not.toBeNull()
  // The bottom edge of the arc wrap should be at or above the top edge of the role row
  expect(arcBox!.y + arcBox!.height).toBeLessThanOrEqual(rowBox!.y + 2) // 2px tolerance
})
