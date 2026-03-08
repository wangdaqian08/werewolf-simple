import {expect, test} from '@playwright/test'

async function goToLobby(page: import('@playwright/test').Page) {
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/')
}

// Lobby → fill nickname → "Create Room" → config screen → "Create Room" → waiting room
async function createRoom(page: import('@playwright/test').Page) {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await expect(page).toHaveURL(/\/create-room/)
    await page.getByRole('button', {name: /Create Room/i}).click()
    await expect(page).toHaveURL(/\/room\//)
}

async function joinRoom(page: import('@playwright/test').Page) {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestGuest')
    await page.getByPlaceholder('Room code').fill('XYZ789')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page).toHaveURL(/\/room\//)
}

// ── Config screen ─────────────────────────────────────────────────────────────

test('Create Room navigates to config screen', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await expect(page).toHaveURL(/\/create-room/)
})

test('config screen shows player count stepper defaulting to 9', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await expect(page.getByText('9')).toBeVisible()
})

test('increment button increases player count', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await page.getByRole('button', {name: '+'}).click()
    await expect(page.getByText('10')).toBeVisible()
})

test('decrement button decreases player count', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await page.getByRole('button', {name: '−'}).click()
    await expect(page.getByText('8')).toBeVisible()
})

test('decrement is disabled at minimum (6)', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    // Click down to 6
    for (let i = 0; i < 3; i++) await page.getByRole('button', {name: '−'}).click()
    await expect(page.getByRole('button', {name: '−'})).toBeDisabled()
})

test('increment is disabled at maximum (12)', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    for (let i = 0; i < 3; i++) await page.getByRole('button', {name: '+'}).click()
    await expect(page.getByRole('button', {name: '+'})).toBeDisabled()
})

test('config screen shows Werewolf and Villager as REQUIRED', async ({page}) => {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await expect(page.getByText('REQUIRED').first()).toBeVisible()
})

// ── Host waiting room ─────────────────────────────────────────────────────────

test('waiting room shows room code', async ({page}) => {
    await createRoom(page)
    await expect(page.getByText('ABC123')).toBeVisible()
})

test('waiting room shows player count', async ({page}) => {
    await createRoom(page)
    // Config screen defaults to 9; mock fills all 9 seats
    await expect(page.getByText(/9\s*\/\s*9/)).toBeVisible()
})

test('host view shows Start Game button', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeVisible()
})

test('Start Game is disabled when guests are NOT_READY', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeDisabled()
})

test('Start Game enables after all guests become ready via STOMP', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i}))
        .toBeEnabled({timeout: 6000})
})

// ── Guest waiting room ────────────────────────────────────────────────────────

test('join room does NOT show Start Game button', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).not.toBeVisible()
})

test('join room shows room code', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByText('XYZ789')).toBeVisible()
})

test('join room shows Ready button', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeVisible()
})

// ── Ready toggle ──────────────────────────────────────────────────────────────

test('clicking Ready shows Undo Ready button', async ({page}) => {
    await joinRoom(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await expect(page.getByRole('button', {name: /Undo Ready/i})).toBeVisible()
})

test('clicking Undo Ready switches back to Ready button', async ({page}) => {
    await joinRoom(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await page.getByRole('button', {name: /Undo Ready/i}).click()
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeVisible()
})

// ── Session persistence ───────────────────────────────────────────────────────

test('after create room, refresh still shows host view', async ({page}) => {
    await createRoom(page)
    const url = page.url()
    await page.reload()
    await expect(page).toHaveURL(url)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeVisible()
})
