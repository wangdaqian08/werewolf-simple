---
name: write-real-e2e-test
description: Use when creating, editing, or debugging any spec under frontend/e2e/real/. Encodes the CI-vs-local pitfalls that repeatedly break this suite so you design around them up front.
---

# Writing Real-Backend E2E Tests

You are about to touch a real-backend Playwright spec. **This suite has a history of passing locally and failing in CI.** Every correction has been absorbed into a checklist so we stop paying for it twice.

## Step 1 — Read the memory first (non-negotiable)

Before writing or editing a line, read:

- `/Users/dq/.claude/projects/-Users-dq-workspace-werewolf-simple/memory/feedback_e2e_ci_vs_local.md`

It enumerates 8 specific CI-vs-local differences. If any of them apply, your design must handle them upfront. Do not decide that a difference "probably won't matter here" — that is the exact reasoning that produced the current quarantine list.

Also check:

- `/Users/dq/.claude/projects/-Users-dq-workspace-werewolf-simple/memory/project_quarantined_e2e_tests.md` — so you do not accidentally re-introduce a pattern we already know is broken.

## Step 2 — Understand the code before asserting

Never assume an action/sub-phase name. Verify against:

- `backend/src/main/kotlin/com/werewolf/model/Enums.kt` — `ActionType`, `NightSubPhase`, `VotingSubPhase`, `ElectionSubPhase`, `DaySubPhase`, `GamePhase`
- `backend/src/main/kotlin/com/werewolf/game/night/NightOrchestrator.kt` — real night sub-phase sequence
- The relevant `*Handler.kt` under `backend/src/main/kotlin/com/werewolf/game/role/` — accepted actions per sub-phase, rejection reasons

If you cannot point to the line that proves your assertion holds, the assertion is a guess — do not write it.

## Step 3 — Use the existing helpers (don't reinvent)

`frontend/e2e/real/helpers/`:

- `assertions.ts` — `verifyAllBrowsersPhase`, `snapshotBackendState`, `TIMEOUT_SCALE` (CI-aware). Use these for phase checks; do not write raw `waitForTimeout` calls.
- `multi-browser.ts` — `createPlayerContexts(...)`. Always isolate per-player contexts; never share storage.
- `shell-runner.ts` — `act(...)` with retry/log. Prefer this over raw `execSync` so rejections surface in the report.
- `composite-screenshot.ts` — capture per-player screenshots in one image when debugging a phase-specific failure.

The shell helpers under `scripts/act.sh` drive bots. When the host needs to fire an action, pass the host nickname explicitly — `PLAYER_SEL=all` is the default fan-out which has bitten us before.

## Step 4 — Gate on sub-phase, not on time

When waiting for a phase/sub-phase transition, poll `/api/game/{id}/state` via `waitForSubPhase(hostPage, gameId, target, timeoutMs)` rather than sleeping. Use `TIMEOUT_SCALE` from `assertions.ts` for CI-friendly timeouts (CI is ~2× slower locally).

`verifyAllBrowsersPhase` already appends a backend-state snapshot on failure — leave that in. It turns "flaky test" into "here is exactly what the DB looked like when we gave up."

## Step 5 — Minimal per-iteration logging

If your test has a loop (revote, multi-night), log one structured line per iteration:

```
[feature] iter=N elapsed=Ns ui={...} backend={phase, subPhase, aliveCount}
```

This diff-reads CI failures in seconds instead of running a local bisect.

## Step 6 — Run locally before pushing

```
cd frontend && npx playwright test --config=playwright.real.config.ts <your-spec>
```

If it passes locally, run it **twice** before pushing. Flake surfaces on the second run half the time.

For full regression before big changes:

```
./scripts/run-e2e-full-flow.sh
```

## Step 7 — If you have to skip

Quarantine must update `memory/project_quarantined_e2e_tests.md`. A `test.skip()` with no memory entry is how we end up rediscovering the same bug.

## Non-goals

- Do not mock the backend in this suite. If a mock is called for, the spec belongs under `frontend/e2e/` (mocked config), not `frontend/e2e/real/`.
- Do not assert on the felt duration of a phase. Only assert sub-phase state transitions. Timing asserts are the single largest source of CI flake in this repo.
