---
id: ADR-007
title: E2E Testing Strategy
status: accepted
---

## Context

Werewolf has many phase transitions, per-role private events and action-gating rules. We need
automated coverage at three levels:

1. **Rule correctness** — actions accepted or rejected in the right sub-phase.
2. **Role isolation** — each player receives only the private info their role entitles them to.
3. **UI correctness** — the right screen is rendered for each phase and role.

## Decision

Two test layers, both in the frontend (`frontend/`), plus backend JUnit unit tests on the Kotlin
side. Intentionally no bespoke Spring "scenario fixture" layer — the real-backend Playwright
suite gives us integration-level coverage while exercising the same code paths real clients use.

### Layer 1 — Backend unit tests (Kotlin, JUnit 5)

Location: `backend/src/test/kotlin/com/werewolf/unit/`.

Covers role handlers, voting pipeline, tally calculator, night orchestrator helpers, and the
action dispatcher in isolation. Focus is on acceptance/rejection rules per sub-phase and on
state transitions, with mocked repositories.

Run: `cd backend && ./gradlew test`.

### Layer 2 — Frontend unit / component tests (Vitest)

Location: `frontend/src/__tests__/` and co-located `*.test.ts` files.

Covers Pinia stores, action dispatch wiring, and component rendering with mocked services.

Run: `cd frontend && npx vitest run`.

### Layer 3 — UI E2E with mocked backend (Playwright)

Config: `frontend/playwright.config.ts`. Specs: `frontend/e2e/*.spec.ts` (top level).

Uses the axios mock adapter in `frontend/src/mocks/` to simulate the backend. Covers view-layer
flows without requiring a running backend. Fast and deterministic.

Run: `cd frontend && npx playwright test`.

### Layer 4 — Real-backend E2E (Playwright, multi-browser)

Config: `frontend/playwright.real.config.ts`. Specs: `frontend/e2e/real/*.spec.ts`.

Each player runs in its own `BrowserContext` with its own STOMP connection. Tests drive bots via
shell helpers (`scripts/join-room.sh`, `scripts/act.sh`) and verify UI state, phase transitions,
and per-role private events end-to-end. This is the layer that catches backend/frontend
integration regressions.

Helpers live in `frontend/e2e/real/helpers/`:
- `assertions.ts` — backend-state snapshots + cross-browser phase assertions (with a CI timeout
  scale).
- `multi-browser.ts` — spawns N `BrowserContext`s and wires them to one room.
- `shell-runner.ts` — wraps `act.sh` with retry/logging for race-prone actions.
- `composite-screenshot.ts` — arranges per-player screenshots into a single image for regression
  review.

Key specs: `game-flow`, `flow-12p-sheriff`, `sheriff-flow`, `revote-flow`, `idiot-flow`,
`guard-audio-sequence`, `dead-role-audio`, `werewolf-win`.

Run: `cd frontend && npx playwright test --config=playwright.real.config.ts`.
Full regression (boots backend + DB + both CLASSIC and HARD_MODE):
`./scripts/run-e2e-full-flow.sh`.

CI runs Layer 4 as a 3-way sharded matrix — see `.github/workflows/ci.yml`.

## Scenario documentation

Scenarios in `docs/scenarios/` are the source of truth for what a given flow should do. Assertions
in the real-backend specs should be derivable from those docs.

## Consequences

- No separate "scenario fixture" DSL to maintain — assertions live next to the Playwright specs.
- CI wall-clock is dominated by Layer 4 (real-backend) due to STOMP timing and multi-browser
  coordination; sharding mitigates.
- Local-vs-CI env differences are real and sometimes subtle — see
  `memory/feedback_e2e_ci_vs_local.md`.
