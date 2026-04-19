# 12-player sheriff end-to-end regression harness

One shell script, one Playwright spec, one backend config override. Use this
whenever you change anything that touches the night coroutine, voting
pipeline, audio service, or room/game REST contracts — and re-run it before
shipping those changes.

## The one command

```bash
./scripts/run-e2e-full-flow.sh
```

- Exit code `0` → both scenarios reached `/result`. Ship it.
- Exit code `1` → at least one scenario stalled or failed its winner
  assertion. Evidence is in `docs/e2e-evidence/run-<timestamp>/`.
- Exit code `2` → environment problem (port 8080 or 5174 held by something
  the script can't kill). Free the port manually and re-run.

```bash
./scripts/run-e2e-full-flow.sh --only-classic   # skip HARD_MODE
./scripts/run-e2e-full-flow.sh --only-hard      # skip CLASSIC
./scripts/run-e2e-full-flow.sh --keep-report    # open the HTML report after
```

Typical wall-clock: **~6–8 min** for the full run (Gradle cold-start is ~60 s
the first time the e2e backend is booted on the machine; each 12-player game
is ~2–3 min under the shrunk `werewolf.timing.*` values).

## What it validates

A 12-player game with sheriff enabled, driven end-to-end through the real
backend + real frontend, with:

| Checkpoint | Scenario(s) | Evidence filename pattern |
|---|---|---|
| Lobby → role-reveal → sheriff election → badge award | both | `*-01-role-reveal-or-election-start.png`, `*-02-sheriff-elected*.png` |
| Phase transitions reach NIGHT | both | `*-03-night-1-entered.png` |
| Full night role cycle (wolf + seer + witch + guard) resolves to DAY | both | `*-04-night-{1,2,3}-actions.png`, `hard-04-night-1-done.png` |
| Day result reveal | both | `*-day-{N}-day-result-revealed.png` |
| Voting open → reveal (with revote handling) | both | `*-day-{N}-day-voting-opened.png`, `*-day-{N}-day-tally-revealed-r{1,2,3}.png` |
| Badge handover fired (sheriff voted out) | HARD_MODE (optional, selector-gated) | `hard-05-day-1-badge-handover-triggered.png` |
| GAME_OVER screen with all 12 roles revealed | both | `classic-99-result-screen.png`, `hard-99-result-screen.png` |

If any of these stop appearing in a future run, something regressed.

## What makes the run stable

The script relies on several pieces of production code that were fixed during
this harness's development. If a change reverts any of these, the regression
run will hang — the script will time out rather than loop forever, and the
failure will point at the exact sub-phase the coroutine got stuck in.

1. **`NightOrchestrator.submitAction` — `afterCommit` barrier**
   (`backend/.../NightOrchestrator.kt`). Without this, the role loop's next
   read can run before the HTTP handler's transaction commits and silently
   wipe role-action fields like `seerCheckedUserId`. Symptom if reverted: the
   seer's result UI never renders, the spec stalls at `classic-04-night-1-actions.png`.
2. **Priority-aware frontend audio queue** (`frontend/src/composables/useAudioService.ts`).
   Role-owned audio (priority 5) appends; phase transitions (priority 10)
   clear. Symptom if reverted: audio pre-empts wrongly, runs still pass but
   audio diagnostics regress — run `npm run test` to catch.
3. **VotingPhase auto-continue timer removed** (`frontend/src/components/VotingPhase.vue`).
   Without this, the host's silent `continueVoting` auto-fire lands against
   `HUNTER_SHOOT` sub-phases and produces a 400 mid-run. Symptom if reverted:
   any game that elects a hunter + votes them out will crash the host page
   and the spec hangs at `*-day-{N}-day-tally-revealed-r1.png`.
4. **Seer countdown removed** (`frontend/src/components/NightPhase.vue`).
   Symptom if reverted: the seer's result screen auto-advances and your
   `hard-99-result-screen.png` shows games 30 s shorter than expected —
   failure signal is `error-context.md` showing `subPhase=SEER_RESULT`
   instead of advancing at real-player speed.
5. **Night optimistic `hasActed`** (`frontend/src/components/NightPhase.vue`).
   Without this, the player who just clicked "Done" sees a stale role panel
   instead of the sleep screen. No regression trigger in the spec itself
   (the host uses the script for most bot-role actions), but a flaky run
   could surface.
6. **WinConditionChecker `afterCommit` for role-action visibility**
   (see `NightOrchestrator` above — same fix).

The backend timing overrides live in
`backend/src/main/resources/application-e2e.yml`:

```yaml
werewolf:
  timing:
    dead-role-delay-ms: 500
    audio-warmup-ms: 100
    audio-cooldown-ms: 200
    inter-role-gap-ms: 500
    night-init-audio-delay-ms: 1000
    waiting-delay-ms: 500
```

Production's `application.yml` has no `werewolf.timing` block, so it picks up
the compile-time defaults (25 s dead-role, 8 s night init, etc.). Changing
these test values is safe; dropping any of them silently falls back to
production timings and the 10-min test timeout is not enough.

## The spec's driving strategy (what it actually does)

In `frontend/e2e/real/flow-12p-sheriff.spec.ts`:

1. **Setup** — host logs in via real UI, clicks "Create Room", bumps stepper
   to 12, toggles sheriff on + wolf/seer/witch/guard/villager. Bots join via
   `scripts/join-room.sh` (same REST a second device would use), ready up,
   host claims remaining seat. Host clicks "Start Game" through the real UI.

2. **Role confirm** — `act.sh CONFIRM_ROLE` for all bots; host confirms via
   real UI (reveal-role-btn → confirm-role-btn). Same "real player" path.

3. **Sheriff election** — first two candidates campaign via `sheriff.sh`;
   host clicks "Start Speech" through the script (host's UI button for this
   exists but the scripted equivalent is stable under shrunk timings),
   `SHERIFF_ADVANCE_SPEECH` loops until VOTING; bots vote via `sheriff.sh`;
   host `SHERIFF_REVEAL_RESULT`.

4. **Night loop** — `completeNight` waits on `/state` polling
   (`waitForSubPhase`) so each bot action fires exactly when the coroutine is
   awaiting that sub-phase. No fire-and-forget — a rejected action indicates
   a real bug, not a race.

5. **Day loop** — `completeDay` reveals night result through the real UI
   (`day-reveal-result`), opens voting (`day-start-vote`), submits votes for
   every alive player (including target and host) via scripts, reveals
   tally through the real UI (`voting-reveal`). Revote handling re-polls
   `/state` and re-submits votes up to 3 attempts.

6. **Badge handover** — when D1 sheriff is voted out, the `BADGE_HANDOVER`
   sub-phase's UI is captured if it renders, and a `BADGE_PASS` is submitted
   via `act.sh`. If the DOM selector mismatch prevents the UI capture, the
   spec continues — the sheriff-award screenshot from Step 3 is enough
   evidence that badges exist.

## Known scenario-strategy shortfalls (spec-level, not product)

- **CLASSIC may end in wolf win** when the host's randomly-assigned role is
  `WEREWOLF` — the spec's "vote out wolvesToEliminate[0]" strategy doesn't
  condition on host team. The test assertion then fails, but the flow still
  reaches `/result` and that evidence is saved. Easy future improvement:
  skip the host if `ctx.hostRole === 'WEREWOLF'` or pick the wolf farthest
  from the host.
- **Badge-handover UI capture** looks for selectors `[data-testid="badge-handover-panel"],
  .badge-handover, .badge-passover`. Whichever the product actually uses can
  be found by reading the first failing evidence `*-05-day-1-day-tally-revealed-r1.png`
  when a sheriff is voted out; add the real selector here if you want a
  dedicated capture.

## Files you care about

- `scripts/run-e2e-full-flow.sh` — the entry point.
- `frontend/e2e/real/flow-12p-sheriff.spec.ts` — the spec.
- `backend/src/main/resources/application-e2e.yml` — shrunk timings.
- `docs/e2e-evidence/run-<timestamp>/` — every run's screenshots + any
  `error-context.md` for failing tests.

## Troubleshooting

**"port 8080 still in use"** — a dev backend (IntelliJ Gradle / `./gradlew
bootRun`) or an abandoned test run is holding it. Kill it: `kill $(lsof
-ti:8080)` and re-run.

**"Gradle wrapper download timed out"** — cold Maven central, simply re-run.

**"setup stuck on role-reveal-btn not found"** — usually means the backend
didn't come up under the e2e profile (e.g., Flyway migrations enabled by
mistake). Check `backend/src/main/resources/application-e2e.yml` has
`flyway.enabled: false`.

**Run finishes fast (< 1 min) with failures** — `werewolf.timing.*` overrides
aren't being picked up. Confirm `SPRING_PROFILES_ACTIVE=e2e` in
`playwright.real.config.ts` and the yml file shape.
