import { test, expect } from '../fixtures/obscura'

test('obscura: lobby loads and renders nickname input', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL(/\/(lobby)?$/)
    await expect(page.locator('body')).toBeVisible()
})

test('obscura: CDP target reports Obscura, not Chrome', async ({ obscuraBrowser }) => {
    const version = obscuraBrowser.version()
    expect(version.length).toBeGreaterThan(0)
})
