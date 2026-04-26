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
  // workers: 1 is REQUIRED — fullyParallel:false alone only serializes
  // tests WITHIN a spec file. Without workers:1, multiple spec files run
  // concurrently within a shard. Each spec's setupGame POSTs
  // /api/user/login {nickname:"Host"} → userId="guest:host". Two
  // simultaneous requests both pass findById (race window before either
  // commits), both INSERT, second one fails with H2 unique-constraint
  // violation → 500. The frontend's handleCreateRoom catches the error,
  // sets `error.value` ("Request failed with status code 500"), and
  // does NOT call router.push('/create-room'). The test's
  // `await waitForURL(/\/create-room/, { timeout: 30_000 })` then sits
  // for 30 s and times out. Verified by adding INFO logging to
  // AuthService.loginOrRegister and observing two concurrent calls in
  // the same millisecond on different exec threads.
  workers: 1,
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
      // Spring Boot with H2 in-memory via e2e profile.
      //
      // `tee` mirrors backend stdout to /tmp/werewolf-e2e-backend.log so the
      // afterEach hook in helpers/composite-screenshot.ts can attach the
      // tail of the log to any failed test in the Playwright HTML report.
      // Without this, CI failures like "stuck on NIGHT/WEREWOLF_PICK"
      // surface only the frontend assertion — no visibility into what the
      // backend coroutine actually did, which actions it accepted or
      // rejected, or where it got stuck.
      command:
        "bash -c 'cd ../backend && SPRING_PROFILES_ACTIVE=e2e ./gradlew bootRun -q --console=plain 2>&1 | tee /tmp/werewolf-e2e-backend.log'",
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
