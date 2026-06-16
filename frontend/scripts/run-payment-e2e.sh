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
# set -e does not propagate into $(...), so check explicitly after the capture.
STRIPE_WEBHOOK_SECRET="$(stripe listen --print-secret)"
if [ -z "$STRIPE_WEBHOOK_SECRET" ]; then
  echo "stripe listen --print-secret returned empty — is STRIPE_API_KEY valid?" >&2
  exit 1
fi
export STRIPE_WEBHOOK_SECRET

stripe listen --forward-to localhost:8080/api/payment/webhook >/tmp/stripe-listen.log 2>&1 &
LISTEN_PID=$!
# NOTE: a SIGKILL bypasses this trap; recover leaked forwarders with: pkill -f "stripe listen"
trap 'kill "$LISTEN_PID" 2>/dev/null || true' EXIT

# Wait for the forwarder tunnel before handing off to Playwright.
# stripe-cli prints "Ready!" once the websocket to Stripe is established.
for _ in $(seq 1 60); do
  grep -q "Ready!" /tmp/stripe-listen.log 2>/dev/null && break
  sleep 0.5
done
if ! grep -q "Ready!" /tmp/stripe-listen.log 2>/dev/null; then
  echo "stripe listen did not become ready within 30s — see /tmp/stripe-listen.log" >&2
  exit 1
fi

npx playwright test --config=playwright.payment.config.ts "$@"
