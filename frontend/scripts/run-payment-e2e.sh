#!/usr/bin/env bash
# Stripe payment E2E launcher: starts the webhook forwarder, then Playwright.
# Requires: stripe-cli on PATH, STRIPE_SANDBOX_SECRET in the environment.
set -euo pipefail

: "${STRIPE_SANDBOX_SECRET:?export STRIPE_SANDBOX_SECRET (Stripe sandbox secret key) first}"
case "$STRIPE_SANDBOX_SECRET" in
  sk_test_* | rk_test_*) ;;
  *)
    echo "STRIPE_SANDBOX_SECRET is not a test-mode key — refusing to run" >&2
    exit 1
    ;;
esac

# stripe-cli auths via env — no `stripe login` browser dance needed.
export STRIPE_API_KEY="$STRIPE_SANDBOX_SECRET"

# --print-secret returns the same whsec the subsequent `listen` signs with.
STRIPE_WEBHOOK_SECRET="$(stripe listen --print-secret)"
export STRIPE_WEBHOOK_SECRET

stripe listen --forward-to localhost:8080/api/payment/webhook >/tmp/stripe-listen.log 2>&1 &
LISTEN_PID=$!
trap 'kill "$LISTEN_PID" 2>/dev/null || true' EXIT

npx playwright test --config=playwright.payment.config.ts "$@"
