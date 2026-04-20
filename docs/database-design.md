# Database Design

**DB:** PostgreSQL 15+. Migrations in `backend/src/main/resources/db/migration/` (Flyway).
The migration files are the source of truth — this doc mirrors their state after V1–V10.

---

## Auth

- `POST /api/auth/dev` — dev-profile-only; `{nickname}` → `{token, user}` (issues a dev JWT with `userId=dev:<nickname>`). See `DevAuthController.kt`.
- `POST /api/auth/google`, `POST /api/auth/wechat` — production OAuth code exchange. See ADR-001.

JWT: HS256, 2 h expiry, payload `{ userId, nickname, avatarUrl }`.
Attached as `Authorization: Bearer <token>` on HTTP and as a STOMP connect header on WebSocket.

---

## Core Tables

### `users`

Created/updated on each auth call (OAuth or dev).

```sql
CREATE TABLE users (
    user_id      VARCHAR(128) PRIMARY KEY,  -- "google:<sub>", "wechat:<openid>", or "dev:<nickname>"
    nickname     VARCHAR(50) NOT NULL,
    avatar_url   VARCHAR(500),
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### `rooms`

Role/config flags determine which handlers run in each game. `config` holds JSONB role-timing tuning (see V9).

```sql
CREATE TABLE rooms (
    room_id       SERIAL PRIMARY KEY,
    room_code     VARCHAR(4)  NOT NULL UNIQUE,
    host_user_id  VARCHAR(128) NOT NULL REFERENCES users(user_id),
    status        VARCHAR(10) NOT NULL DEFAULT 'WAITING'
                  CHECK (status IN ('WAITING','IN_GAME','CLOSED')),
    total_players INT         NOT NULL,
    has_seer      BOOLEAN     NOT NULL DEFAULT FALSE,
    has_witch     BOOLEAN     NOT NULL DEFAULT FALSE,
    has_hunter    BOOLEAN     NOT NULL DEFAULT FALSE,
    has_guard     BOOLEAN     NOT NULL DEFAULT FALSE,
    has_idiot     BOOLEAN     NOT NULL DEFAULT FALSE,   -- V1 / V4
    has_sheriff   BOOLEAN     NOT NULL DEFAULT TRUE,    -- V5
    win_condition VARCHAR(20) NOT NULL DEFAULT 'CLASSIC', -- V6: 'CLASSIC' | 'HARD_MODE'
    config        JSONB,                                 -- V9: role delays, etc.
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     TIMESTAMP
);
```

### `room_players`

```sql
CREATE TABLE room_players (
    id         SERIAL PRIMARY KEY,
    room_id    INT          NOT NULL REFERENCES rooms(room_id),
    user_id    VARCHAR(128) NOT NULL REFERENCES users(user_id),
    seat_index INT,
    status     VARCHAR(10)  NOT NULL DEFAULT 'NOT_READY'
               CHECK (status IN ('NOT_READY','READY')),
    is_host    BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (room_id, user_id),
    UNIQUE (room_id, seat_index)
);
```

### `games`

```sql
CREATE TABLE games (
    game_id         SERIAL PRIMARY KEY,
    room_id         INT          NOT NULL REFERENCES rooms(room_id),
    host_user_id    VARCHAR(128) NOT NULL REFERENCES users(user_id),
    phase           VARCHAR(20)  NOT NULL DEFAULT 'ROLE_REVEAL',
    sub_phase       VARCHAR(25),                                   -- V3
    day_number      INT          NOT NULL DEFAULT 1,
    sheriff_user_id VARCHAR(128) REFERENCES users(user_id),
    winner          VARCHAR(10) CHECK (winner IN ('WEREWOLF','VILLAGER')),
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at        TIMESTAMP
);
```

Check constraints (current, after V10 + V8):

```sql
-- phase
CHECK (phase IN ('ROLE_REVEAL','SHERIFF_ELECTION','WAITING','NIGHT',
                 'DAY_PENDING','DAY_DISCUSSION','DAY_VOTING','GAME_OVER'))
-- sub_phase (VotingSubPhase + DaySubPhase union; NULL allowed)
CHECK (sub_phase IN ('RESULT_HIDDEN','RESULT_REVEALED',
                     'VOTING','RE_VOTING','VOTE_RESULT',
                     'HUNTER_SHOOT','BADGE_HANDOVER'))
```

### `game_players`

```sql
CREATE TABLE game_players (
    id             SERIAL PRIMARY KEY,
    game_id        INT          NOT NULL REFERENCES games(game_id),
    user_id        VARCHAR(128) NOT NULL REFERENCES users(user_id),
    seat_index     INT          NOT NULL,
    role           VARCHAR(10)  NOT NULL
                   CHECK (role IN ('WEREWOLF','VILLAGER','SEER','WITCH','HUNTER','GUARD','IDIOT')),
    is_alive       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_sheriff     BOOLEAN      NOT NULL DEFAULT FALSE,
    confirmed_role BOOLEAN      NOT NULL DEFAULT FALSE,
    can_vote       BOOLEAN      NOT NULL DEFAULT TRUE,    -- V7; set false on idiot reveal
    idiot_revealed BOOLEAN      NOT NULL DEFAULT FALSE,   -- V7
    UNIQUE (game_id, user_id),
    UNIQUE (game_id, seat_index)
);
```

---

## Night Phase

One mutable row per `(game_id, day_number)`. Used to resolve dawn deaths and drive personalized STOMP private state.

```sql
CREATE TABLE night_phases (
    id                          SERIAL PRIMARY KEY,
    game_id                     INT         NOT NULL REFERENCES games(game_id),
    day_number                  INT         NOT NULL,
    sub_phase                   VARCHAR(20) NOT NULL DEFAULT 'WAITING'
                                CHECK (sub_phase IN
                                  ('WAITING','WEREWOLF_PICK','SEER_PICK','SEER_RESULT',
                                   'WITCH_ACT','GUARD_PICK','COMPLETE')),
    wolf_target_user_id         VARCHAR(128),
    seer_checked_user_id        VARCHAR(128),
    seer_result_is_werewolf     BOOLEAN,
    witch_antidote_used         BOOLEAN     NOT NULL DEFAULT FALSE,
    witch_poison_target_user_id VARCHAR(128),
    guard_target_user_id        VARCHAR(128),
    prev_guard_target_user_id   VARCHAR(128),
    UNIQUE (game_id, day_number)
);
```

---

## Sheriff Election

```sql
CREATE TABLE sheriff_elections (
    id                      SERIAL PRIMARY KEY,
    game_id                 INT         NOT NULL UNIQUE REFERENCES games(game_id),
    sub_phase               VARCHAR(10) NOT NULL DEFAULT 'SIGNUP'
                            CHECK (sub_phase IN ('SIGNUP','SPEECH','VOTING','RESULT','TIED')),
    speaking_order          TEXT,                   -- comma-separated userIds
    current_speaker_idx     INT         NOT NULL DEFAULT 0,
    elected_sheriff_user_id VARCHAR(128) REFERENCES users(user_id),
    started_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            TIMESTAMP
);

CREATE TABLE sheriff_candidates (
    id          SERIAL PRIMARY KEY,
    election_id INT          NOT NULL REFERENCES sheriff_elections(id),
    user_id     VARCHAR(128) NOT NULL REFERENCES users(user_id),
    status      VARCHAR(10)  NOT NULL DEFAULT 'RUNNING'
                CHECK (status IN ('RUNNING','QUIT')),
    UNIQUE (election_id, user_id)
);
```

The `TIED` sub_phase is entered when a sheriff vote ties and a revote is needed.

---

## Votes (generic — sheriff election + elimination)

See ADR-005 for why this is a single table.

```sql
CREATE TABLE votes (
    id             SERIAL PRIMARY KEY,
    game_id        INT          NOT NULL REFERENCES games(game_id),
    vote_context   VARCHAR(20)  NOT NULL
                   CHECK (vote_context IN ('SHERIFF_ELECTION','ELIMINATION')),
    day_number     INT          NOT NULL,   -- 0 for sheriff election
    voter_user_id  VARCHAR(128) NOT NULL REFERENCES users(user_id),
    target_user_id VARCHAR(128) REFERENCES users(user_id),  -- NULL = abstain
    voted_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (game_id, vote_context, day_number, voter_user_id)
);
```

Sheriff weight (1.5× for the sheriff's vote during `ELIMINATION`) is applied at tally time, not stored here. See ADR-009 and `TallyCalculator.kt`.

---

## Elimination History

```sql
CREATE TABLE elimination_history (
    id                  SERIAL PRIMARY KEY,
    game_id             INT       NOT NULL REFERENCES games(game_id),
    day_number          INT       NOT NULL,
    eliminated_user_id  VARCHAR(128),
    eliminated_role     VARCHAR(10) CHECK (eliminated_role IN
                        ('WEREWOLF','VILLAGER','SEER','WITCH','HUNTER','GUARD','IDIOT')),
    hunter_shot_user_id VARCHAR(128),
    hunter_shot_role    VARCHAR(10) CHECK (hunter_shot_role IN
                        ('WEREWOLF','VILLAGER','SEER','WITCH','HUNTER','GUARD','IDIOT')),
    recorded_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (game_id, day_number)
);
```

## Event Log

```sql
CREATE TABLE game_events (
    id             SERIAL PRIMARY KEY,
    game_id        INT         NOT NULL REFERENCES games(game_id),
    event_type     VARCHAR(50) NOT NULL,
    message        TEXT        NOT NULL,
    target_user_id VARCHAR(128) REFERENCES users(user_id),
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_game_events ON game_events(game_id);
```

---

## Payment — Placeholder

Stub only. V2 migration contains commented-out scaffolding for a future 房卡 (room-card) system. Not implemented.

---

## Entity Relationship

```
users
  ├─ rooms                (host_user_id)
  │    └─ room_players
  └─ games                (host_user_id)
       ├─ game_players
       ├─ night_phases        (one per day_number)
       ├─ sheriff_elections   (1:1, candidates under it)
       ├─ votes               (SHERIFF_ELECTION day=0 + ELIMINATION day=N)
       ├─ elimination_history (one per day_number)
       └─ game_events
```

---

## Notes

- Entity classes live at `backend/src/main/kotlin/com/werewolf/model/*.kt`.
- Enum values in CHECK constraints must match `Enums.kt` — always update V__ migrations when an enum value is added (see V8 for `RE_VOTING`, V10 for `phase`).
- String-backed enums in `games.sub_phase` hold values from either `DaySubPhase` or `VotingSubPhase` — the union is documented in ADR-004.
