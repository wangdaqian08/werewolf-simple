import {expect, test} from '@playwright/test'

test.beforeEach(async ({page}) => {
    await page.goto('/')
    await page.evaluate(() => localStorage.clear())
    await page.goto('/')
})

test('Create Room button is disabled with no nickname', async ({page}) => {
    const btn = page.getByRole('button', {name: /Create Room/i})
    await expect(btn).toBeDisabled()
})

test('Create Room button is disabled with whitespace-only nickname', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('   ')
    const btn = page.getByRole('button', {name: /Create Room/i})
    await expect(btn).toBeDisabled()
})

test('Create Room button enables once nickname is typed', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Alice')
    const btn = page.getByRole('button', {name: /Create Room/i})
    await expect(btn).toBeEnabled()
})

test('Join button is disabled with nickname but no room code', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Alice')
    const btn = page.getByRole('button', {name: /Join/i})
    await expect(btn).toBeDisabled()
})

test('Join button is disabled with room code but no nickname', async ({page}) => {
    await page.getByPlaceholder('Room code').fill('ABC123')
    const btn = page.getByRole('button', {name: /Join/i})
    await expect(btn).toBeDisabled()
})

test('Join button enables when both nickname and room code are entered', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Alice')
    await page.getByPlaceholder('Room code').fill('ABC123')
    const btn = page.getByRole('button', {name: /Join/i})
    await expect(btn).toBeEnabled()
})

test('Create Room navigates to config screen', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('TestHost')
    await page.getByRole('button', {name: /Create Room/i}).click()
    await expect(page).toHaveURL(/\/create-room/)
})

test('Join Room navigates to room view', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('TestGuest')
    await page.getByPlaceholder('Room code').fill('XYZ789')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page).toHaveURL(/\/room\//)
})

test('successful join stores JWT in localStorage', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Alice')
    await page.getByPlaceholder('Room code').fill('XYZ789')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page).toHaveURL(/\/room\//)
    const jwt = await page.evaluate(() => localStorage.getItem('jwt'))
    expect(jwt).not.toBeNull()
    const userId = await page.evaluate(() => localStorage.getItem('userId'))
    expect(userId).not.toBeNull()
    const nickname = await page.evaluate(() => localStorage.getItem('nickname'))
    expect(nickname).not.toBeNull()
})

test('invalid room code shows error message', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Alice')
    await page.getByPlaceholder('Room code').fill('BADCD')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page.getByText('Room not found. Check the code.')).toBeVisible()
    await expect(page).toHaveURL('/')
})

test('invalid room code does not navigate away', async ({page}) => {
    await page.getByPlaceholder('Enter your nickname').fill('Bob')
    await page.getByPlaceholder('Room code').fill('WRONG')
    await page.getByRole('button', {name: /Join/i}).click()
    await expect(page).not.toHaveURL(/\/room\//)
})
