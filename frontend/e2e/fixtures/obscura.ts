import { test as base, chromium, type Browser } from '@playwright/test'

const DEFAULT_ENDPOINT = 'http://127.0.0.1:9222'

export const test = base.extend<{}, { obscuraBrowser: Browser }>({
    obscuraBrowser: [
        async ({}, use) => {
            const endpoint = process.env.OBSCURA_CDP ?? DEFAULT_ENDPOINT
            const browser = await chromium.connectOverCDP(endpoint)
            await use(browser)
            await browser.close()
        },
        { scope: 'worker' },
    ],

    page: async ({ obscuraBrowser }, use) => {
        const context = await obscuraBrowser.newContext({ baseURL: 'http://localhost:5174' })
        const page = await context.newPage()
        await use(page)
        await context.close()
    },
})

export { expect } from '@playwright/test'
