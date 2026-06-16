/**
 * E2E spec: sheriff badge must render in the top-right quadrant of the
 * player card across DayPhase, VotingPhase, and NightPhase. The fix is
 * to centralize `.sheriff-badge` CSS in game.css so the absolute
 * positioning applies everywhere — currently DayPhase + VotingPhase
 * lack the scoped style, so the badge falls into the flex column flow
 * and renders below the avatar/name (bottom-center).
 *
 * Mock sheriff: Alice (u2, seatIndex 2). All scenarios inherit
 * MOCK_GAME_STATE which marks her isSheriff: true.
 */

import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

const SHERIFF_SEAT = 2

async function bootMock(page: Page) {
  await page.goto('/')
  await page.evaluate(() => {
    localStorage.setItem('jwt', 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjQwNzA5MDg4MDB9.mock-sig')
    localStorage.setItem('userId', 'u1')
    localStorage.setItem('nickname', 'You')
  })
  await page.goto('/game/game-001')
  await page.waitForTimeout(300)
}

async function gotoScenario(page: Page, debugTestId: string, waitFor: string) {
  await bootMock(page)
  await page.locator(`[data-testid="${debugTestId}"]`).click()
  await expect(page.locator(waitFor)).toBeVisible({ timeout: 10000 })
  await expect(
    page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first(),
  ).toBeVisible({ timeout: 5000 })
}

async function assertBadgeTopRight(card: Locator, badge: Locator, label: string) {
  await expect(card, `${label}: card should be visible`).toBeVisible()
  await expect(badge, `${label}: badge should be visible`).toBeVisible()
  const cardBox = await card.boundingBox()
  const badgeBox = await badge.boundingBox()
  if (!cardBox || !badgeBox) throw new Error(`${label}: missing bounding box`)
  const cardMidX = cardBox.x + cardBox.width / 2
  const cardMidY = cardBox.y + cardBox.height / 2
  // Badge's center should be in the upper-right quadrant of the card.
  const badgeCenterX = badgeBox.x + badgeBox.width / 2
  const badgeCenterY = badgeBox.y + badgeBox.height / 2
  expect.soft(badgeCenterX, `${label}: badge X (${badgeCenterX}) > card midX (${cardMidX})`).toBeGreaterThan(cardMidX)
  expect.soft(badgeCenterY, `${label}: badge Y (${badgeCenterY}) < card midY (${cardMidY})`).toBeLessThan(cardMidY)
  expect(badgeCenterX, `${label}: badge horizontally right of midline`).toBeGreaterThan(cardMidX)
  expect(badgeCenterY, `${label}: badge vertically above midline`).toBeLessThan(cardMidY)
}

test('sheriff badge is top-right in DayPhase HOST_REVEALED', async ({ page }) => {
  await gotoScenario(page, 'debug-scenario-host-revealed', '.day-wrap')
  const card = page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first()
  const badge = card.locator('.sheriff-badge')
  await assertBadgeTopRight(card, badge, 'DayPhase HOST_REVEALED')
  await page.screenshot({
    path: 'e2e/screenshots/sheriff-badge-day-host-revealed.png',
    fullPage: false,
  })
})

test('sheriff badge is top-right in DayPhase HOST_HIDDEN', async ({ page }) => {
  await gotoScenario(page, 'debug-scenario-host-hidden', '.day-wrap')
  const card = page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first()
  const badge = card.locator('.sheriff-badge')
  await assertBadgeTopRight(card, badge, 'DayPhase HOST_HIDDEN')
})

test('sheriff badge is top-right in VotingPhase VOTING', async ({ page }) => {
  await gotoScenario(page, 'debug-voting', '.voting-wrap')
  const card = page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first()
  const badge = card.locator('.sheriff-badge')
  await assertBadgeTopRight(card, badge, 'VotingPhase VOTING')
  await page.screenshot({
    path: 'e2e/screenshots/sheriff-badge-voting.png',
    fullPage: false,
  })
})

test('sheriff badge is top-right in VotingPhase REVEALED', async ({ page }) => {
  await gotoScenario(page, 'debug-voting-revealed', '.voting-wrap')
  const card = page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first()
  const badge = card.locator('.sheriff-badge')
  await assertBadgeTopRight(card, badge, 'VotingPhase REVEALED')
})

test('sheriff badge is top-right in NightPhase WEREWOLF', async ({ page }) => {
  // NightPhase already had the scoped CSS — this is a regression check that
  // moving the rule to game.css doesn't break it.
  await gotoScenario(page, 'debug-night-werewolf', '.nw')
  const card = page.locator(`.player-grid [data-seat="${SHERIFF_SEAT}"]`).first()
  const badge = card.locator('.sheriff-badge')
  await assertBadgeTopRight(card, badge, 'NightPhase WEREWOLF')
  await page.screenshot({
    path: 'e2e/screenshots/sheriff-badge-night.png',
    fullPage: false,
  })
})
