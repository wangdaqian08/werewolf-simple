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

async function joinRoom(page: import('@playwright/test').Page) {
    await goToLobby(page)
    await page.getByPlaceholder('Enter your nickname').fill('TestGuest')
    await page.getByPlaceholder('Room code').fill('XYZ789')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page).toHaveURL(/\/room\//)
}

// ── Host view ─────────────────────────────────────────────────────────────────

test('create room shows HOST badge on own seat', async ({page}) => {
    await createRoom(page)
    await expect(page.getByText('HOST')).toBeVisible()
})

test('create room shows role configuration panel', async ({page}) => {
    await createRoom(page)
    await expect(page.getByText(/Role configuration panel/i)).toBeVisible()
})

test('create room shows Start Game button', async ({page}) => {
    await createRoom(page)
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeVisible()
})

test('Start Game is disabled when a guest is NOT_READY', async ({page}) => {
    await createRoom(page)
    // Carol (seat 4) starts as NOT_READY in mock data
    await expect(page.getByRole('button', {name: /Start Game/i})).toBeDisabled()
})

test('Start Game enables after all guests become ready via STOMP', async ({page}) => {
    await createRoom(page)
    // STOMP mock pushes Carol as READY after 3s
    await expect(page.getByRole('button', {name: /Start Game/i}))
        .toBeEnabled({timeout: 5000})
})

// ── Guest view ────────────────────────────────────────────────────────────────

test('join room does NOT show host controls', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByText('Role configuration panel')).not.toBeVisible()
    await expect(page.getByRole('button', {name: /Start Game/i})).not.toBeVisible()
})

test('join room shows Ready button', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByRole('button', {name: /^准备 \/ Ready$/})).toBeVisible()
})

test('join room shows the host seat with HOST badge', async ({page}) => {
    await joinRoom(page)
    await expect(page.getByText('HOST')).toBeVisible()
})

// ── Ready toggle ──────────────────────────────────────────────────────────────

test('clicking Ready shows Undo Ready button', async ({page}) => {
    await joinRoom(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    await expect(page.getByRole('button', {name: /Undo Ready/i})).toBeVisible()
})

test('clicking Ready marks own seat with ✓', async ({page}) => {
    await joinRoom(page)
    await page.getByRole('button', {name: /^准备 \/ Ready$/}).click()
    // Seat 2 is "You" in the guest room — find the ✓ badge on it
    const seat2 = page.locator('.player-slot').nth(1)
    await expect(seat2.getByText('✓')).toBeVisible()
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
    await expect(page.getByText('HOST')).toBeVisible()
})
