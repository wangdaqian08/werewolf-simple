# Database Design

## Design Principles

- Keep it simple
- Auth via **WeChat OAuth** (China) or **Google OAuth** (international)
- Re-authenticate each game session — short-lived JWT, no persistent client session
- Payment tables are **stubs only** — not implemented

---

## Core Tables

### `users`

Created/updated on each OAuth login.

```sql
CREATE TABLE users
(
    user_id      VARCHAR(128) PRIMARY KEY, -- provider-prefixed: "google:107..." or "wechat:oABC..."
    nickname     VARCHAR(50) NOT NULL,
    avatar_url   VARCHAR(500),
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**Auth flow:**

```
User taps "Login with Google/WeChat"
  → OAuth redirect → POST /api/auth/google or /api/auth/wechat  {code}
  → Backend exchanges code → upsert users row
  → Issue JWT (2h, payload: userId + nickname + avatarUrl)
  → Frontend stores JWT in sessionStorage
```

---

### `rooms`

```sql
CREATE TABLE rooms
(
    room_id       INT PRIMARY KEY AUTO_INCREMENT,
    room_code     CHAR(4)                              NOT NULL UNIQUE, -- 4-digit code shared verbally
    host_user_id  VARCHAR(128)                         NOT NULL REFERENCES users (user_id),
    status        ENUM('WAITING', 'IN_GAME', 'CLOSED') NOT NULL DEFAULT 'WAITING',
    total_players INT                                  NOT NULL,
    has_seer      BOOLEAN                              NOT NULL DEFAULT FALSE,
    has_witch     BOOLEAN                              NOT NULL DEFAULT FALSE,
    has_hunter    BOOLEAN                              NOT NULL DEFAULT FALSE,
    has_guard     BOOLEAN                              NOT NULL DEFAULT FALSE,
    created_at    DATETIME                             NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     DATETIME
);
```

### `room_players`

```sql
CREATE TABLE room_players
(
    id         INT PRIMARY KEY AUTO_INCREMENT,
    room_id    INT                        NOT NULL REFERENCES rooms (room_id),
    user_id    VARCHAR(128)               NOT NULL REFERENCES users (user_id),
    seat_index INT,
    status     ENUM('NOT_READY', 'READY') NOT NULL DEFAULT 'NOT_READY',
    is_host    BOOLEAN                    NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_room_user (room_id, user_id),
    UNIQUE KEY uq_room_seat (room_id, seat_index)
);
```

---

### `games`

```sql
CREATE TABLE games
(
    game_id         INT PRIMARY KEY AUTO_INCREMENT,
    room_id         INT                                                                            NOT NULL REFERENCES rooms (room_id),
    host_user_id    VARCHAR(128)                                                                   NOT NULL REFERENCES users (user_id),
    phase           ENUM('ROLE_REVEAL', 'SHERIFF_ELECTION', 'DAY', 'VOTING', 'NIGHT', 'GAME_OVER') NOT NULL DEFAULT 'ROLE_REVEAL',
    day_number      INT                                                                            NOT NULL DEFAULT 1,
    sheriff_user_id VARCHAR(128) REFERENCES users (user_id),
    winner          ENUM('WEREWOLF', 'VILLAGER'),
    started_at      DATETIME                                                                       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at        DATETIME
);
```

### `game_players`

```sql
CREATE TABLE game_players
(
    id             INT PRIMARY KEY AUTO_INCREMENT,
    game_id        INT                                                              NOT NULL REFERENCES games (game_id),
    user_id        VARCHAR(128)                                                     NOT NULL REFERENCES users (user_id),
    seat_index     INT                                                              NOT NULL,
    role           ENUM('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD') NOT NULL,
    is_alive       BOOLEAN                                                          NOT NULL DEFAULT TRUE,
    is_sheriff     BOOLEAN                                                          NOT NULL DEFAULT FALSE,
    confirmed_role BOOLEAN                                                          NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_game_user (game_id, user_id),
    UNIQUE KEY uq_game_seat (game_id, seat_index)
);
```

---

## Night Phase

### `night_phases` — one mutable row per night per game

Tracks each role's action as it happens; used to resolve who dies at dawn and to send personalized private state via
STOMP.

```sql
CREATE TABLE night_phases
(
    id                          INT PRIMARY KEY AUTO_INCREMENT,
    game_id                     INT                                                                                      NOT NULL REFERENCES games (game_id),
    day_number                  INT                                                                                      NOT NULL,
    sub_phase                   ENUM('WEREWOLF_PICK', 'SEER_PICK', 'SEER_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE') NOT NULL DEFAULT 'WEREWOLF_PICK',
    wolf_target_user_id         VARCHAR(128) REFERENCES users (user_id),
    seer_checked_user_id        VARCHAR(128) REFERENCES users (user_id),
    seer_result_is_werewolf     BOOLEAN,
    witch_antidote_used         BOOLEAN                                                                                  NOT NULL DEFAULT FALSE,
    witch_poison_target_user_id VARCHAR(128) REFERENCES users (user_id),
    guard_target_user_id        VARCHAR(128) REFERENCES users (user_id),
    prev_guard_target_user_id   VARCHAR(128) REFERENCES users (user_id),
    UNIQUE KEY uq_game_night (game_id, day_number)
);
```

---

## Sheriff Election

```sql
CREATE TABLE sheriff_elections
(
    id                      INT PRIMARY KEY AUTO_INCREMENT,
    game_id                 INT                                          NOT NULL UNIQUE REFERENCES games (game_id),
    sub_phase               ENUM('SIGNUP', 'SPEECH', 'VOTING', 'RESULT') NOT NULL DEFAULT 'SIGNUP',
    speaking_order          TEXT, -- comma-separated userIds, set once at SPEECH start
    current_speaker_idx     INT                                          NOT NULL DEFAULT 0,
    elected_sheriff_user_id VARCHAR(128) REFERENCES users (user_id),
    started_at              DATETIME                                     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            DATETIME
);

CREATE TABLE sheriff_candidates
(
    id          INT PRIMARY KEY AUTO_INCREMENT,
    election_id INT                     NOT NULL REFERENCES sheriff_elections (id),
    user_id     VARCHAR(128)            NOT NULL REFERENCES users (user_id),
    status      ENUM('RUNNING', 'QUIT') NOT NULL DEFAULT 'RUNNING',
    UNIQUE KEY uq_election_user (election_id, user_id)
);
```

---

## Votes (generic — covers sheriff election + elimination)

```sql
CREATE TABLE votes
(
    id             INT PRIMARY KEY AUTO_INCREMENT,
    game_id        INT                                     NOT NULL REFERENCES games (game_id),
    vote_context   ENUM('SHERIFF_ELECTION', 'ELIMINATION') NOT NULL,
    day_number     INT                                     NOT NULL, -- 0 for sheriff election
    voter_user_id  VARCHAR(128)                            NOT NULL REFERENCES users (user_id),
    target_user_id VARCHAR(128) REFERENCES users (user_id),          -- NULL = abstain/skip
    voted_at       DATETIME                                NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_vote (game_id, vote_context, day_number, voter_user_id)
);
```

---

## Elimination History

```sql
CREATE TABLE elimination_history
(
    id                  INT PRIMARY KEY AUTO_INCREMENT,
    game_id             INT      NOT NULL REFERENCES games (game_id),
    day_number          INT      NOT NULL,
    eliminated_user_id  VARCHAR(128) REFERENCES users (user_id),
    eliminated_role     ENUM('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD'),
    hunter_shot_user_id VARCHAR(128) REFERENCES users (user_id),
    hunter_shot_role    ENUM('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD'),
    recorded_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_game_day (game_id, day_number)
);
```

---

## Event Log

```sql
CREATE TABLE game_events
(
    id             INT PRIMARY KEY AUTO_INCREMENT,
    game_id        INT         NOT NULL REFERENCES games (game_id),
    event_type     VARCHAR(50) NOT NULL,
    message        TEXT        NOT NULL,
    target_user_id VARCHAR(128) REFERENCES users (user_id),
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX          idx_game_events(game_id)
);
```

---

## Payment — Placeholder (Not Implemented)

Model when ready: 房卡 (Room Card) — host spends 1 card per game, guests join free.

```sql
-- PLACEHOLDER — implement when payment is ready
-- wallet_accounts  (user_id FK, room_cards INT)
-- products         (product_key, room_cards, bonus_cards, price_fen)
-- payment_orders   (order_no, user_id, product_id, status, payment_channel, external_order_id)
-- payment_callbacks (order_id, raw_payload, signature_valid)
-- card_transactions (user_id, type PURCHASE/SPEND/REFUND, amount, balance_after, game_id)
```

Provider options: WeChat Pay H5 + Alipay WAP via Ping++ aggregator, or Stripe for international.

---

## Auth Endpoints

| Method | Path               | Description                |
|--------|--------------------|----------------------------|
| POST   | `/api/auth/google` | `{code}` → `{token, user}` |
| POST   | `/api/auth/wechat` | `{code}` → `{token, user}` |

---

## Entity Relationship

```
users
  ├─ rooms (host_user_id)
  │    └─ room_players
  └─ games (host_user_id)
       ├─ game_players
       ├─ night_phases        (one per day_number)
       ├─ sheriff_elections   (1:1)
       │    └─ sheriff_candidates
       ├─ votes               (SHERIFF_ELECTION + ELIMINATION)
       ├─ elimination_history (one per day_number)
       └─ game_events
```

---

## Implementation Notes

- **DB**: MySQL 8+ or PostgreSQL 15+
- **Migrations**: Flyway — `V1__core.sql`, `V2__payment_stub.sql` (comment only)
- **OAuth**: Spring Security OAuth2 Client — built-in Google; WeChat needs custom `OAuth2UserService`
- **JWT**: `jjwt` — HS256, 2h expiry
