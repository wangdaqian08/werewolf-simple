# Werewolf (狼人杀) — Local Development Guide

Vue 3 + Spring Boot + PostgreSQL multiplayer game.

---

## Prerequisites

| Tool           | Version | Install                                                       |
|----------------|---------|---------------------------------------------------------------|
| Java           | 17+     | `brew install openjdk@17`                                     |
| Node           | 18+     | `brew install node`                                           |
| Docker Desktop | any     | [docker.com](https://www.docker.com/products/docker-desktop/) |

---

## First-time setup

Copy the env template and fill in your credentials:

```bash
cp backend/.env.example backend/.env
# edit backend/.env — set POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
```

`backend/.env` is gitignored and never committed.

---

## Start Everything

**1. Database** (reads credentials from `backend/.env` automatically)

```bash
cd backend && docker compose up -d
```
**Access database** 

```
docker exec werewolf-db psql -U werewolf -d werewolf -c
```

**2. Backend**

```bash
cd backend
set -a && source .env && set +a   # export vars so Spring Boot picks them up
./gradlew bootRun --args='--spring.profiles.active=dev'
```

**3. Frontend**

```bash
cd frontend && npm install && npm run dev
```

- Backend: http://localhost:8080
- Frontend: http://localhost:5173 (proxies `/api` and `/ws` to backend automatically)

---

## Dev Login

No OAuth needed in dev mode. Get a JWT token:

```bash
curl -s -X POST http://localhost:8080/api/auth/dev \
  -H "Content-Type: application/json" \
  -d '{"nickname": "Alice"}'
# → {"token":"eyJ...","user":{"userId":"dev:alice",...}}
```

Store and use it:

```bash
RESP=$(curl -s -X POST http://localhost:8080/api/auth/dev \
  -H "Content-Type: application/json" \
  -d '{"nickname": "Alice"}')
echo $RESP   # verify response before extracting
TOKEN=$(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

curl -s http://localhost:8080/api/room/1 -H "Authorization: Bearer $TOKEN"
```

---

## Create a Game (6-player example)

```bash
B=http://localhost:8080

# Get tokens for all players
token() {
  local R=$(curl -s -X POST $B/api/auth/dev -H "Content-Type: application/json" -d "{\"nickname\":\"$1\"}")
  echo "$1: $R" >&2
  [ -z "$R" ] && { echo "ERROR: empty response — is the backend running? (cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev')" >&2; return 1; }
  echo "$R" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])"
}f
T_ALICE=$(token Alice); T_BOB=$(token Bob); T_CAROL=$(token Carol)
T_DAVE=$(token Dave);   T_EVE=$(token Eve); T_FRANK=$(token Frank)

# Alice creates a room
ROOM=$(curl -s -X POST $B/api/room/create \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_ALICE" \
  -d '{"totalPlayers":6,"hasSeer":true,"hasWitch":true,"hasHunter":true,"hasGuard":false}')
ROOM_ID=$(echo $ROOM | python3 -c "import sys,json; print(json.load(sys.stdin)['roomId'])")
ROOM_CODE=$(echo $ROOM | python3 -c "import sys,json; print(json.load(sys.stdin)['roomCode'])")

# Others join
for T in $T_BOB $T_CAROL $T_DAVE $T_EVE $T_FRANK; do
  curl -s -X POST $B/api/room/join \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
    -d "{\"roomCode\":\"$ROOM_CODE\"}"
done

# Alice starts the game
curl -s -X POST $B/api/game/start \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_ALICE" \
  -d "{\"roomId\": $ROOM_ID}"

# All players confirm their roles (triggers night phase)
for T in $T_ALICE $T_BOB $T_CAROL $T_DAVE $T_EVE $T_FRANK; do
  curl -s -X POST $B/api/game/action \
    -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
    -d "{\"gameId\": 1, \"actionType\": \"CONFIRM_ROLE\"}"
done
```

---

## API Reference

All endpoints require `Authorization: Bearer <token>` except `/api/auth/dev`.

| Method | Path                   | Description                                                                |
|--------|------------------------|----------------------------------------------------------------------------|
| `POST` | `/api/auth/dev`        | Dev login — `{"nickname":"Alice"}` → JWT                                   |
| `POST` | `/api/room/create`     | Create room → `roomId` + `roomCode`                                        |
| `POST` | `/api/room/join`       | Join by code — `{"roomCode":"XXXX"}`                                       |
| `GET`  | `/api/room/{id}`       | Room status + player list                                                  |
| `POST` | `/api/game/start`      | Host starts game — `{"roomId":1}`                                          |
| `POST` | `/api/game/action`     | Submit any action — `{"gameId":1,"actionType":"...","targetUserId":"..."}` |
| `GET`  | `/api/game/{id}/state` | Current game state                                                         |

**WebSocket:** connect to `ws://localhost:8080/ws` with `Authorization: Bearer <token>`

- `/topic/game/{gameId}` — public events
- `/user/queue/private` — your private events (role, seer result)

**Key action types:** `CONFIRM_ROLE`, `WOLF_KILL`, `SEER_CHECK`, `SEER_CONFIRM`, `WITCH_ACT`, `GUARD_PROTECT`,
`GUARD_SKIP`, `REVEAL_NIGHT_RESULT`, `DAY_ADVANCE`, `SUBMIT_VOTE`, `VOTING_REVEAL_TALLY`, `VOTING_CONTINUE`,
`HUNTER_SHOOT`, `HUNTER_SKIP`, `BADGE_PASS`, `BADGE_DESTROY`

---

## Troubleshooting

**Reset the database** (after a JVM crash that left tables in bad state):

```bash
source backend/.env
docker exec -it werewolf-db psql -U $POSTGRES_USER -d $POSTGRES_DB \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# Then restart the backend
```

**Inspect live data:**

```bash
source backend/.env
docker exec -it werewolf-db psql -U $POSTGRES_USER -d $POSTGRES_DB
```

```sql
SELECT game_id, phase, sub_phase, day_number
FROM games;
SELECT user_id, role, is_alive
FROM game_players
WHERE game_id = 1;
```

| Error                                  | Fix                                            |
|----------------------------------------|------------------------------------------------|
| `Connection to localhost:5432 refused` | `docker compose up -d`                         |
| `relation "users" does not exist`      | Reset schema above, restart backend            |
| Port 8080 in use                       | `lsof -ti:8080 \| xargs kill -9`               |
| `401 Unauthorized`                     | Re-run `POST /api/auth/dev`, get a fresh token |
| `{"error":"Rejected(reason=...)"}`     | Check phase + role constraints in backend logs |

## E2E tests

### Full 12-player regression (one command)

Regression harness for the complete game flow — 12 players, sheriff
election, badge award, nights + days + revotes + GAME_OVER — for both
CLASSIC and HARD_MODE. Run this before shipping any change to the night
coroutine, voting pipeline, audio service, or room/game REST contracts:

```bash
./scripts/run-e2e-full-flow.sh
# --only-classic / --only-hard  to run one scenario
# --keep-report                  to open the HTML report at the end
```

Full documentation + regression-check reference:
[`docs/e2e-evidence/README.md`](docs/e2e-evidence/README.md).

Each run drops timestamped screenshots under
`docs/e2e-evidence/run-<date_time>/` so you can diff two runs or share a
specific run as evidence.

### Individual real-backend specs

at `werewolf-simple/frontend` folder

- Single file

`npx playwright test --config=playwright.real.config.ts e2e/real/game-flow.spec.ts`

- Both files

`npx playwright test --config=playwright.real.config.ts e2e/real/game-flow.spec.ts e2e/real/sheriff-flow.spec.ts`

- All real E2E tests

`npx playwright test --config=playwright.real.config.ts`

## E2E Testing Best Practices

### Common Pitfalls

**1. Initialization Consistency**
- All tests in the same suite should have identical initialization logic
- Missing initialization steps (like `START_NIGHT`) can cause tests to hang or fail mysteriously
- Always verify that each test has the same setup as passing tests

**2. Timeout Settings**
- Complex E2E tests need adequate timeout periods (90s+ for full game flows)
- Short timeouts (30s) can prevent proper debugging and error diagnosis
- Set `testInfo.setTimeout(90_000)` for comprehensive test scenarios

**3. State Diagnosis**
- Capture game state early in tests, not just at failure points
- Log phase transitions, button states, and game wrap information
- Use `testInfo.attach()` to record diagnostic data for debugging

**4. Debugging Approach**
- Start with simple solutions before complex workarounds
- Check if tests have matching initialization logic
- Verify game state progression before adding complex waiting strategies
- Don't over-engineer solutions for simple missing initialization steps

**5. Multi-browser Testing**
- Ensure all browsers synchronize state correctly
- Use `verifyAllBrowsersPhase()` to confirm phase transitions across all clients
- Test role-specific functionality from multiple perspectives

**Example: Idiot Role Testing**
- Tests 1 & 2 had different initialization → test2 failed
- Solution: Added `act('START_NIGHT', ...)` to test2
- Lesson: Always compare initialization logic between passing and failing tests 