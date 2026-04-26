# Prod smoke test — quick checklist

Condensed run sheet for fast re-runs. See `SKILL.md` for full context.

## Pre-flight (~30 s)

- [ ] `gcloud auth list` — active account
- [ ] `./scripts/vm-ssh.sh 'curl -sf http://127.0.0.1:8080/api/health'` → `{"status":"UP"}`
- [ ] `./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod ps'` → 3 containers Up
- [ ] `./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo git log -1 --oneline'` → matches release tag

## Setup (~3 min)

- [ ] Spawn L1 Monitor (persistent, gcloud + grep on backend logs)
- [ ] User on **phone**: nickname A → Create Room (9p, sheriff OFF, default roles) → share code
- [ ] Claude: open MCP browser → join as `Claude` → claim seat
- [ ] Claude: inject `audio-patcher.js` via `evaluate_script` (L2)
- [ ] User on **laptop**: nickname B → JOIN by room code → claim seat
- [ ] Claude: `vm-ssh.sh 'cd /opt/werewolf-simple && BACKEND_BASE=http://127.0.0.1:8080/api ./scripts/join-room.sh <CODE> 6 --ready'`
- [ ] All 3 humans Ready up; host taps Start Game
- [ ] Claude: inject host JWT into `/tmp/werewolf-<CODE>.json` on VM (S9 in SKILL.md)

## Confirm role (~30 s)

- [ ] Each human reveals + confirms role in their browser
- [ ] Claude: `vm-ssh.sh ...act.sh CONFIRM_ROLE --room <CODE>` for bots
- [ ] Claude: `vm-ssh.sh ...roles.sh --room <CODE>` to log bot roles

## Each night (~1–2 min)

For each sub-phase: poll `/state` → fire action → confirm SUCCESS in L1 + audio in L2.

- [ ] `WEREWOLF_PICK` — wolves kill non-wolf bot (keeps humans alive)
- [ ] `SEER_PICK` → `SEER_RESULT` — seer checks; confirm
- [ ] `WITCH_ACT` — pass (`useAntidote:false`)
- [ ] `GUARD_PICK` — skip
- [ ] Backend transitions to `DAY_DISCUSSION/RESULT_HIDDEN`

## Each day (~1–2 min)

- [ ] Host: tap "公布结果 / Reveal Night Result"
- [ ] Host: tap "开始投票 / Start Vote"
- [ ] All alive vote (humans via UI, bots via `act.sh SUBMIT_VOTE` fan-out)
- [ ] Host: tap "公布结果 / Reveal Tally"
- [ ] Host: tap "继续 / Continue" → next NIGHT

Repeat until GAME_OVER.

## Verification

- [ ] All 3 human browsers on `/result/<id>` with all 9 role reveals
- [ ] L1: zero `Exception` / `ERROR` / `WARN.*werewolf` (the `GameStateLogger SKIP` race line is allowed)
- [ ] L2: no errors, audio fired in expected order each night
- [ ] User reports phone+laptop stayed in sync (no `1006 abnormal closure`, no stale phase)
- [ ] `docker compose ps` post-game shows all containers Up

## Cleanup

- [ ] `TaskStop <L1-monitor-id>`

## Time

- Pre-flight: ~30 s
- Setup: ~3 min
- 1 night + 1 day: ~3 min
- Average game: 2–3 night/day cycles → ~15 min total
