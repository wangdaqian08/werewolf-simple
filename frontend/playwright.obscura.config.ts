import { defineConfig } from '@playwright/test'

export default defineConfig({
    testDir: './e2e/obscura',
    workers: 1,
    fullyParallel: false,
    retries: 0,
    use: {
        baseURL: 'http://localhost:5174',
    },
    reporter: [['list']],
    webServer: {
        command: 'npx vite --port 5174',
        url: 'http://localhost:5174',
        reuseExistingServer: true,
        env: { VITE_MOCK: 'true', VITE_MOCK_FAST: 'true' },
    },
})
