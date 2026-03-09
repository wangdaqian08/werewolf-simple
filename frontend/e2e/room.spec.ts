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

// Lobby → nickname → "Create Room" → config → increment to 12 → "Create Room" → room
async function createRoomWith12Players(page: import('@playwright/test').Page) {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).first().click()
    await expect(page).toHaveURL(/\/create-room/)
    for (let i = 0; i < 3; i++) await page.getByRole('button', {name: '+'}).click()
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

// Clicks the first available (selectable) seat in the grid
async function pickFirstSeat(page: import('@playwright/test').Page) {
    await page.locator('.player-grid .slot-selectable').first().click()
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
    // Mock 9-player room: 8 guests (seats 1-8), host has no seat yet → 7 ready / 9 total
    // (host not counted until they pick a seat; Bob is NOT_READY)
    await expect(page.locator('.player-count').getByText(/7\s*\/\s*9/)).toBeVisible()
})

test('host view shows Start Game button', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeVisible()
})

test('Start Game is disabled when guests are NOT_READY', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeDisabled()
})

test('Start Game enables after host picks a seat and all guests become ready via STOMP', async ({page}) => {
    // Default 9-player room: host picks seat 9, allReady STOMP fires at 4s → canStart
    await createRoom(page)
    await page.locator('.player-grid .slot-selectable').first().click() // host picks seat
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

// ── Seat selection ────────────────────────────────────────────────────────────

test('Ready button is disabled until a seat number is picked', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeDisabled()
})

test('empty seats show selectable numbers in the grid', async ({page}) => {
    await joinRoom(page)
    await expect(page.locator('.player-grid .slot-selectable').first()).toBeVisible()
})

test('clicking an empty seat enables the Ready button', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeEnabled()
})

test('picked seat shows the player nickname', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    // Mock returns nickname "You" for the logged-in user regardless of what was typed
    await expect(page.locator('.player-grid').getByText('You')).toBeVisible()
})

test('occupied slots always show the seat number alongside the player', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    // The slot that now shows TestGuest should also contain a seat index label
    const mySlot = page.locator('.player-grid .slot-me')
    await expect(mySlot.locator('.slot-index')).toBeVisible()
})

test('player can change to a different empty seat before ready', async ({page}) => {
    await joinRoom(page)
    const grid = page.locator('.player-grid')

    // Pick the first available seat
    const firstSelectable = grid.locator('.slot-selectable').first()
    await firstSelectable.click()
    await expect(grid.getByText('You')).toBeVisible()

    // Pick a second different seat — should move there
    await grid.locator('.slot-selectable').first().click()
    // Player still appears in the grid (now in new slot)
    await expect(grid.getByText('You')).toBeVisible()
    // My slot is still present
    await expect(grid.locator('.slot-me')).toBeVisible()
})

test('after clicking Ready, empty slots are no longer selectable', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await expect(page.locator('.player-grid .slot-selectable')).toHaveCount(0)
})

test('after Undo Ready, empty slots become selectable again', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await page.getByRole('button', {name: /Undo Ready/i}).click()
    await expect(page.locator('.player-grid .slot-selectable').first()).toBeVisible()
})

// ── Ready toggle ──────────────────────────────────────────────────────────────

test('clicking Ready shows Undo Ready button', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await expect(page.getByRole('button', {name: /Undo Ready/i})).toBeVisible()
})

test('clicking Undo Ready switches back to Ready button', async ({page}) => {
    await joinRoom(page)
    await pickFirstSeat(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await page.getByRole('button', {name: /Undo Ready/i}).click()
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeVisible()
})

// ── Host seat selection ───────────────────────────────────────────────────────
// Uses 12-player room: 8 guests at seats 1–8, seats 9–12 empty, allReady suppressed.
// Host (seatIndex null) has 4 empty seats to freely pick and change between.

test('host sees selectable seats in the grid before picking a number', async ({page}) => {
    await createRoomWith12Players(page)
    await expect(page.locator('.player-grid .slot-selectable').first()).toBeVisible()
})

test('host can pick a seat number', async ({page}) => {
    await createRoomWith12Players(page)
    await pickFirstSeat(page)
    await expect(page.locator('.player-grid .slot-me-ready').getByText('You')).toBeVisible()
})

test('host can change to a different seat', async ({page}) => {
    await createRoomWith12Players(page)
    const grid = page.locator('.player-grid')

    // Pick first available seat (one of seats 9–12)
    await grid.locator('.slot-selectable').first().click()
    await expect(grid.getByText('You')).toBeVisible()

    // Change to the next empty seat — previous slot freed, host moves
    await grid.locator('.slot-selectable').first().click()
    await expect(grid.getByText('You')).toBeVisible()
    // Host occupies exactly one slot
    await expect(grid.locator('.slot-me-ready')).toHaveCount(1)
})

test('host seat remains changeable (no ready button locks it)', async ({page}) => {
    await createRoomWith12Players(page)
    await pickFirstSeat(page)

    // Host controls Start Game, not Ready — seat stays changeable until game starts
    await expect(page.locator('.player-grid .slot-selectable').first()).toBeVisible()
})

// ── Session persistence ───────────────────────────────────────────────────────

test('after create room, refresh still shows host view', async ({page}) => {
    await createRoom(page)
    const url = page.url()
    await page.reload()
    await expect(page).toHaveURL(url)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeVisible()
})
