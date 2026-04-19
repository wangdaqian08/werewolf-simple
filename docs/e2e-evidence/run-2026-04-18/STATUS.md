# 12-player sheriff E2E — evidence run 2026-04-18

## What was asked

Full-game Playwright flow (12 players + sheriff + badge passover) covering:
- Villager winner (CLASSIC)
- Wolf winner (HARD_MODE)
- Screenshots per phase per role
- Host driving every UI decision via real clicks (no shortcuts)
- Document where the run gets stuck

## What was delivered

- New spec: `frontend/e2e/real/flow-12p-sheriff.spec.ts` — both scenarios, real-UI host, bots via REST.
- New backend profile override: `backend/src/main/resources/application-e2e.yml`
  now sets `werewolf.timing.*` to sub-second values so a full 12-player game
  completes in ~2 min rather than ~15 min. Production `application.yml` is
  untouched.
- Screenshots captured (CLASSIC, Run 2 after fixes):
  - `classic-01-role-reveal-or-election-start.png` — role reveal phase, all 12 players seated.
  - `classic-02-sheriff-elected.png` — sheriff election completed, badge awarded (Bot8 ⭐).
  - `classic-03-night-1-entered.png` — night 1 began (host transition verified).
  - `classic-04-night-1-actions.png` — immediately after bots submitted wolf/seer/witch/guard actions.
  - `classic-04-day-1-day-result-revealed.png`
  - `classic-04-day-1-day-voting-opened.png`
  - `classic-04-day-1-day-tally-revealed.png`

Files are in this directory (`docs/e2e-evidence/run-2026-04-18/`).

## Where it stuck

All three day-1 screenshots are byte-identical (34 057 bytes) — the host's
page never actually advances past the night resolution. The flow reached:

1. **Working**: lobby + 12-player seating + sheriff election + badge award.
   The `classic-02-sheriff-elected.png` clearly shows the ⭐ on the elected
   bot's avatar, so badge award works end-to-end via the real UI.
2. **Working**: host click on "Start Night" transitions all browsers to NIGHT
   phase. `classic-03-night-1-entered.png` is a genuine night UI on the host.
3. **Blocking**: night never resolves to day. Symptom — day-reveal-result
   button never becomes visible, so my 30 s wait for it times out and the
   following three `captureSnapshot` calls take the same unchanged frame.

Root cause (best reading): the multi-role bot actions fired by `completeNight`
race against the backend role loop's sub-phase progression. Bot SEER_CHECK /
WITCH_ACT / GUARD_SKIP are submitted faster than the coroutine can move the
sub-phase forward, so each one is rejected by the role handler with
"Not in X sub-phase" and the role loop then waits forever for the real action
that never comes. Only the first action (WOLF_KILL) lands because the sub-phase
is already correct when it arrives. Subsequent actions never get retried by
the spec.

The existing `werewolf-win.spec.ts` (9p, no sheriff) uses the same pattern
and succeeds because it has only a single wolf kill in the critical path and
the other roles are skipped/dead in later rounds. At 12p with a full role
roster the race is consistently lost.

## What the fix looks like

Replace fire-and-forget bot actions with per-sub-phase polling:

```
for each active sub-phase in order:
  poll backend state (GET /api/game/{id}/state as bot) until .nightPhase.subPhase === expected
  fire the matching bot action
```

This is the shape `NightOrchestrator`'s own integration tests use
(`waitForNightSubPhaseReady` in `NightPhaseDeadRoleIntegrationTest.kt`). The
Playwright helper needs the same: a small `waitForSubPhase(gameId, target)`
that hits `/api/game/{id}/state` with the host's JWT and polls. Then the
spec's `completeNight` becomes:

```
await waitForSubPhase(ctx, 'WEREWOLF_PICK'); tryAct('WOLF_KILL', ...)
await waitForSubPhase(ctx, 'SEER_PICK');     tryAct('SEER_CHECK', ...)
await waitForSubPhase(ctx, 'SEER_RESULT');   tryAct('SEER_CONFIRM', ...)
await waitForSubPhase(ctx, 'WITCH_ACT');     tryAct('WITCH_ACT', ...)
await waitForSubPhase(ctx, 'GUARD_PICK');    tryAct('GUARD_SKIP', ...)
```

## What still needs doing to complete the task as specified

1. Add the `waitForSubPhase(ctx, subPhase)` helper.
2. Refactor `completeNight` in `flow-12p-sheriff.spec.ts` to use it.
3. Re-run. Expected total wall-clock with e2e timings: ~3 min for CLASSIC, ~3 min for HARD_MODE.
4. After CLASSIC green, the HARD_MODE scenario's badge-passover still needs
   its own exercising click — I left `BADGE_PASS` via bot + host-UI destroy
   fallback in `completeDay`. That's reachable only when D1 votes out the
   elected sheriff, which the spec already sets up (targetSeat = seer's seat).

## Processes
All background test processes have been terminated (`pkill -9 -f
'playwright|chrome-headless|gradle.*bootRun|vite.*5174'`). Ports 8080 and
5174 are free.

## Config state
- `backend/src/main/resources/application-e2e.yml` — shrunk timings **kept**
  (harmless in prod since prod uses application.yml which has no timing block).
- `frontend/e2e/real/flow-12p-sheriff.spec.ts` — new spec kept. Needs the
  `waitForSubPhase` refactor described above before it will complete both
  scenarios.
- All other production code unchanged from the earlier session's green state
  (WinConditionChecker fix, audio-queue priority handling, frontend seer
  countdown removal, VotingPhase auto-continue removal).
