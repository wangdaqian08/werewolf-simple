# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Werewolf (狼人杀) mobile-web game** targeting mobile Safari and Chrome, built with **Vue 3 + TypeScript** (
frontend) and **Spring Boot 3** (backend). The design resolution is 417x614 (portrait mode for mobile).

## Tech Stack

### Frontend

- **Vue 3 + TypeScript + Vite** — HTML-first templates, Composition API, fast builds
- **Tailwind CSS** — utility-first styling, no custom CSS files needed
- **Element Plus** — Vue 3 UI component library (mobile-friendly, Chinese-origin)
- **vue-router** — page navigation (Lobby, Room, Game, Result views)
- **Pinia** — state management (replaces Vuex; simpler, type-safe)
- **axios** — HTTP client for REST API calls
- **@stomp/stompjs** — WebSocket client, pairs directly with Spring's STOMP broker

### Backend

- **Spring Boot 3 (Java 17+)** — REST + WebSocket (STOMP)
- **Spring Data JPA + PostgreSQL** — persistence (Flyway migrations)
- **Spring Security + JWT** — stateless auth (nickname-based for MVP)
- **spring-boot-starter-websocket** — built-in STOMP broker

### Deployment

```
[ Mobile Browser ]
       |
    [ Nginx ]
    /       \
[Vue static] [Spring Boot :8080]
              /api/*  →  REST
              /ws/*   →  WebSocket proxy
```

- Nginx serves `frontend/dist/` as static files
- Nginx reverse-proxies `/api` and `/ws` to Spring Boot on port 8080
- Spring Boot runs as a systemd service or Docker container
- SSL via Let's Encrypt (required — mobile Safari blocks `ws://`, must use `wss://`)

## Project Structure

```
werewolf-simple/
├── frontend/                   # Vue 3 app
│   ├── src/
│   │   ├── views/              # Pages: Lobby, Room, Game, Result
│   │   ├── components/         # Reusable UI components
│   │   ├── services/           # axios HTTP calls + STOMP subscriptions
│   │   ├── stores/             # Pinia stores (user, room, game state)
│   │   └── types/              # TypeScript interfaces (mirror backend DTOs)
│   ├── package.json
│   └── vite.config.ts
│
└── backend/                    # Spring Boot app
    └── src/main/java/
        ├── controller/         # REST controllers + WebSocket message handlers
        ├── service/            # Game logic (server-authoritative)
        ├── model/              # JPA entities
        ├── dto/                # Request/Response DTOs
        ├── config/             # WebSocket config, Security config
        └── websocket/          # STOMP event publishers
```

## Architecture Philosophy

This project follows a **server-authoritative client-server architecture**:

- **Vue 3 Frontend**: Handles UI rendering, animations, user interactions, and presentation logic only
- **Spring Boot Backend**: Manages authentication, room management, game rules, and data persistence
- **Communication**: REST HTTP for stateless operations + WebSocket (STOMP) for real-time game events

**Critical**: The frontend should NEVER implement game logic. All game state, rule validation, and business decisions
must be handled by the backend API.

## Communication Protocol

### Two-protocol strategy

| Protocol          | Used For                                    | Why                      |
|-------------------|---------------------------------------------|--------------------------|
| REST HTTP (axios) | Login, create room, join room, leave room   | Stateless, simple        |
| WebSocket + STOMP | Real-time game events, phase changes, votes | Full-duplex, server-push |

### STOMP Topic Design

```
/topic/room/{roomId}       → room state updates (player join/leave, ready status)
/topic/game/{gameId}       → public game events (phase change, vote results, deaths)
/user/queue/private        → private messages (your role, werewolf night channel)
/app/game/action           → client → server game actions (vote, skill use)
```

## Authentication (MVP)

Player enters a nickname → backend generates a JWT with `userId` + `nickname`. No password or email required for MVP.
JWT is sent as `Authorization: Bearer <token>` on all HTTP requests and as a STOMP connect header for WebSocket auth.

## Layer Responsibilities

### Network/Service Layer (`src/services/`)

- **userService**: Login (nickname → JWT), logout, get profile
- **roomService**: Create room, join room, leave room, get room list
- **gameService**: Send game actions, subscribe to STOMP topics
- Services call backend API and return typed data. No business logic here.

### Store Layer (`src/stores/`)

- **userStore**: Session JWT, nickname, userId
- **roomStore**: Current room info, player list, ready states
- **gameStore**: Game phase, public events, player states received from backend

### View/Component Layer (`src/views/`, `src/components/`)

Pure presentation:

- Renders data from stores
- Handles user input and calls services
- Animations and visual effects
- NO business logic or game rules

## Backend API Contract

```
// User APIs
POST   /api/user/login           // nickname → returns JWT
GET    /api/user/profile         // get current user info
POST   /api/user/logout          // invalidate session

// Room APIs
POST   /api/room/create          // create new room → returns roomCode
POST   /api/room/join            // join by roomCode → returns room info
POST   /api/room/leave           // leave current room
GET    /api/room/{roomId}        // get room details
GET    /api/room/list            // list available rooms

// Game APIs
POST   /api/game/action          // perform game action (vote, skill, etc.)
GET    /api/game/state           // get current game state

// WebSocket (STOMP)
WS     /ws                       // STOMP endpoint
```

## Room Management Flow

1. **Create Room**: User clicks "Create Room" → `roomService.createRoom()` → backend returns room code → navigate to
   Room view
2. **Join Room**: User enters room code → `roomService.joinRoom(roomCode)` → backend validates, returns room info and
   seat → update UI
3. **Room State Sync**: WebSocket STOMP connection established after joining; backend pushes updates to
   `/topic/room/{roomId}`
4. **Leave Room**: `roomService.leaveRoom()` → backend notifies others → disconnect STOMP, return to Lobby

## Frontend-Backend Separation Principles

### Frontend Responsibilities

**Should do**:
- Render UI and animations
- Handle user input (clicks, touches)
- Display data received from backend
- Play sound effects and visual effects
- Scene transitions and navigation

**Should NOT do**:
- Validate game rules (e.g., "can this player vote?")
- Store sensitive data (e.g., other players' hidden roles)
- Calculate game outcomes (e.g., "who wins?")
- Manage room state independently
- Perform user authentication logic

### Backend Responsibilities

- Validate all game actions
- Enforce game rules
- Manage room state and player sessions
- Store and retrieve persistent data
- Handle authentication and authorization
- Detect cheating and enforce fair play
- Generate random events (role assignment, etc.)

### Data Flow Example: Player Voting

**Wrong** (client-side logic):
```typescript
onVotePlayer(targetPlayer: string) {
    if (this.hasVoted) return; // Client validates — WRONG
    this.vote = targetPlayer;
}
```

**Correct** (server-authoritative):
```typescript
async onVotePlayer(targetPlayer: string) {
    const result = await gameService.submitVote(targetPlayer);
    if (result.success) {
        this.updateVoteUI(result.voteData); // display backend result
    } else {
        this.showError(result.message); // backend rejected
    }
}
```

## UI Design Guidelines

- **Design resolution**: 417x614 (portrait orientation)
- **Safe area**: Account for device notches and navigation bars
- **Touch targets**: Minimum 88x88 pixels for buttons
- **Font sizes**: Minimum 24px for readability
- **Layouts**: Use Tailwind responsive utilities for adaptation

### Current Style: Style C — Ink & Paper

Reference demo: `ui-demos/style-c-ink-paper.html`

| Token        | Value     | Usage                            |
|--------------|-----------|----------------------------------|
| `--bg`       | `#ede8df` | Page background (warm parchment) |
| `--paper`    | `#f5f0e8` | Surface / input background       |
| `--card`     | `#ffffff` | Card / elevated surface          |
| `--border`   | `#ccc2b0` | Borders                          |
| `--border-l` | `#ddd6c6` | Light borders                    |
| `--text`     | `#1a140c` | Primary text                     |
| `--muted`    | `#8a7a65` | Secondary text                   |
| `--red`      | `#b5251a` | Primary accent (vermillion)      |
| `--gold`     | `#a07830` | Secondary accent                 |
| `--green`    | `#2d6a3f` | Success / ready state            |
| `--ink`      | `#2a1f14` | Night phase background           |

**Typography**: `Noto Sans SC` (body) + `Noto Serif SC` (headings, role names, timer)

**Button conventions**:

- `btn-primary` — red fill, white text (main actions)
- `btn-secondary` — parchment fill, muted border (cancel/skip)
- `btn-gold` — gold fill (ready/confirm)
- `btn-success` — green fill (save/heal)
- `btn-danger` — red fill (vote/poison)
- `btn-outline` — transparent, muted border (passive)

**Night phase** inverts to `--ink` (`#2a1f14`) background with paper-white text — no separate component needed, just
swap the screen background.

**To switch styles**: swap the CSS variable values and font imports. Reference demos are in `ui-demos/`. Style A (
glassmorphism blue) is `ui-demos/style-a-glassmorphism.html`.

## Development Roadmap

### Phase 1 — Infrastructure (complete first)

1. Vite project scaffold with Vue 3 + TypeScript + Tailwind + Element Plus
2. axios HTTP client with JWT interceptor
3. STOMP WebSocket client wrapper
4. Pinia stores (user, room, game)

### Phase 2 — Core Views

1. Lobby view (nickname login, create/join room)
2. Room view (player list, ready toggle, start game)
3. Game view (phase display, action buttons, event log)
4. Result view (game outcome, roles revealed)

### Phase 3 — Game Logic Integration

1. All game actions routed through backend API
2. Real-time game events via STOMP subscriptions
3. Private role channel via `/user/queue/private`

### Phase 4 — Polish & Deployment

1. Nginx config + SSL (Let's Encrypt)
2. Mobile Safari/Chrome testing
3. Error handling, reconnection logic
4. Performance optimization

## Critical Architecture Principles (Summary)

### 1. Server-Authoritative Design

- All game logic executes on the backend
- Frontend is a "dumb client" that displays state and sends user actions
- Backend validates every action before changing state

### 2. Layer Separation
```
┌─────────────────────────────────────┐
│  View/Component Layer (views/)      │  → User interactions
├─────────────────────────────────────┤
│  Service Layer (services/)          │  → API abstractions
├─────────────────────────────────────┤
│  Store Layer (stores/)              │  → State management (Pinia)
└─────────────────────────────────────┘
         ↕  REST + STOMP WebSocket
┌─────────────────────────────────────┐
│  Spring Boot Backend                │  → Business logic
└─────────────────────────────────────┘
```

### 3. Common Mistakes to Avoid

- Don't implement game rules in TypeScript — call backend API instead
- Don't store sensitive data (roles, other players' state) in frontend
- Don't validate user actions client-side — send to backend for authoritative validation
- Don't calculate game outcomes in UI code — receive computed results from backend

### 4. When Implementing New Features

1. **Is this just UI?** → Implement in frontend
2. **Does this involve game rules?** → Backend API required
3. **Does this change game state?** → Must go through backend
4. **Is this sensitive data?** → Never store in frontend

If in doubt, default to backend implementation.

---

**Remember**: This is a mobile-web game for real multiplayer gameplay. A robust client-server architecture is essential
for security, fairness, and scalability.

---

## Behavioral Guidelines (Karpathy)

Behavioral guidelines to reduce common LLM coding mistakes, derived from [Andrej Karpathy's observations](https://x.com/karpathy/status/2015883857489522876) on LLM coding pitfalls. Source: [forrestchang/andrej-karpathy-skills](https://github.com/forrestchang/andrej-karpathy-skills).

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.
