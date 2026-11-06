import { expect, type Page, test } from '@playwright/test'

// A modal is teleported to <body>. When its content fits, a swipe on it used to
// scroll the page behind it (the document was the only scroller in the chain).
test.use({ viewport: { width: 417, height: 614 }, isMobile: true, hasTouch: true })

async function createRoom(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('TestHost')
  await page.getByRole('button', { name: /創建房間|Create Room/i }).first().click()
  await expect(page).toHaveURL(/\/create-room/)
  await page.getByRole('button', { name: /創建房間|Create Room/i }).click()
  await page.getByTestId('room-setup-done').click()
  await expect(page.getByTestId('room-code')).toBeVisible()
}

/** A real touch swipe (Chromium CDP), finger moving up by `dy` px. */
async function swipeUp(page: Page, x: number, y: number, dy: number) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Input.synthesizeScrollGesture', {
    x,
    y,
    yDistance: -dy,
    gestureSourceType: 'touch',
    speed: 800,
  })
  await cdp.detach()
}

const scrollY = (page: Page) => page.evaluate(() => Math.round(window.scrollY))

test('a swipe on the role sheet does not scroll the room page, and closing restores the position', async ({
  page,
}) => {
  await createRoom(page)
  const chip = page.getByTestId('role-composition').locator('button').first()
  await chip.scrollIntoViewIfNeeded()
  const before = await scrollY(page)

  await chip.click()
  const sheet = page.getByTestId('role-card-sheet')
  await expect(sheet).toBeVisible()
  const box = await page.getByTestId('role-card-desc').boundingBox()
  if (!box) throw new Error('role-card-desc has no box')

  await swipeUp(page, box.x + box.width / 2, box.y + box.height / 2, 250)
  await swipeUp(page, 10, 600, 250) // the dimmed backdrop

  await page.getByTestId('role-card-close').click()
  await expect(sheet).toHaveCount(0)
  await expect.poll(() => scrollY(page)).toBe(before)
})
