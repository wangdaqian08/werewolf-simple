import { defineConfig } from '@playwright/test'

/**
 * Real-backend E2E config.
 *
 * Starts Spring Boot (e2e profile → H2 in-memory) + Vite (VITE_MOCK=false).
 * Run with: npx playwright test --config=playwright.real.config.ts
 */
export default defineConfig({
  testDir: './e2e/real',
  fullyParallel: false, // tests share one backend instance; avoid parallel state collisions
  retries: 0,
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
  },
  reporter: process.env.CI
    ? [['blob'], ['github']]
    : [['html', { open: 'never' }]],
  webServer: [
    {
      // Spring Boot with H2 in-memory via e2e profile
      command:
        'cd ../backend && SPRING_PROFILES_ACTIVE=e2e ./gradlew bootRun -q --console=plain',
      url: 'http://localhost:8080/api/health',
      timeout: 120_000,
      reuseExistingServer: true,
    },
    {
      // Vite dev server pointing at real backend (no mock)
      command: 'npm run dev -- --port 5174',
      url: 'http://localhost:5174',
      timeout: 30_000,
      reuseExistingServer: true,
      env: { VITE_MOCK: 'false' },
    },
  ],
})
