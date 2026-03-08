# Player Identity Design

## Problem

Mobile-web game, no accounts. Players join via a 6-char room code shared verbally in-person.
Need to identify each player securely within a session so they can reconnect automatically if
their connection drops — without remembering any password or nickname.

## Requirements

| Requirement | Detail |
|---|---|
| Zero user input to reconnect | Same phone = automatic rejoin |
| No password / memorisation | UUID invisible to user |
| Block new joiners mid-game | Backend enforces room phase lock |
| Per-session security only | No cross-session identity needed |
| Mobile Safari / Chrome | Must use web-standard APIs only |

---

## Chosen Approach — localStorage Device UUID

### Concept

On first visit to the site, generate a random UUID and store it in `localStorage`.
This UUID is the player's **device identity** for the lifetime of the game session.
The backend maps `deviceId → PlayerSession` and issues a JWT on join.

Reconnection is fully automatic: the JWT in localStorage is validated by the backend,
which restores the player to their exact seat — zero user input required.

### Join Flow

```
[Player visits site]
       │
       ▼
localStorage has deviceId?
  No  → generate crypto.randomUUID(), store it
  Yes → use existing deviceId
       │
       ▼
Player enters nickname + room code
       │
       ▼
POST /api/user/login  { deviceId, nickname, roomCode }
       │
       ▼
Backend creates PlayerSession, issues JWT
{ deviceId, nickname, roomId, seatIndex, exp }
       │
       ▼
Frontend stores JWT in localStorage
Router navigates → Room view
```

### Reconnect Flow (same device)

```
[Player reopens app / recovers from disconnect]
       │
       ▼
localStorage has JWT?
  No  → go to Lobby (re-enter nickname + room code)
  Yes → POST /api/session/restore  { jwt }
         │
         ▼
       Backend validates JWT, checks PlayerSession still active
         │
         ▼
       Return current game state → Router redirects to Room/Game view
```

### Mid-Game Lock

Once room status transitions to `IN_GAME`, the backend rejects any `deviceId`
not already registered in that room. New players cannot join.

---

## Data Model

### Backend — PlayerSession

```
PlayerSession {
  deviceId    : string       // UUID from client localStorage
  nickname    : string
  roomId      : string
  seatIndex   : number
  status      : CONNECTED | DISCONNECTED
  joinedAt    : timestamp
  lastSeenAt  : timestamp
}
```

### JWT Payload

```json
{
  "deviceId"  : "550e8400-e29b-41d4-a716-446655440000",
  "nickname"  : "Alice",
  "roomId"    : "room-001",
  "seatIndex" : 3,
  "exp"       : 1234567890
}
```

JWT expiry: **4 hours** (covers a full game session with buffer).

---

## Security Considerations

| Threat | Risk | Mitigation |
|---|---|---|
| UUID guessing | Very low (128-bit random) | Acceptable |
| JWT theft | Low (same-device party game) | Short expiry, HTTPS only |
| New player joins mid-game | None | Backend phase lock |
| Stolen phone mid-game | Low (party game context) | Out of scope for MVP |

---

## Edge Cases

| Scenario | Behaviour |
|---|---|
| Same device, JWT valid | Auto-rejoin silently |
| Same device, JWT expired, game still active | Re-authenticate with deviceId only (no nickname prompt) |
| Browser data cleared mid-game | Show "Session lost" screen, prompt nickname + room code to reclaim seat |
| New device (e.g. phone died) | Host manually removes dead seat; player joins as new player after game |

---

## Files to Implement

### Frontend
- `src/stores/userStore.ts` — add `deviceId` generation and persistence
- `src/services/userService.ts` — include `deviceId` in login request body
- `src/router/index.ts` — add session restore check on app load

### Backend
- `model/PlayerSession.java` — new JPA entity
- `service/AuthService.java` — issue JWT with `deviceId`, validate on restore
- `controller/UserController.java` — `/api/user/login` and `/api/session/restore` endpoints
- `config/SecurityConfig.java` — allow `/api/session/restore` without full auth
