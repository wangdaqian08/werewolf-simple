import {defineConfig} from '@playwright/test'

export default defineConfig({
    testDir: './e2e',
    testIgnore: '**/real/**',
    workers: process.env.CI ? 2 : undefined,
    fullyParallel: true,
    retries: process.env.CI ? 1 : 0,
    use: {
        baseURL: 'http://localhost:5174',
        headless: true,
    },
    reporter: process.env.CI
        ? [['blob'], ['github']]
        : [['html', {open: 'never'}]],
    webServer: {
        command: 'npx vite --port 5174',
        url: 'http://localhost:5174',
        reuseExistingServer: !process.env.CI,
        env: {VITE_MOCK: 'true', VITE_MOCK_FAST: 'true'},
    },
})
