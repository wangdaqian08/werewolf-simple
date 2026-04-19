#!/usr/bin/env bash
# =============================================================================
# run-e2e-full-flow.sh — one-command 12-player sheriff end-to-end regression.
#
# What it runs:
#   1. Kills any existing backend / Vite / Playwright / Chromium on 8080 or 5174.
#   2. Hands control to Playwright which starts:
#        • Spring Boot with SPRING_PROFILES_ACTIVE=e2e (H2 in-memory, shrunk
#          werewolf.timing.* from application-e2e.yml so a full game runs in
#          ~3 min rather than ~15 min).
#        • Vite dev server with VITE_MOCK=false pointing at the real backend.
#   3. Executes the 12-player sheriff spec with BOTH scenarios:
#        • CLASSIC (easy-mode) full game flow
#        • HARD_MODE with badge-passover setup
#   4. Copies every captured screenshot into a timestamped evidence folder
#      under docs/e2e-evidence/.
#   5. Prints a pass/fail summary + prints the evidence path.
#
# Usage:
#   ./scripts/run-e2e-full-flow.sh                       # full flow
#   ./scripts/run-e2e-full-flow.sh --only-hard           # HARD_MODE only
#   ./scripts/run-e2e-full-flow.sh --only-classic        # CLASSIC only
#   ./scripts/run-e2e-full-flow.sh --keep-report         # open HTML report at end
#
# Exit codes:
#   0  — both scenarios reached /result with no test-failed artefacts.
#   1  — at least one scenario failed to reach /result (stall or assertion).
#   2  — environment problem (ports busy, gradle missing, etc.).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FRONTEND="$ROOT/frontend"
EVIDENCE_ROOT="$ROOT/docs/e2e-evidence"
TS="$(date +%Y-%m-%d_%H-%M-%S)"
EVIDENCE_DIR="$EVIDENCE_ROOT/run-$TS"

GREP=""
KEEP_REPORT=0
for arg in "$@"; do
  case "$arg" in
    --only-hard)    GREP="-g HARD_MODE wolf win" ;;
    --only-classic) GREP="-g CLASSIC villager win" ;;
    --keep-report)  KEEP_REPORT=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

# ── Step 1: free ports ─────────────────────────────────────────────────────
echo "==> Releasing ports 8080 and 5174 (if taken)..."
pkill -9 -f 'playwright.*test|chrome-headless|gradle.*bootRun|vite.*5174' 2>/dev/null || true
# Brief pause so OS releases sockets before Playwright's webServer starts them.
sleep 2

if lsof -i :8080 -P 2>/dev/null | grep -q LISTEN; then
  echo "!! port 8080 still in use — kill it manually and retry" >&2
  lsof -i :8080 -P 2>/dev/null | head -5 >&2
  exit 2
fi
if lsof -i :5174 -P 2>/dev/null | grep -q LISTEN; then
  echo "!! port 5174 still in use — kill it manually and retry" >&2
  lsof -i :5174 -P 2>/dev/null | head -5 >&2
  exit 2
fi

# ── Step 2: clean prior run artefacts ──────────────────────────────────────
echo "==> Cleaning prior test-results/..."
rm -rf "$FRONTEND/test-results/flow-12p-sheriff-"*
mkdir -p "$EVIDENCE_DIR"

# ── Step 3: run the spec (Playwright starts its own backend + vite) ────────
echo "==> Running flow-12p-sheriff.spec.ts against a fresh e2e-profile backend"
echo "    (you'll see a Gradle cold-start the first time — ~60s)"
cd "$FRONTEND"
# --workers=1 keeps the two scenarios sequential so they share the same backend
# without racing game IDs; --reporter=line keeps stdout scrollable.
PW_EXIT=0
if [[ -n "$GREP" ]]; then
  # shellcheck disable=SC2086
  npx playwright test flow-12p-sheriff.spec.ts \
    --config=playwright.real.config.ts \
    --reporter=line --workers=1 $GREP || PW_EXIT=$?
else
  npx playwright test flow-12p-sheriff.spec.ts \
    --config=playwright.real.config.ts \
    --reporter=line --workers=1 || PW_EXIT=$?
fi

# ── Step 4: copy evidence ──────────────────────────────────────────────────
echo "==> Archiving screenshots to $EVIDENCE_DIR"
CLASSIC_RESULT=""
HARD_RESULT=""
for dir in "$FRONTEND/test-results/flow-12p-sheriff-"*/; do
  [[ -d "$dir" ]] || continue
  cp "$dir"*.png "$EVIDENCE_DIR/" 2>/dev/null || true
  # Preserve any failure context for post-mortem
  if [[ -f "$dir/error-context.md" ]]; then
    name="$(basename "$dir")"
    cp "$dir/error-context.md" "$EVIDENCE_DIR/${name}.error-context.md"
  fi
done

# Detect which scenarios reached /result.
if ls "$EVIDENCE_DIR"/classic-99-*.png >/dev/null 2>&1; then
  CLASSIC_RESULT="REACHED-RESULT"
fi
if ls "$EVIDENCE_DIR"/hard-99-*.png >/dev/null 2>&1; then
  HARD_RESULT="REACHED-RESULT"
fi

# ── Step 5: tear down backend + vite (Playwright leaves them via reuseExistingServer) ──
echo "==> Stopping backend + Vite..."
pkill -9 -f 'playwright.*test|chrome-headless|gradle.*bootRun|vite.*5174' 2>/dev/null || true

# ── Step 6: summary ────────────────────────────────────────────────────────
echo
echo "======================================================================"
echo "  12-PLAYER SHERIFF E2E SUMMARY"
echo "======================================================================"
echo "  CLASSIC villager-win : ${CLASSIC_RESULT:-NOT-RUN}"
echo "  HARD_MODE wolf-win   : ${HARD_RESULT:-NOT-RUN}"
echo
echo "  Evidence : $EVIDENCE_DIR"
SHOT_COUNT="$(find "$EVIDENCE_DIR" -name '*.png' | wc -l | tr -d ' ')"
echo "  PNGs     : $SHOT_COUNT"
echo "  Playwright exit: $PW_EXIT"
echo "======================================================================"

if [[ "$KEEP_REPORT" == "1" ]]; then
  echo "==> Opening Playwright HTML report..."
  (cd "$FRONTEND" && npx playwright show-report) &
fi

# The playwright exit code is authoritative for regression gating.
if [[ "$PW_EXIT" -ne 0 ]]; then
  echo "!! One or more scenarios failed — see screenshots + error-context in evidence dir"
  exit 1
fi

echo "✓ both scenarios reached /result"
exit 0
