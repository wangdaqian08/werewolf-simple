import {defineConfig} from '@playwright/test'

export default defineConfig({
    testDir: './e2e',
    workers: process.env.CI ? 2 : undefined,
    fullyParallel: true,
    retries: process.env.CI ? 1 : 0,
    use: {
        baseURL: 'http://localhost:5173',
        headless: true,
    },
    reporter: process.env.CI
        ? [['blob'], ['github']]
        : [['html', {open: 'never'}]],
    webServer: {
        command: 'npm run dev',
        url: 'http://localhost:5173',
        reuseExistingServer: !process.env.CI,
        env: {VITE_MOCK_FAST: 'true'},
    },
})
