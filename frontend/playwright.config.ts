import {defineConfig} from '@playwright/test'

export default defineConfig({
    testDir: './e2e',
    use: {
        baseURL: 'http://localhost:5173',
        headless: true,
    },
    // Dev server must already be running — start with `npm run dev`
    webServer: {
        command: 'npm run dev',
        url: 'http://localhost:5173',
        reuseExistingServer: true,
    },
})
