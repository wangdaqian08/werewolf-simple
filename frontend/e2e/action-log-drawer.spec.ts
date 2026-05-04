/**
 * E2E tests for ActionLogDrawer — the floating 📋 button and slide-up drawer
 * that shows night deaths and vote results.
 *
 * The drawer is rendered inside DayPhase.vue. We navigate directly to
 * /game/game-001 (mock game ID) in the HOST_REVEALED day scenario because
 * that reliably puts the UI into DAY_DISCUSSION with the FAB visible.
 *
 * Privacy check: the mock data (MOCK_ACTION_LOG in mocks/data.ts) contains
 * only {dayNumber, userId, nickname, seatIndex} for NIGHT_DEATH — no "cause"
 * or "killedBy" fields. Tests verify only those safe fields appear.
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function goToDayScenario(page: Page, scenario: 'HOST_HIDDEN' | 'HOST_REVEALED' | 'ALIVE_HIDDEN' | 'ALIVE_REVEALED') {
  await page.goto('/')
  await page.evaluate(() => {
    localStorage.setItem('jwt', 'mock-jwt-token-abc123')
    localStorage.setItem('userId', 'u1')
    localStorage.setItem('nickname', 'You')
  })
  await page.goto('/game/game-001')
  await page.waitForTimeout(300)

  const testIdMap: Record<string, string> = {
    HOST_HIDDEN: 'debug-scenario-host-hidden',
    HOST_REVEALED: 'debug-scenario-host-revealed',
    ALIVE_HIDDEN: 'debug-scenario-alive-hidden',
    ALIVE_REVEALED: 'debug-scenario-alive-revealed',
  }
  await page.locator(`[data-testid="${testIdMap[scenario]}"]`).click()
  // Wait for the DayPhase component to render
  await expect(page.locator('.day-wrap')).toBeVisible({ timeout: 3000 })
}

// ── FAB button visibility ─────────────────────────────────────────────────────

test('📋 FAB is visible in day phase', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  const fab = page.locator('button.log-fab')
  await expect(fab).toBeVisible()
  await expect(fab).toHaveAttribute('aria-label', '游戏记录')
})

test('📋 FAB is visible for non-host alive player', async ({ page }) => {
  // FAB is hidden during RESULT_HIDDEN to prevent spoilers (see DayPhase.vue),
  // so assert visibility after the host has revealed the night result.
  await goToDayScenario(page, 'ALIVE_REVEALED')
  await expect(page.locator('button.log-fab')).toBeVisible()
})

// ── Drawer open / close ───────────────────────────────────────────────────────

test('clicking FAB opens the drawer', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await expect(page.locator('.action-log-drawer')).not.toBeVisible()
  await page.locator('button.log-fab').click()
  await expect(page.locator('.action-log-drawer')).toBeVisible()
  await expect(page.locator('.drawer-title')).toHaveText('游戏记录')
})

test('✕ button closes the drawer', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()
  await expect(page.locator('.action-log-drawer')).toBeVisible()
  await page.locator('.drawer-close').click()
  // v-if removes element from DOM after slide-up leave transition (0.25s)
  await expect(page.locator('.action-log-drawer')).not.toBeAttached()
})

test('clicking backdrop closes the drawer', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()
  // Wait for both drawer AND backdrop to finish their enter transitions
  const backdrop = page.locator('.drawer-backdrop')
  await expect(backdrop).toBeVisible()
  await backdrop.click()
  // v-if removes element from DOM after slide-up leave transition (0.25s)
  await expect(page.locator('.action-log-drawer')).not.toBeAttached()
})

// ── Content: night deaths ─────────────────────────────────────────────────────

test('drawer shows 昨夜出局 section with night death data', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  // Round 1 block
  const round = page.locator('.round-block').first()
  await expect(round.locator('.round-label')).toHaveText('第 1 天')

  // Night death section — locate by title text so reorderings don't break us
  const deathSection = round.locator('.log-section').filter({ hasText: '昨夜出局' })
  await expect(deathSection.locator('.section-title')).toContainText('昨夜出局')

  // Charlie died at seat 3
  const deathRow = deathSection.locator('.log-row').first()
  await expect(deathRow.locator('.seat-badge')).toHaveText('3号')
  await expect(deathRow.locator('.log-name')).toHaveText('Charlie')
})

test('night death row has no cause or killedBy text', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  const deathSection = page
    .locator('.round-block')
    .first()
    .locator('.log-section')
    .filter({ hasText: '昨夜出局' })
  const text = await deathSection.textContent()
  expect(text).not.toContain('cause')
  expect(text).not.toContain('killedBy')
  expect(text).not.toContain('wolf')
  expect(text).not.toContain('witch')
  expect(text).not.toContain('WEREWOLF')
})

// ── Content: sheriff election ─────────────────────────────────────────────────

test('drawer shows ⭐ 警长选举 section at the top of day 1 when game has a sheriff', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  // Sheriff section is the FIRST section inside the day-1 round-block — before
  // 昨夜出局 — so players see who became sheriff before reading the round.
  const round = page.locator('.round-block').first()
  const firstSection = round.locator('.log-section').first()
  await expect(firstSection.locator('.section-title')).toContainText('警长选举')

  // Bob (seat 2) won the mock sheriff election
  const winnerRow = firstSection.locator('.log-row').first()
  await expect(winnerRow.locator('.seat-badge')).toHaveText('2号')
  await expect(winnerRow.locator('.log-name')).toHaveText('Bob')
  await expect(winnerRow.locator('.log-tag.tag-gold')).toHaveText('警长')
})

test('drawer shows sheriff election tally so players can analyse alliances', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  const sheriffSection = page.locator('.round-block').first().locator('.log-section').first()
  // Bob got 4 votes (winning row of the tally)
  const winnerTally = sheriffSection.locator('.tally-row').first()
  await expect(winnerTally.locator('.tally-votes')).toHaveText('4 票')
  await expect(winnerTally.locator('.tally-voters')).toContainText('1号')
  await expect(winnerTally.locator('.tally-voters')).toContainText('3号')
  await expect(winnerTally.locator('.tally-voters')).toContainText('5号')
  await expect(winnerTally.locator('.tally-voters')).toContainText('6号')
})

test('sheriff section surfaces abstain voters so analysts can flag silent wolves', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  const sheriffSection = page.locator('.round-block').first().locator('.log-section').first()
  const abstainRow = sheriffSection.locator('.tally-abstain')
  await expect(abstainRow).toBeVisible()
  await expect(abstainRow.locator('.tally-name')).toHaveText('弃权')
  await expect(abstainRow.locator('.tally-votes')).toHaveText('1 票')
  // Mock seeds Grace (seat 7) as the lone abstainer
  await expect(abstainRow.locator('.tally-voters')).toContainText('7号')
})

// ── Content: vote results ─────────────────────────────────────────────────────

test('drawer shows 投票结果 section with eliminated player', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  // Vote result section — locate by title text so it survives reorderings
  const voteSection = page
    .locator('.round-block')
    .first()
    .locator('.log-section')
    .filter({ hasText: '投票结果' })
  await expect(voteSection.locator('.section-title')).toContainText('投票结果')

  // Eve (seat 5) was eliminated
  const eliminationRow = voteSection.locator('.log-row').first()
  await expect(eliminationRow.locator('.seat-badge')).toHaveText('5号')
  await expect(eliminationRow.locator('.log-name')).toHaveText('Eve')
  await expect(eliminationRow.locator('.log-tag')).toHaveText('出局')
})

test('drawer shows vote tally breakdown', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  const voteSection = page
    .locator('.round-block')
    .first()
    .locator('.log-section')
    .filter({ hasText: '投票结果' })
  // tally rows include the abstain row, so target the candidate row by class
  const tallyRow = voteSection
    .locator('.tally-row')
    .filter({ hasNotText: '弃权' })
    .first()

  // Eve got 3 votes from seats 1, 2, 4
  await expect(tallyRow.locator('.tally-votes')).toHaveText('3 票')
  await expect(tallyRow.locator('.tally-voters')).toContainText('1号')
  await expect(tallyRow.locator('.tally-voters')).toContainText('2号')
  await expect(tallyRow.locator('.tally-voters')).toContainText('4号')
})

test('vote-result section surfaces abstain voters', async ({ page }) => {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('button.log-fab').click()

  const voteSection = page
    .locator('.round-block')
    .first()
    .locator('.log-section')
    .filter({ hasText: '投票结果' })
  const abstainRow = voteSection.locator('.tally-abstain')
  await expect(abstainRow).toBeVisible()
  await expect(abstainRow.locator('.tally-votes')).toHaveText('2 票')
  // Mock seeds Frank (seat 6) and Henry (seat 8) as abstainers
  await expect(abstainRow.locator('.tally-voters')).toContainText('6号')
  await expect(abstainRow.locator('.tally-voters')).toContainText('8号')
})

// ── VotingPhase coverage ──────────────────────────────────────────────────────
// The FAB is mirrored into VotingPhase.vue so the game record stays reachable
// for the entire daytime, not only during DAY_DISCUSSION.

async function goToVotingScenario(page: Page) {
  await goToDayScenario(page, 'HOST_REVEALED')
  await page.locator('[data-testid="debug-voting"]').click()
  await expect(page.locator('.voting-wrap')).toBeVisible({ timeout: 3000 })
}

test('📋 FAB is visible in voting phase', async ({ page }) => {
  await goToVotingScenario(page)
  const fab = page.locator('button.log-fab')
  await expect(fab).toBeVisible()
  await expect(fab).toHaveAttribute('aria-label', '游戏记录')
})

test('clicking FAB opens the drawer in voting phase', async ({ page }) => {
  await goToVotingScenario(page)
  await expect(page.locator('.action-log-drawer')).not.toBeVisible()
  await page.locator('button.log-fab').click()
  await expect(page.locator('.action-log-drawer')).toBeVisible()
  await expect(page.locator('.drawer-title')).toHaveText('游戏记录')
})
