import { expect, test } from '@playwright/test'

// ── Helpers ───────────────────────────────────────────────────────────────────

async function goToSheriffSignup(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })
  // Start game with sheriff enabled (default)
  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
  // Navigate to sheriff SIGNUP phase
  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.waitForTimeout(100)
}

// ── Test 1: withdraw removes player from candidate list ───────────────────────

test('withdraw removes player from candidate list', async ({ page }) => {
  await goToSheriffSignup(page)

  // Verify we're in SIGNUP phase
  await expect(page.getByText(/参选 \/ Run for Sheriff/i)).toBeVisible()

  // Run for sheriff to become a candidate
  await page.getByRole('button', { name: /参选 \/ Run for Sheriff/i }).click()
  await page.waitForTimeout(100)

  // Player is now a candidate — the "Withdraw" button should be visible
  await expect(page.getByRole('button', { name: /撤回 \/ Withdraw/i })).toBeVisible()

  // Note the candidate count before withdrawal (initial SIGNUP mock has Alice + Tom + now You)
  const candidatesBeforeCount = await page.locator('.cand-row-running').count()

  // Click Withdraw
  await page.getByRole('button', { name: /撤回 \/ Withdraw/i }).click()
  await page.waitForTimeout(100)

  // After withdrawal, the Withdraw button should disappear and Run for Sheriff should return
  await expect(page.getByRole('button', { name: /参选 \/ Run for Sheriff/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /撤回 \/ Withdraw/i })).not.toBeVisible()

  // The candidate count should have decreased (u1 removed)
  const candidatesAfterCount = await page.locator('.cand-row-running').count()
  expect(candidatesAfterCount).toBeLessThan(candidatesBeforeCount)
})

// ── Test 2: pass button changes to Passed state ───────────────────────────────

test('pass button changes to Passed state', async ({ page }) => {
  await goToSheriffSignup(page)

  // Both Run for Sheriff and Pass should be visible initially
  await expect(page.getByRole('button', { name: /参选 \/ Run for Sheriff/i })).toBeVisible()
  await expect(page.getByRole('button', { name: '放弃 / Pass', exact: true })).toBeVisible()

  // Click "放弃 / Pass"
  await page.getByRole('button', { name: '放弃 / Pass', exact: true }).click()
  await page.waitForTimeout(100)

  // The "Pass" button disappears after clicking it
  await expect(page.getByRole('button', { name: '放弃 / Pass', exact: true })).not.toBeVisible()
  // "Run for Sheriff" stays visible — player can still change their mind
  await expect(page.getByRole('button', { name: /参选 \/ Run for Sheriff/i })).toBeVisible()
})

// ── Test 3: host sees Next Speaker in My Turn view ────────────────────────────

test('host sees Next Speaker in My Turn view', async ({ page }) => {
  await goToSheriffSignup(page)

  // Use the debug helper to jump to SPEECH_CANDIDATE preset where currentSpeakerId === u1 (the host/You)
  await page.evaluate(() => (window as any).__debug.sheriffPhase('SPEECH_CANDIDATE'))
  await page.waitForTimeout(100)

  // Should now be in "My Turn" view (currentSpeakerId === myUserId)
  await expect(page.getByText(/Your Turn/i)).toBeVisible()

  // "退出竞选 / Quit Campaign" button should be visible
  await expect(page.getByRole('button', { name: /退出竞选 \/ Quit Campaign/i })).toBeVisible()

  // Host also sees "下一位 / Next Speaker" button
  await expect(page.getByRole('button', { name: /下一位 \/ Next Speaker/i })).toBeVisible()
})

// ── Test 4: Reveal Result stays visible (disabled) when host quit campaign ────
// Bug: when host was a candidate but quit during speech, Reveal Result disappeared
// because the button was hidden behind v-if="allVoted" and host can't vote.
// Fix: always show to host, just disable when !allVoted.

test('Reveal Result stays visible disabled when host quit campaign and others not voted', async ({
  page,
}) => {
  await goToSheriffSignup(page)

  // Jump to VOTING state where host (u1) quit campaign — canVote=false, allVoted=false
  await page.evaluate(() => (window as any).__debug.sheriffPhase('VOTING_HOST_QUIT'))
  await page.waitForTimeout(100)

  // Host sees "已放弃投票 / Vote forfeited" (can't vote)
  await expect(page.getByRole('button', { name: /已放弃投票 \/ Vote forfeited/i })).toBeVisible()

  // Reveal Result must be visible (not hidden) even though allVoted=false
  const revealBtn = page.getByRole('button', { name: /揭晓结果 \/ Reveal Result/i })
  await expect(revealBtn).toBeVisible()
  // It should be disabled because not everyone has voted yet
  await expect(revealBtn).toBeDisabled()
})

// ── Test 5: vote progress shows and Reveal Result enables after abstaining ────
// Bug: "Give Up Vote" had no UI effect; vote count wasn't shown; allVoted never true.
// Fix: backend counts abstained as voted; voteProgress shown; locked state after abstain.

test('vote count shows and Reveal Result enables after Give Up Vote', async ({ page }) => {
  await goToSheriffSignup(page)
  await page.evaluate(() => (window as any).__debug.sheriffPhase('VOTING'))
  await page.waitForTimeout(100)

  // Vote progress label shows X/Y voted
  await expect(page.getByText(/\d+\/\d+ voted/i)).toBeVisible()

  // Reveal Result is visible but disabled (not all voted)
  const revealBtn = page.getByRole('button', { name: /揭晓结果 \/ Reveal Result/i })
  await expect(revealBtn).toBeVisible()
  await expect(revealBtn).toBeDisabled()

  // Click "Give Up Vote"
  await page.getByRole('button', { name: /放弃投票 \/ Give Up Vote/i }).click()
  await page.waitForTimeout(100)

  // Locked state: "已弃票 / Abstained" shown, vote buttons gone
  await expect(page.getByText(/已弃票 \/ Abstained/i)).toBeVisible()
  await expect(page.getByRole('button', { name: /放弃投票 \/ Give Up Vote/i })).not.toBeVisible()

  // Reveal Result is now enabled
  await expect(revealBtn).toBeEnabled()
})

// ── Test 6: abstaining clears previously selected candidate ──────────────────
// Bug: after clicking Give Up Vote, the previously selected row stayed highlighted
// because election.myVote is undefined → (myVote ?? selectedId) still resolved to selectedId.

test('abstaining clears previously selected candidate highlight', async ({ page }) => {
  await goToSheriffSignup(page)
  await page.evaluate(() => (window as any).__debug.sheriffPhase('VOTING'))
  await page.waitForTimeout(100)

  // Select a candidate (Tom, u6)
  await page.locator('.vote-row').filter({ hasText: 'Tom' }).click()
  await page.waitForTimeout(100)

  // Verify Tom row is selected
  await expect(page.locator('.vote-row-selected')).toBeVisible()

  // Click Give Up Vote
  await page.getByRole('button', { name: /放弃投票 \/ Give Up Vote/i }).click()
  await page.waitForTimeout(100)

  // No row should be selected anymore
  await expect(page.locator('.vote-row-selected')).not.toBeVisible()
  // Locked abstain badge shown
  await expect(page.getByText(/已弃票 \/ Abstained/i)).toBeVisible()
})

// ── Test 7: cannot select self when voting as a candidate ────────────────────
// Bug: player could vote for themselves.
// Fix: self-row has vote-row-self class (cursor: not-allowed); click does nothing.

test('cannot select self when voting as a candidate', async ({ page }) => {
  await goToSheriffSignup(page)

  // Jump to VOTING state where u1 (host/me) is a running candidate
  await page.evaluate(() => (window as any).__debug.sheriffPhase('VOTING_WITH_HOST_CANDIDATE'))
  await page.waitForTimeout(100)

  // u1's row (nickname '我') should have .vote-row-self class
  const selfRow = page.locator('.vote-row-self')
  await expect(selfRow).toBeVisible()

  // Clicking the self row should NOT select it
  await selfRow.click()
  await page.waitForTimeout(100)

  // No selection state on the self row
  await expect(selfRow).not.toHaveClass(/vote-row-selected/)
  // Confirm Vote button stays disabled (nothing selected)
  await expect(page.getByRole('button', { name: /确认投票 \/ Confirm Vote/i })).toBeDisabled()
})

// ── Test 8: quit candidates must NOT appear in final result columns ────────────
// Bug: buildResult() iterates ALL candidates including QUIT ones, so speech-quitters
// appear in the result tally with 0 votes.
// Fix: filter to status === RUNNING before building tally.

test('quit candidate does not appear as a vote column in result', async ({ page }) => {
  await goToSheriffSignup(page)
  await page.evaluate(() => (window as any).__debug.sheriffPhase('RESULT'))
  await page.waitForTimeout(100)

  // RESULT screen should be shown
  await expect(page.getByText(/警长当选/i)).toBeVisible()

  // Running candidates (Tom, Alice, Bob) appear as their own vote columns
  await expect(page.locator('.vote-col-cname', { hasText: 'Tom' })).toBeVisible()
  await expect(page.locator('.vote-col-cname', { hasText: 'Alice' })).toBeVisible()
  await expect(page.locator('.vote-col-cname', { hasText: 'Bob' })).toBeVisible()

  // Eve quit during SPEECH — she must NOT appear as a vote column of her own,
  // but IS shown inside the "退出竞选" quit column
  await expect(page.locator('.vote-col-cname', { hasText: 'Eve' })).not.toBeVisible()
  await expect(page.locator('.vote-col-quit')).toBeVisible()
  await expect(page.locator('.vote-col-quit').getByText('Eve')).toBeVisible()
})
