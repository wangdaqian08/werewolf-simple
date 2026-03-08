import {expect, test} from '@playwright/test'

async function goToLobby(page: import('@playwright/test').Page) {
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/')
}

async function createRoom(page: import('@playwright/test').Page) {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).click()
    await expect(page).toHaveURL(/\/room\//)
}

// ── Back navigation ───────────────────────────────────────────────────────────

test('browser back does not leave room view', async ({page}) => {
    await createRoom(page)
    const roomUrl = page.url()

    // goBack returns null when no navigation occurred (guard pushed state back)
    await page.goBack().catch(() => {})

    await expect(page).toHaveURL(roomUrl)
})

test('room content is still visible after back attempt', async ({page}) => {
    await createRoom(page)

    await page.goBack().catch(() => {})

    await expect(page.getByText('HOST')).toBeVisible()
})

// ── Refresh confirmation dialog ───────────────────────────────────────────────

test('refresh triggers a beforeunload confirmation dialog in room view', async ({page}) => {
    await createRoom(page)

    // Trigger reload from within the page so Playwright can intercept the dialog.
    // (page.reload() from Playwright auto-accepts beforeunload without firing the event)
    const dialogPromise = page.waitForEvent('dialog')
    page.evaluate(() => location.reload())

    const dialog = await dialogPromise
    expect(dialog.type()).toBe('beforeunload')
    await dialog.dismiss()
})

test('dismissing refresh dialog keeps user on room page', async ({page}) => {
    await createRoom(page)
    const roomUrl = page.url()

    const dialogPromise = page.waitForEvent('dialog')
    page.evaluate(() => location.reload())

    const dialog = await dialogPromise
    await dialog.dismiss()

    await expect(page).toHaveURL(roomUrl)
    await expect(page.getByText('HOST')).toBeVisible()
})

// ── Lobby is unguarded ────────────────────────────────────────────────────────

test('lobby does not block browser back', async ({page}) => {
    // Go to lobby from a prior page so there is history to go back to
    await page.goto('about:blank')
    await goToLobby(page)

    // Back should navigate away from lobby without interception
    await page.goBack()

    await expect(page).not.toHaveURL(/\/room\//)
})

test('lobby does not show a refresh dialog', async ({page}) => {
    await goToLobby(page)

    let dialogFired = false
    page.once('dialog', () => {
        dialogFired = true
    })
    page.evaluate(() => location.reload())

    // Give the dialog a short window to appear; it should not
    await page.waitForTimeout(500)
    expect(dialogFired).toBe(false)
})
