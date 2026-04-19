import {defineConfig} from '@playwright/test'

/**
 * Real-backend E2E config.
 *
 * Starts Spring Boot (e2e profile → H2 in-memory) + Vite (VITE_MOCK=false).
 * Run with: npx playwright test --config=playwright.real.config.ts
 */
export default defineConfig({
  testDir: './e2e/real',
  fullyParallel: false, // tests share one backend instance; avoid parallel state collisions
  // CI: retry once to absorb timing-sensitive NIGHT→DAY phase-transition flakes
  // that pass locally on fast hardware but occasionally stall on slower GH
  // runners. Local runs keep retries: 0 so flakes aren't hidden.
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
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
