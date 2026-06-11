import { defineConfig } from '@playwright/test'

/**
 * Stripe payment E2E config — REAL Stripe sandbox + real hosted checkout.
 *
 * Deliberately NOT part of the PR-blocking e2e-integration shards: it
 * depends on checkout.stripe.com and stripe-cli webhook forwarding, so it
 * runs nightly / on demand via .github/workflows/payment-e2e.yml.
 *
 * Run locally with: npm run test:e2e:payment
 * (requires stripe-cli and STRIPE_SANDBOX_SECRET in the environment —
 * the npm script wraps scripts/run-payment-e2e.sh which starts the
 * webhook forwarder and exports STRIPE_WEBHOOK_SECRET.)
 */
export default defineConfig({
  testDir: './e2e/payment',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 180_000,
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
  reporter: process.env.CI
    ? [['html', { open: 'never' }], ['github']]
    : [['html', { open: 'never' }]],
  webServer: [
    {
      // Profile order matters: dev first (activates DevAuthController so the
      // spec can mint a non-guest buyer), e2e LAST so its H2 datasource and
      // flyway-off settings win over dev's postgres/flyway settings.
      // Dummy GOOGLE_* values keep dev's oauth2 placeholders resolvable
      // (the e2e profile excludes the OAuth2 client auto-config anyway).
      command:
        "bash -c 'cd ../backend && SPRING_PROFILES_ACTIVE=dev,e2e GOOGLE_CLIENT_ID=dummy GOOGLE_CLIENT_SECRET=dummy STRIPE_SECRET_KEY=$STRIPE_SANDBOX_SECRET STRIPE_WEBHOOK_SECRET=$STRIPE_WEBHOOK_SECRET FRONTEND_BASE_URL=http://localhost:5174 ./gradlew bootRun -q --console=plain 2>&1 | tee /tmp/werewolf-payment-backend.log'",
      url: 'http://localhost:8080/api/health',
      timeout: 120_000,
      // Never reuse: a backend booted by the regular real config lacks the
      // Stripe env and would fail the run in a confusing way.
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --port 5174',
      url: 'http://localhost:5174',
      timeout: 30_000,
      reuseExistingServer: true,
      env: { VITE_MOCK: 'false' },
    },
  ],
})
