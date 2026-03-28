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

### 6. Host starts the game

Click **Start Game** in the host browser.

### 7. Confirm roles for bots

```bash
./scripts/confirm-role.sh <GAME_ID> <ROOM_CODE>
# GAME_ID is the numeric ID in the browser URL: /game/:gameId
./scripts/confirm-role.sh 3 AB3C
```

> If the script reports `skipped: Not in ROLE_REVEAL phase`, the game has already advanced.
> Check whether `hasSheriff=true` and all players confirmed — this auto-transitions to `SHERIFF_ELECTION`.

### 8. Human players confirm their roles

In each human browser: click **Reveal Role** → **Got it**.

### 9. View a bot's perspective (optional)

To switch a human browser to a specific bot's session:

```bash
./scripts/as-bot.sh <ROOM_CODE> <INDEX>
# INDEX is 0-based (first bot = 0)
./scripts/as-bot.sh AB3C 0
```

Paste the printed token into the browser's localStorage under `token`.

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
