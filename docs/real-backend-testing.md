# Real Backend E2E Testing

Manual testing against the real Spring Boot backend + PostgreSQL database using Playwright browsers and bot helper scripts.

---

## Start the servers

### Backend (Spring Boot)

```bash
# from project root
./scripts/start-backend.sh
# listening on :8080
```

This script:
- Loads credentials from `backend/.env` (`POSTGRES_PASSWORD=werewolf`)
- Starts with `dev` profile: Flyway **drops and recreates all tables** on every start, OAuth2 stubbed
- Requires `werewolf-db` Docker container to be running

Verify it's up:

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

### Frontend (real mode — no mock)

```bash
cd frontend
npm run dev:real
# listening on :5173 (connects to backend on :8080)
```

> **Do not use `npm run dev`** — that starts mock mode on `:5174` where the backend is never called.

---

## API reference

All HTTP endpoints require `Authorization: Bearer <token>` except `/api/auth/*`.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/auth/dev` | Get a JWT by nickname (dev profile only) |
| `POST` | `/api/user/login` | Get a JWT by nickname (prod login) |
| `POST` | `/api/room/create` | Create a room → returns roomCode |
| `POST` | `/api/room/join` | Join by roomCode → returns roomId, config |
| `POST` | `/api/room/seat` | Claim a seat `{ seatIndex, roomId }` |
| `POST` | `/api/room/ready` | Set ready status `{ ready, roomId }` |
| `GET`  | `/api/room/{roomId}` | Room details + player list |
| `POST` | `/api/game/start` | Host starts the game `{ roomId }` |
| `POST` | `/api/game/action` | Send a game action `{ gameId, actionType, ... }` |
| `GET`  | `/api/game/{gameId}/state` | Full game state for a player |
| `GET`  | `/api/health` | Health check |

### Common action types (`POST /api/game/action`)

```json
{ "gameId": 3, "actionType": "CONFIRM_ROLE" }
{ "gameId": 3, "actionType": "START_NIGHT" }
```

---

## Step-by-step testing flow

### 1. Open browser contexts

Open at least 2 independent browser windows/contexts via Playwright or manually.

Navigate both to `http://localhost:5173/`.

### 2. Create a room (host browser)

1. Enter a host nickname
2. Click **Create Room** → arrives at the configure-room screen
3. Adjust settings (roles, sheriff toggle, player count)
4. Click **Create Room**
5. Note the **4-character room code** shown on the room screen (e.g. `AB3C`)

### 3. Join room (guest browser)

1. Enter a guest nickname
2. Enter the room code
3. Click **Join**

### 4. Select seats and ready up

- **Host**: select a seat (host is not required to click Ready)
- **Guest**: select a seat, then click **Ready**

### 5. Fill remaining seats with bots

```bash
./scripts/join-room.sh <ROOM_CODE> <N> --ready
# e.g. join 5 bots, all ready
./scripts/join-room.sh AB3C 5 --ready
```

Bot tokens are saved to `/tmp/werewolf-<ROOM_CODE>.json` for later use. Each bot entry includes `{ nick, token, seat, userId }` — `userId` is decoded from the JWT so it's immediately available for vote/action targeting without a state API call.

### 6. Get the host token

The host logged in via the browser. Their token is stored in the browser's localStorage.

Open DevTools in the **host browser** (`⌘+Option+J`), run:

```js
localStorage.getItem('jwt')
```

Copy the printed value. Then in your terminal:

```bash
HOST_TOKEN=<paste token here>
```

Keep this terminal open — you'll use `$HOST_TOKEN` for all host actions (reveal night result, advance day, reveal tally, etc.).

### 7. Host starts the game

Click **Start Game** in the host browser.

### 7.5. Confirm roles and identify which bot has each role

```bash
# All bots confirm their role card
./scripts/act.sh CONFIRM_ROLE

# Set GAME_ID from act.sh output (it prints "Game ID = N")
export GAME_ID=4   # replace 4 with the actual number

# Print seat → nickname → role mapping for all bots
./scripts/roles.sh
```

**Sample output:**
```
  seat  1  Bot-abc              WEREWOLF      ← use for WOLF_KILL / WOLF_SELECT
  seat  2  Bot-def              SEER          ← use for SEER_CHECK
  seat  3  Bot-ghi              WITCH         ← use for WITCH_ACT
  seat  4  Bot-jkl              GUARD         ← use for GUARD_PROTECT
  seat  5  Bot-mno              VILLAGER
  seat  7  Bot-stu              HUNTER        ← use for HUNTER_SHOOT
  seat  8  Bot-vwx              IDIOT         ← use for IDIOT_REVEAL
```

Store nicks as variables for the commands below:
```bash
WOLF_NICK="Bot-abc"
SEER_NICK="Bot-def"
WITCH_NICK="Bot-ghi"
GUARD_NICK="Bot-jkl"
```

### 8. Human players confirm their roles

In each human browser: click **Reveal Role** → **Got it**.

### Check phase at any time

```bash
./scripts/act.sh STATUS
# → Game 4  phase=NIGHT  subPhase=WEREWOLF_PICK  day=1  alive=8/8
```

---

## Complete NIGHT phase walkthrough

Night sub-phases run in strict order: **WEREWOLF_PICK → SEER_PICK → SEER_RESULT → WITCH_ACT → GUARD_PICK**

```bash
# Verify phase before starting
./scripts/act.sh STATUS

# ── 1. Wolves: pre-select target (STOMP broadcast to alive wolf teammates, no phase advance) ──
./scripts/act.sh WOLF_SELECT $WOLF_NICK --target <seat>

# ── 2. Wolves: confirm kill (advances to SEER_PICK) ──
./scripts/act.sh WOLF_KILL $WOLF_NICK --target <seat>

# Verify: should show SEER_PICK
./scripts/act.sh STATUS

# ── 3. Seer: check a player (advances to SEER_RESULT) ──
./scripts/act.sh SEER_CHECK $SEER_NICK --target <seat>

# ── 4. Seer: confirm result (advances to WITCH_ACT) ──
./scripts/act.sh SEER_CONFIRM $SEER_NICK

# Verify: should show WITCH_ACT
./scripts/act.sh STATUS

# ── 5. Witch: one of these options ──
# Save the wolf-kill victim (antidote — once per game):
./scripts/act.sh WITCH_ACT $WITCH_NICK --payload '{"useAntidote":true}'

# Skip antidote, poison a different player:
./scripts/act.sh WITCH_ACT $WITCH_NICK --payload '{"useAntidote":false,"poisonTargetUserId":"<userId>"}'

# Do nothing this night:
./scripts/act.sh WITCH_ACT $WITCH_NICK --payload '{"useAntidote":false}'

# Verify: should show GUARD_PICK
./scripts/act.sh STATUS

# ── 6. Guard: protect a player (or skip) ──
./scripts/act.sh GUARD_PROTECT $GUARD_NICK --target <seat>
# OR
./scripts/act.sh GUARD_SKIP $GUARD_NICK

# Night resolves → phase transitions to DAY
./scripts/act.sh STATUS
```

> **WITCH_ACT payload field names:**
> - `useAntidote` (boolean) — whether to save the wolf-kill victim
> - `poisonTargetUserId` (string) — userId of player to poison (**NOT** `poisonTargetId` or `targetId`)
> - Cannot use both antidote and poison on the same night
> - Cannot use antidote on night 2+ if it was used in a previous round

### WOLF_SELECT vs WOLF_KILL distinction

Multiple wolves can coordinate before committing:

```bash
# Wolf 1 proposes seat 3 (teammates see this in their UI immediately):
./scripts/act.sh WOLF_SELECT Wolf1 --target 3

# Wolf 2 changes to seat 5:
./scripts/act.sh WOLF_SELECT Wolf2 --target 5

# After discussion, Wolf 1 confirms seat 5 (final kill, advances sub-phase):
./scripts/act.sh WOLF_KILL Wolf1 --target 5

# Verify sub-phase advanced to SEER_PICK:
./scripts/act.sh STATUS
```

---

## Complete SHERIFF ELECTION walkthrough

Only present when room was created with `hasSheriff=true`.

Sub-phase sequence: **SIGNUP → SPEECH → VOTING → RESULT** (or **TIED** if votes tied)

```bash
# ── SIGNUP sub-phase ──
# Bots run for sheriff:
./scripts/act.sh SHERIFF_CAMPAIGN

# Or specific bot opts out:
./scripts/act.sh SHERIFF_PASS Bot2

# Optional: candidate drops out before speeches (SIGNUP phase):
./scripts/act.sh SHERIFF_QUIT <nick>

# Host: advance to SPEECH (if no candidates → goes directly to NIGHT):
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"SHERIFF_START_SPEECH\"}"

./scripts/act.sh STATUS   # should show subPhase=SPEECH

# ── SPEECH sub-phase ──
# Current speaker may quit their campaign during their speech:
./scripts/act.sh SHERIFF_QUIT_CAMPAIGN <current-speaker-nick>

# Host: advance to next speaker (repeat until all have spoken):
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"SHERIFF_ADVANCE_SPEECH\"}"

# ── VOTING sub-phase ──
# Players vote for a candidate (all bots vote for same candidate):
./scripts/act.sh SHERIFF_VOTE --target <candidate-seat>

# Individual bot abstains:
./scripts/act.sh SHERIFF_ABSTAIN Bot3

# Host: reveal result:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"SHERIFF_REVEAL_RESULT\"}"

./scripts/act.sh STATUS   # should show subPhase=RESULT or TIED

# ── If TIED sub-phase: host appoints from tied candidates ──
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"SHERIFF_APPOINT\",\"targetUserId\":\"<userId>\"}"
# OR via act.sh:
./scripts/act.sh SHERIFF_APPOINT --target <seat>

# ── RESULT sub-phase: host starts night ──
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"START_NIGHT\"}"

./scripts/act.sh STATUS   # should show phase=NIGHT
```

---

## Complete VOTING phase walkthrough (all sub-phases)

```bash
# ── Submit votes (dead bots auto-rejected) ──
./scripts/act.sh SUBMIT_VOTE --target <seat>

# Host reveals tally:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_REVEAL_TALLY\"}"

./scripts/act.sh STATUS   # check who was eliminated and subPhase

# ────────────────────────────────────────────────────────────────────
# CASE A: Normal elimination (no hunter, no sheriff badge)
# ────────────────────────────────────────────────────────────────────
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_CONTINUE\"}"

# ────────────────────────────────────────────────────────────────────
# CASE B: Hunter was eliminated → subPhase = HUNTER_SHOOT
# ────────────────────────────────────────────────────────────────────
./scripts/act.sh HUNTER_SHOOT $HUNTER_NICK --target <seat>
# OR hunter passes:
./scripts/act.sh HUNTER_PASS $HUNTER_NICK

# If hunter's target was NOT the sheriff → goes to night
# If hunter's target WAS the sheriff → subPhase = BADGE_HANDOVER (see Case C)

# ────────────────────────────────────────────────────────────────────
# CASE C: Sheriff was eliminated → subPhase = BADGE_HANDOVER
# ────────────────────────────────────────────────────────────────────
# Sheriff passes badge:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"BADGE_PASS\",\"targetUserId\":\"<aliveUserId>\"}"
# OR via act.sh:
./scripts/act.sh BADGE_PASS --target <seat>

# OR sheriff destroys badge:
./scripts/act.sh BADGE_DESTROY

# After badge transfer → proceed to night:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_CONTINUE\"}"

# ────────────────────────────────────────────────────────────────────
# CASE D: Idiot was voted out (first time) → idiot STAYS ALIVE
#          subPhase stays at VOTE_RESULT; idiot reveals identity
# ────────────────────────────────────────────────────────────────────
./scripts/act.sh IDIOT_REVEAL $IDIOT_NICK

# After reveal → host continues to night:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_CONTINUE\"}"

# ────────────────────────────────────────────────────────────────────
# CASE E: Tie vote → subPhase = RE_VOTING
# ────────────────────────────────────────────────────────────────────
./scripts/act.sh SUBMIT_VOTE --target <seat>   # revote
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_REVEAL_TALLY\"}"
# Second consecutive tie → automatically goes to night (no VOTING_CONTINUE needed)
```

### Hunter cascade: hunter shoots sheriff → badge handover

```bash
# Scenario: hunter (seat 2) voted out, sheriff at seat 4
# After VOTING_REVEAL_TALLY: subPhase = HUNTER_SHOOT
./scripts/act.sh HUNTER_SHOOT $HUNTER_NICK --target 4   # hunter shoots the sheriff

# subPhase automatically becomes BADGE_HANDOVER (seat 4 is now dead)
./scripts/act.sh BADGE_PASS --target <alive-seat>       # sheriff passes badge

# Then night:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_CONTINUE\"}"
```

---

## DAY phase

```bash
# Host reveals who died last night:
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"REVEAL_NIGHT_RESULT\"}"

# Host advances after speech round (moves to VOTING):
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"DAY_ADVANCE\"}"
```

---

## Win condition check

After any action that could end the game:

```bash
TOKEN=$(python3 -c 'import json,glob; print(json.load(open(glob.glob("/tmp/werewolf-*.json")[0]))["bots"][0]["token"])')
curl -s http://localhost:8080/api/game/$GAME_ID/state \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("phase="+str(d.get("phase")), "winner="+str(d.get("winner")))'
# GAME_OVER → phase=GAME_OVER, winner=WEREWOLF or VILLAGER
```

Or use:
```bash
./scripts/act.sh STATUS
# → Game 4  phase=GAME_OVER  subPhase=-  day=2  alive=3/8  winner=WEREWOLF
```

---

## View any player's UI

Each player (host or bot) has a JWT token. Paste it into a browser and that browser becomes that player.

**Step 1 — Get the token you want to watch**

For the host — already in your terminal from step 6:
```bash
echo $HOST_TOKEN
```

For a bot (from the state file):
```bash
python3 -c 'import json,glob; bots=json.load(open(glob.glob("/tmp/werewolf-*.json")[0]))["bots"]; [print(b["seat"], b["nick"], "\n", b["token"]) for b in bots]'
```

**Step 2 — Inject the token into a browser**

Open `http://localhost:5173`, open DevTools console (`⌘+Option+J`), run:

```js
localStorage.setItem('jwt', 'PASTE_FULL_TOKEN_HERE')
location.reload()
```

The page reloads as that player. All STOMP events arrive in real time as bots drive the game via scripts.

**Step 3 — Watch multiple players at once**

Each of these is a fully independent session (separate localStorage):

| Browser | How to open |
|---------|-------------|
| Chrome (normal) | regular window |
| Chrome Incognito | `⌘+Shift+N` |
| Firefox | normal window |
| Safari | normal window |
| Extra Chrome instance | `open -na "Google Chrome" --args --user-data-dir=/tmp/chrome-p2 --app=http://localhost:5173` |

Open one per player you want to watch, inject the matching token into each, then run `act.sh` commands — all browsers update live.

---

## curl testing with dev-login.sh

`dev-login.sh` gets and caches a dev JWT, eliminating repeated re-authentication in every command:

```bash
# First call: hits /api/auth/dev, caches to /tmp/werewolf-token-host.txt
HOST=$(./scripts/dev-login.sh Host)

# Subsequent calls: reads from cache (no network request)
HOST=$(./scripts/dev-login.sh Host)

# Force a fresh token (e.g. after backend restart)
HOST=$(./scripts/dev-login.sh Host --refresh)

# Clear all cached tokens
./scripts/dev-login.sh --clear
```

Combined with the `userId` in bot token files, a full action test looks like:

```bash
HOST=$(./scripts/dev-login.sh Host)
BOT1_ID=$(python3 -c "import json; print(json.load(open('/tmp/werewolf-AB3C.json'))['bots'][0]['userId'])")
BOT1=$(python3 -c "import json; print(json.load(open('/tmp/werewolf-AB3C.json'))['bots'][0]['token'])")

# Vote for Bot1 using their userId directly — no state API call needed
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BOT1" \
  -d "{\"gameId\":3,\"actionType\":\"SHERIFF_VOTE\",\"targetId\":\"$BOT1_ID\"}"
```

---

## No-sheriff flow

When the room is created with **Sheriff** disabled:

1. Follow steps 1–7 above
2. After all players confirm roles, the **host** sees a **"开始夜晚 / Start Night"** button
3. Host clicks it → all players see the Night phase (`WAITING` sub-phase)
4. Night proceeds without a sheriff election

---

## Edge case cookbook

These are the rejection cases every tester should verify. Each should return `"success": false` with the expected message.

### Night rejections

```bash
# Dead wolf tries to WOLF_KILL (should reject "dead"):
./scripts/act.sh WOLF_KILL <dead-wolf-nick> --target <seat>

# Villager tries SEER_CHECK (should reject "seer"):
./scripts/act.sh SEER_CHECK <villager-nick> --target <seat>

# Witch uses antidote on night 2 when already used (should reject "Antidote already used"):
./scripts/act.sh WITCH_ACT $WITCH_NICK --payload '{"useAntidote":true}'

# Witch tries antidote + poison same night (should reject "antidote and poison"):
./scripts/act.sh WITCH_ACT $WITCH_NICK --payload '{"useAntidote":true,"poisonTargetUserId":"<userId>"}'

# Guard protects same target two nights in a row (should reject "same player two nights"):
./scripts/act.sh GUARD_PROTECT $GUARD_NICK --target <same-seat-as-yesterday>

# WITCH_ACT without --payload (caught by act.sh before sending):
./scripts/act.sh WITCH_ACT $WITCH_NICK
```

### Voting rejections

```bash
# Dead bot tries to vote (should reject "dead" or "cannot vote"):
./scripts/act.sh SUBMIT_VOTE <dead-nick> --target <seat>

# Vote twice in same round (first succeeds; second should reject "Already voted"):
./scripts/act.sh SUBMIT_VOTE Bot1 --target <seat>
./scripts/act.sh SUBMIT_VOTE Bot1 --target <seat>

# Idiot who already revealed tries to vote (should reject "voting right"):
./scripts/act.sh SUBMIT_VOTE $IDIOT_NICK --target <seat>

# Non-host sends VOTING_REVEAL_TALLY (should reject):
BOT_TOKEN=$(python3 -c 'import json,glob; print(json.load(open(glob.glob("/tmp/werewolf-*.json")[0]))["bots"][0]["token"])')
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_REVEAL_TALLY\"}"
# Expected: {"success":false,"message":"..."}
```

### Phase mismatch rejections

```bash
# Send WOLF_KILL during DAY phase (should reject):
./scripts/act.sh WOLF_KILL $WOLF_NICK --target <seat>

# Non-host sends DAY_ADVANCE (should reject):
BOT_TOKEN=<any-bot-token>
curl -s -X POST http://localhost:8080/api/game/action \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"DAY_ADVANCE\"}"
```

| Scenario | Expected rejection message |
|---|---|
| Dead wolf tries WOLF_KILL | contains "dead" |
| Villager tries SEER_CHECK | contains "seer" (not the seer) |
| Witch antidote used twice | contains "Antidote already used" |
| Witch antidote + poison same night | contains "antidote and poison" |
| Guard protects same target twice | contains "same player two nights" |
| Vote while dead | contains "Dead" or "cannot vote" |
| Idiot votes after reveal | contains "voting right" |
| Vote twice same round | contains "Already voted" |
| Non-host VOTING_REVEAL_TALLY | rejected |
| Non-host DAY_ADVANCE | rejected |
| WOLF_KILL during DAY phase | rejected |

---

## Run E2E tests against the real backend

```bash
cd frontend
npm run test:e2e:real
```

Config: `frontend/playwright.real.config.ts`

---

## Notes

- **Port 5173** = real app (connects to backend). **Port 5174** = mock-only dev server (`npm run dev`). Do not confuse them.
- Re-running `join-room.sh` after a game has started issues new tokens for new user IDs; the original `/tmp/werewolf-<ROOM_CODE>.json` tokens are what the confirm script uses — do not overwrite them.
- `POST /api/auth/dev` only works when Spring Boot is started with profile `dev` (`--spring.profiles.active=dev` or `SPRING_PROFILES_ACTIVE=dev`).
- `$GAME_ID` must be set (`export GAME_ID=N`) before running any `curl` commands that reference it directly. `act.sh` auto-detects it on every run.
