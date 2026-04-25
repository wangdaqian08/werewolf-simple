---
name: prod-smoke-test
description: Use after deploying a werewolf-simple release tag (vX.Y.Z) to https://www.youplay123.online/, OR when the user says "run the prod smoke test" / "routine prod test". Also use when validating prod after a docker compose pull + up -d, or when investigating whether a production deploy is healthy end-to-end before declaring success.
---

# Prod smoke test — routine multi-device full-game flow

## What this skill does

Drives ONE complete werewolf game on production with three concurrent monitoring layers, so any stall, disconnect, or unhandled exception surfaces immediately rather than silently timing out. Pattern: **3 real human-controlled sessions** (user's phone, user's laptop, Claude's Chrome DevTools MCP browser) playing a 9-player CLASSIC game; the remaining 6 seats are bots driven from the prod VM via SSH. Reuses the existing `scripts/*.sh` family — no new scripts get written.

**Output is a clean go/no-go signal** on the deployed release. Re-run after every `vX.Y.Z` tag.

## Coordinates

| | |
|---|---|
| Prod URL | `https://www.youplay123.online/` |
| VM SSH wrapper | `scripts/vm-ssh.sh` (ControlMaster — fast repeated calls) |
| BACKEND_BASE on VM | `http://127.0.0.1:8080/api` |
| Default game | 9 players, sheriff OFF, roles: WEREWOLF + VILLAGER (required) + SEER + WITCH + GUARD; HUNTER + IDIOT off |
| Estimated wall-clock | 15–20 min total |

## Roster

| Seat | Player | Driver |
|---|---|---|
| ? | User nickname A | Phone Safari/Chrome |
| ? | User nickname B | Laptop browser |
| ? | Claude | Chrome DevTools MCP |
| ? × 6 | Bots | `scripts/*.sh` from VM via SSH |

Roles randomized — Claude reads layout via `roles.sh` after CONFIRM_ROLE.

## Pre-flight (always do this first)

All read-only:

```bash
gcloud auth list                                                      # active account check
./scripts/vm-ssh.sh 'curl -sf http://127.0.0.1:8080/api/health'        # backend up
./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod ps'
./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo git log -1 --oneline'   # confirm deployed tag
```

Expect `{"status":"UP"}`, all 3 containers Up, deployed commit matches the release tag we're verifying. If any check fails, abort the smoke test and investigate.

## Three monitoring layers (start ALL before the game begins)

### L1 — Backend log stream (persistent Monitor task)

Long-lived SSH that tails the backend container log on the VM with `--tail=0 -f` so we see only events emitted DURING the test. Filter is broad on purpose — every line is a potential failure signal.

Spawn via the `Monitor` tool with `persistent: true`:

```bash
gcloud compute ssh werewolf-server --zone=us-east1-d --command='sudo docker compose -f /opt/werewolf-simple/docker-compose.yml --env-file /opt/werewolf-simple/.env.prod logs --tail=0 -f backend 2>&1 | stdbuf -oL grep -E "action\.submit|PhaseChanged|Exception|ERROR|WARN.*werewolf|disconnect|STOMP|waitingOn=|GameOver|BadgeHandover|game\.state"'
```

`stdbuf -oL` is critical — without it, grep's stdout buffers and notifications batch into 4 KB chunks (~30 s of silence between updates).

### L2 — Claude's browser console (window.__appLog ring buffer)

After Claude joins the room AND after any reload of `/game/<id>`, inject the patcher from `audio-patcher.js` (this skill folder) via `evaluate_script`. It captures audio playback events, STOMP events, errors, and unhandled rejections in a 500-entry ring buffer.

To dump on demand: `evaluate_script` returning `window.__appLog`.

### L3 — User-side console (phone + laptop)

Claude can't introspect the user's real browsers. Ask the user to:
- **Laptop**: open Chrome DevTools (F12) → Console + Network → WS tab. Report red errors or `1006 abnormal closure` on the WebSocket.
- **Phone**: observe the in-app `[connection]` banner — frontend renders it on STOMP reconnect. If it appears mid-game, note it.

Silence = healthy.

## Setup walkthrough

### S1. Pre-flight (Claude)

Run all 4 pre-flight checks. Confirm green before going further.

### S2. Spawn L1 Monitor (Claude)

Persistent Monitor running the gcloud + grep command above.

### S3. User logs in on phone (User)

Open `https://www.youplay123.online/`, enter **nickname A**, tap Create Room, configure 9 players + sheriff OFF + default roles + classic win condition. Tap Create Room. **Paste the room code** + nickname A into chat.

### S4. Claude joins as Claude (Claude)

```
mcp__plugin_chrome-devtools-mcp_chrome-devtools__new_page url=https://www.youplay123.online/
fill nickname = "Claude"
fill room-code = <CODE>
click "加入 / Join"
```

Now on `/room/<id>`. Claim a seat: `evaluate_script` clicking `.slot-selectable`.

### S5. Inject L2 patcher (Claude)

Paste `audio-patcher.js` content into `evaluate_script`. Confirm `window.__appPatched === true`.

### S6. User logs in on laptop (User)

Open same URL, **nickname B**, **JOIN by room code** (NOT create). Claim a seat.

### S7. Claude fills 6 bots from VM (Claude)

```bash
./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/join-room.sh <CODE> 6 --ready'
```

Verify: 9/9 in room.

### S8. Each human readies up; host starts game

- Phone: tap Ready (host doesn't need to ready themselves on most flows).
- Laptop: tap Ready.
- Claude (MCP): click `准备 / Ready` button.
- Whoever created the room is host — they tap **Start Game**.

### S9. Inject host token to VM state file (Claude)

Lets `act.sh Host` work for host-only actions (REVEAL_NIGHT_RESULT, DAY_ADVANCE, etc.):

```bash
# Read host JWT from host browser's localStorage:
#   evaluate_script: localStorage.getItem('jwt')   (Claude's MCP if Claude is host)
#   OR ask user to paste from phone/laptop console: localStorage.getItem('jwt')
HOST_JWT="<paste here>"
./scripts/vm-ssh.sh "python3 -c 'import json; p=\"/tmp/werewolf-<CODE>.json\"; d=json.load(open(p)); d[\"hostToken\"]=\"$HOST_JWT\"; d[\"hostNick\"]=\"Host\"; json.dump(d,open(p,\"w\"))'"
```

If host JWT can't be obtained, fall back: have the host human click ALL host actions in their browser. Slower but works.

## Game flow (sub-phase by sub-phase)

For every sub-phase Claude:
1. **Polls** backend `/state` for the expected `nightPhase.subPhase`. Coroutine gap on prod is ~5 s — poll up to 10× at 1 s intervals.
2. **Fires** the corresponding action via the right driver (script for bots, MCP click for Claude, browser click instruction for user).
3. **Cross-references** L1 (`SUCCESS` log line) + L2 (audio sequence change) before moving to next.

### Night

| Sub-phase | Bot driver | Human role driver |
|---|---|---|
| `WEREWOLF_PICK` | `act.sh WOLF_KILL <bot> --target <seat-or-nick>` | Wolf clicks slot + Confirm in their UI (twice — once selects, once confirms after WOLF_SELECT broadcasts) |
| `SEER_PICK` / `SEER_RESULT` | `act.sh SEER_CHECK <bot> --target <bot-nick>` then `SEER_CONFIRM` | Seer clicks slot + Check; Confirm |
| `WITCH_ACT` | `act.sh WITCH_ACT <bot> --payload '{"useAntidote":false}'` | Witch chooses Save / Skip / Poison via UI |
| `GUARD_PICK` | `act.sh GUARD_SKIP <bot>` | Guard clicks slot + Protect, OR Skip |

**Important — `act.sh --target` ambiguity**: passing a bare number like `--target 1` matches "Bot1" (by name) before "seat 1" (by index). When the target is a HUMAN player, use the human's nickname or userId — don't rely on seat number. When the target is a bot, prefer the full bot nick (`Bot4-XXXX`) for unambiguity.

**Wolf-kill target**: prefer a non-wolf BOT seat to keep all 3 humans alive for full multi-device coverage. Killing a human is allowed but reduces remaining test surface.

**Wolf UI quirk** (Claude's MCP browser): clicking a player slot fires `WOLF_SELECT` (broadcast to teammates). Clicking the Confirm button fires `WOLF_KILL`. Two separate clicks; wait for the WOLF_SELECT SUCCESS in L1 before clicking Confirm.

### Day

1. Host (whoever created the room) clicks **"公布结果 / Reveal Night Result"** → backend `REVEAL_NIGHT_RESULT`.
2. Host clicks **"开始投票 / Start Vote"** → `DAY_ADVANCE` → `phase=DAY_VOTING, subPhase=VOTING`.
3. Each alive player votes:
   - Bots: `./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/act.sh SUBMIT_VOTE --target <seat-or-bot-nick> --room <CODE>'` (mass fan-out).
   - Each human: clicks vote target in their UI.
4. Host clicks **"公布结果 / Reveal Tally"** → `VOTING_REVEAL_TALLY` → eliminated player shown.
5. Host clicks **"继续 / Continue"** → next NIGHT.

If everyone abstains, backend transitions to `RE_VOTING` instead of `VOTE_RESULT`. Either fan-out a real target on the next round, or accept the revote loop.

### Termination

Game ends when wolves==0 (villager win) OR wolves≥non-wolves (wolf win). Frontend redirects all 3 human browsers to `/result/<id>` showing all role reveals.

## Known UI quirks (don't panic)

| Symptom | Explanation | Action |
|---|---|---|
| Claude's MCP browser shows blank `/game/<id>` after Start Game | SPA hydration race; pre-existing, not a regression | `navigate_page reload` once |
| Backend log: `WARN GameStateLogger - game=N ... -&gt; SKIP (game not found)` | `@Async` logger races the create-game transaction commit | Benign; ignore |
| Bot self-vote rejected: `Cannot vote for yourself` (sheriff election only) | Sheriff election forbids self-vote; day vote allows it | Have that bot abstain instead |
| `--target Peter` rejected `Target not found or dead` | `act.sh` doesn't know human nicks; sends literal "Peter" not `guest:peter` | Use a bot target instead, or pass full userId |

## Failure handling

If at any sub-phase the L1 stream shows a `REJECTED` or stays silent past the expected coroutine gap:

1. **Capture** (Claude):
   - L2: `evaluate_script` → dump `window.__appLog`.
   - L1: `./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod logs --tail=200 backend'`.
   - State: `./scripts/vm-ssh.sh "curl -s -H \"Authorization: Bearer <bot-jwt>\" http://127.0.0.1:8080/api/game/<id>/state"` — read `phase`, `subPhase`, `players[*].isAlive`. Backend's `waitingOn=[...]` is in the L1 logs, not the /state DTO.
2. **User report**: ask user what their phone / laptop UI shows.
3. **Decide**: workaround (fire missing action manually) or abort + reproduce locally.

Common patterns:
- `REJECTED reason="No active night phase"` → coroutine moved past sub-phase before the action; widen `poll` timeout or check whether sub-phase was already passed.
- `Sub.*disconnect` in STOMP log → device's WebSocket dropped. Frontend should auto-resync (PR #52). User can verify by reloading the page.
- `1006 abnormal closure` on user's WS panel → network hiccup. Expect resync within ~5 s.

## Verification — success criteria

1. Game reaches `/result/<id>` with all 9 role reveals visible on every human browser.
2. **Zero `Exception` / `ERROR` / `WARN.*werewolf` lines** in L1 during the run (the `GameStateLogger SKIP` is the one allowed exception — see quirks).
3. Phone and laptop state stayed in sync (user reports OK).
4. L2 audio: `wolf_open_eyes`, `wolf_close_eyes`, plus each special role's `*_open_eyes` / `*_close_eyes` per night, plus `rooster_crowing` + `day_time` at NIGHT→DAY transition. Each unique playback STARTS exactly once per night.
5. No STOMP disconnects in L2 buffer or L1 stream.
6. `docker compose ps` post-game still shows all containers Up.

## After the game — cleanup

- `TaskStop <monitor-id>` — stop the L1 stream.
- Optionally close Claude's MCP browser tab (or leave for next test).
- The `/tmp/werewolf-<CODE>.json` state file on the VM is fine to leave; next run uses a new room code.

## Files in this skill

- `SKILL.md` — this document
- `audio-patcher.js` — paste content into `evaluate_script` to install L2 logging
- `checklist.md` — condensed run sheet for fast re-runs

## Critical files (read-only reference)

| File | Purpose |
|---|---|
| `scripts/vm-ssh.sh` | Fast ControlMaster-backed SSH; reuse for every VM call |
| `scripts/join-room.sh` | Bot fill + ready (BACKEND_BASE-aware) |
| `scripts/act.sh` | All player actions including Host (after hostToken injection) |
| `scripts/roles.sh` | Pull bot role layout once roles confirmed |
| `backend/src/main/kotlin/com/werewolf/service/GameStateLogger.kt` | Source of `game.state … waitingOn=[...]` log line that L1 surfaces |
| `frontend/src/services/audioService.ts` | Audio queue (logs L2 captures) |
| `frontend/src/composables/useAudioService.ts` | AudioSequence watcher (L2) |
