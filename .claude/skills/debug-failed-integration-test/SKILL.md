---
name: debug-failed-integration-test
description: Use when an integration / E2E test is failing or quarantined (test.skip) in werewolf-simple — especially anything under frontend/e2e/real/ or any GitHub Action job that flips red on a PR. Encodes the evidence-driven reproduce → log → root-cause → DOM-driven fix → CI-verify workflow proven on PRs #67, #68, #69. Reach for this BEFORE typing the words "flake", "timeout", "race condition", or "resource pressure".
---

# Debugging a Failed Integration Test — werewolf-simple

You are looking at a red E2E test (or a quarantined one you've been asked to un-skip). **The Iron Law of this skill: never declare the cause until you can point at a specific log line and a specific code path.** Every "flake" callout in this repo's history has, on inspection, been a real product bug.

The same loop produced PR #67 (CLASSIC sheriff), #68 (HARD_MODE wolf-win), and #69 (audio queue clear). Use it.

## Step 0 — Stop. Read these first.

- `/Users/dq/.claude/projects/-Users-dq-workspace-werewolf-simple/memory/feedback_no_timeout_handwave.md` — the rule that bans hand-waving "flake/timeout" without log evidence. Re-read every time, even if you "remember it".
- `/Users/dq/.claude/projects/-Users-dq-workspace-werewolf-simple/memory/feedback_e2e_ci_vs_local.md` — 8 specific CI-vs-local differences. Likely the failure mode is one of these.
- `/Users/dq/.claude/projects/-Users-dq-workspace-werewolf-simple/memory/project_quarantined_e2e_tests.md` — current quarantine list. Don't accidentally re-fix something already fixed; conversely, if the test you're touching is named here, this skill plus the case studies below are how you get it green.
- The skill `write-real-e2e-test` (also in `.claude/skills/`) — design rules for any spec edit you end up making.

If you skipped any of these, restart the loop.

## The 6-step playbook

### 1. Reproduce locally before anything else

Failing CI run → run the same spec on your laptop FIRST. The harness boots Spring Boot in `e2e` profile and Vite in real-backend mode automatically.

```bash
cd frontend
: > /tmp/werewolf-e2e-backend.log   # truncate so the next run is clean
npx playwright test --config=playwright.real.config.ts -g "<spec name fragment>" --reporter=line 2>&1 | tee /tmp/run-1.log | tail -60
```

- Use `-g "<fragment>"` so only the failing test runs (a single Integration shard otherwise takes 5-10 min).
- The config has `reuseExistingServer: true`; if the backend is already up but on the wrong profile, kill it (or rely on `/tmp/werewolf-e2e-backend.log` showing the active profile in the banner).
- If the test passes locally on the first attempt but fails in CI, your reproduction is incomplete — re-run with `process.env.CI=1` (the assertions and helper polls scale 2× under CI), or check `feedback_e2e_ci_vs_local.md` for the specific axis you missed.

### 2. Read both ends of the log

Two log streams must match the symptom.

**Backend log** — `/tmp/werewolf-e2e-backend.log`. Mirror of the Spring Boot stdout. Search by game id, sub-phase, or rejection reason:

```bash
grep -E "PhaseChanged|REJECTED|game.state|action.submit user=" /tmp/werewolf-e2e-backend.log | grep "game=N" | head -30
```

`GameStateLogger` emits one line per state change with the form `game.state game=N ctx=… phase=X subPhase=Y day=D alive=A/T waitingOn=[…]`. That timeline is the source of truth — if you cannot explain the failure as a sequence of these lines, you do not yet have the root cause.

**Frontend / Playwright log** — `/tmp/run-1.log` (the redirect from step 1). `[tryAct rejected]` lines give the action, target, and rejection reason. Trace screenshots are listed by attachment number and live under `frontend/test-results/<spec>/attachments/`.

A useful triangulation is the **raw page state** — `state?.<phase>?.subPhase` polled via `hostPage.evaluate` against `/api/game/{id}/state`. Many helpers already do this (`waitForSubPhase`, `readAlivePlayerIds`); when in doubt, write a one-shot eval rather than guessing what the page shows.

Common failure shapes:

| Symptom | Where it lives in the log |
|---|---|
| Action stalls forever | Backend `REJECTED reason="..."` immediately after the action.submit line |
| UI button never enables | Backend `subPhase=` shows the page is not in the expected sub-phase |
| Game stuck mid-day/night | Look for a sub-phase that never transitions — `waitingOn` field shows who the backend is waiting on |
| Audio assertion fails | Browser console (`hostPage.on('console', …)` capture) — search `Starting playback`, `clearQueue`, `AudioSequence changed` |

### 3. Trace to the specific code path

Once you have the rejection / mismatched sub-phase, walk back to the source.

- `backend/src/main/kotlin/com/werewolf/model/Enums.kt` defines every ActionType + sub-phase string. **Verify spelling** — silent rejections include "Unknown action 'FOO'".
- `backend/src/main/kotlin/com/werewolf/game/role/<Role>Handler.kt` lists which actions each sub-phase accepts plus rejection messages.
- `backend/src/main/kotlin/com/werewolf/game/voting/VotingPipeline.kt`, `phase/GamePhasePipeline.kt`, `night/NightOrchestrator.kt` show the broadcast / commit ordering. Most "fast-CI race" bugs in this repo have been broadcast-before-commit — fix template is `TransactionSynchronizationManager.registerSynchronization { afterCommit { stompPublisher.broadcastGame(...) } }`.

If you cite a backend file in your fix, **read the surrounding 30+ lines** — these pipelines pass mutable `GameContext` and order matters.

### 4. Fix DOM-first; never paper over symptoms

When the fix is in test code:

- **Use `getByTestId`, never `getByText`.** If the testid is missing, add one to the component (it's product code that improves accessibility too). See `feedback_use_testid_not_text.md`.
- **Drive actions through the page that a real user would.** Pass-badge belongs on the eliminated sheriff's browser (the only page with `isEliminatedSheriff=true`). Win-condition belongs on the host's CreateRoom toggle. Don't reach for the REST API to bypass UI logic.
- **Never lengthen `waitForTimeout` to "fix" timing.** Wait for an effect: a sub-phase change (`waitForSubPhase`), a DOM testid (`waitFor({ state: 'visible' })`), or a sentinel log event polled in a loop with a budget. Blanket `waitForTimeout(10_000)` is the same anti-pattern as "flake".

When the fix is in product code:

- The bug is real. Treat it as a regression and lock it in with a vitest unit test. PR #69 added one for `audioService.isQueueActive()` for this exact reason.
- Match style: existing files use the patterns; if you're adding a public method, mirror neighbour signatures and JSDoc.

### 5. Verify locally — at least 3 stable runs

```bash
for i in 1 2 3; do : > /tmp/werewolf-e2e-backend.log && npx playwright test --config=playwright.real.config.ts -g "<spec>" --reporter=line 2>&1 | tail -3; done
```

Plus:

```bash
npx vue-tsc --noEmit          # zero output, exit 0
npx vitest run --silent | tail # all unit tests still pass
```

Three consecutive passes is the minimum bar before pushing. One pass and one fail is "still flaky"; you go back to step 1.

### 6. Commit, raise PR, monitor CI

Branch + commit are surgical: stage only the files you changed, never `git add -A` (untracked `frontend/blob-report/` and ad-hoc docs would leak in). Keep the commit message structured around the evidence:

```
<type>(<scope>): <one-line summary>

<reproduce + log evidence — actual log lines, with timestamps>

Root cause: <pointer to file:line and the exact mechanism>

Fix:
  * <file> — <what changed and why>
  * <file> — <what changed and why>

Verification:
  * vue-tsc --noEmit clean
  * vitest run NN/NN pass (added M new regression tests)
  * Playwright real-backend: K/K stability runs at ~Xs each

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Then push, `gh pr create`, and **monitor with the Monitor tool**, not by manually polling:

```bash
prev=""
while true; do
  cur=$(gh pr checks <PR#> 2>/dev/null || true)
  diff_out=$(diff <(printf '%s\n' "$prev") <(printf '%s\n' "$cur") | grep '^>' | sed 's/^> //')
  [ -n "$diff_out" ] && printf '%s\n' "$diff_out"
  prev="$cur"
  pending=$(printf '%s\n' "$cur" | awk -F'\t' '{print $2}' | grep -E 'pending|in_progress' || true)
  [ -z "$pending" ] && { printf 'ALL CHECKS COMPLETE\n'; exit 0; }
  sleep 90
done
```

The integration shards run after backend builds — total wall clock is usually 10-20 min. Don't sleep-poll in the foreground; arm Monitor and keep working.

## Words to avoid in your first response

If your first reaction to a failure is "this is flaky" or "it's a CI-only timeout", **delete that sentence and start over at step 1.** Use the actual cause once you have it: "broadcast-before-commit on dayAdvance" (PR #67), "BADGE_HANDOVER never resolves because handler used non-existent selectors" (PR #68), "high-priority clearQueue races role audio" (PR #69). Specific causes have specific fixes; "flake" has no fix.

## Case studies — three examples of this loop end-to-end

Read the merged PR diffs + commit messages for the worked examples:

- **PR #67 — CLASSIC sheriff election** ([github.com/wangdaqian08/werewolf-simple/pull/67](https://github.com/wangdaqian08/werewolf-simple/pull/67))
  - Symptom: shard 1 game-flow test 7 stuck on `voting-reveal` button.
  - Evidence: `action.submit ... SUBMIT_VOTE -> REJECTED reason="Not in voting phase"` 2.6s after `dayAdvance` SUCCESS.
  - Root cause: STOMP `PhaseChanged` broadcast fired *before* the dayAdvance UPDATE committed. Frontend reacted, fired SUBMIT_VOTE, backend re-loaded GameContext, saw stale phase.
  - Fix: wrap broadcast in `TransactionSynchronizationManager.registerSynchronization(...) { afterCommit { ... } }` in `GamePhasePipeline.dayAdvance`. Plus diagnostic warn-log in `VotingPipeline.submitVote` so the next regression is one log line away.

- **PR #68 — HARD_MODE wolf-win with badge passover** ([github.com/wangdaqian08/werewolf-simple/pull/68](https://github.com/wangdaqian08/werewolf-simple/pull/68))
  - Symptom: spec hits 6-round budget without reaching `/result`.
  - Evidence: backend log shows the game parks at `subPhase=BADGE_HANDOVER` from D1 onwards; every later `WOLF_KILL` returns `REJECTED reason="No active night phase"`.
  - Root cause: (a) `act('SET_WIN_CONDITION', ...)` was a no-op — no such ActionType — so the room ran in CLASSIC despite the test claiming HARD_MODE. (b) The badge-handover handler in `completeDay` looked for selectors that don't render on the host's page; pass-badge button only renders where `isEliminatedSheriff` is true (the eliminated sheriff's own browser).
  - Fix: DOM toggle on CreateRoomView for HARD_MODE; new testids on the badge UI; pass-badge driven via the eliminated sheriff's `ctx.pages.get('SEER')`; deterministic kill plan that drives the POST_VOTE wolf-win logical branch.

- **PR #69 — guard audio sequence regression** ([github.com/wangdaqian08/werewolf-simple/pull/69](https://github.com/wangdaqian08/werewolf-simple/pull/69))
  - Symptom: `guard_close_eyes.mp3 played 0 times, expected 1`.
  - Evidence: browser console capture shows `[useAudioService] High-priority sequence — clearing queue` immediately after `guard_close_eyes` was queued but before it started playback.
  - Root cause: `useAudioService.ts:46-52` unconditionally called `audioService.clearQueue()` for any priority>=10 sequence, dropping the queued role-narrative audio.
  - Fix: new `audioService.isQueueActive()`; in the composable, only clear when the queue is idle, otherwise append. Preserves narrative integrity. Spec's 3s blanket wait replaced with a poll on the last sentinel (`rooster_crowing`) since the queue now drains in order. Added a vitest contract test for the active-queue append path.

These are the templates. When the next failing test lands on your desk, the loop is the same; only the specific root cause is different.

## Red flags — STOP if you catch yourself doing any of these

- Suggesting a fix before you've reproduced the failure locally.
- Citing "flake", "timeout", "race condition", or "resource pressure" as a root cause without a backend log line that proves it.
- Reaching for `act.sh` REST shortcuts when a real player would click a button.
- Lengthening a `waitForTimeout` instead of waiting for an effect.
- `getByText` instead of `getByTestId`.
- `git add -A` or `git commit -am`. Stage explicit files only.
- Claiming a fix is verified after one local run.
- Telling the user "should be fixed" without monitoring CI to green.

If you find yourself in any of these, restart at step 0.
