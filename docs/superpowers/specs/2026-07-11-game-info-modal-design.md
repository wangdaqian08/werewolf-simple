# In-Game Room Info Modal — Design Spec

**Date:** 2026-07-11
**Branch:** `feature/game-info-modal`
**Status:** Approved

## Overview

During a game, any player can tap an info button to open a modal showing the
**room code** and the **game settings** (role composition and rule config).
The information is available only to players who are in that game.

User decisions (2026-07-11):

- Button lives **next to the existing 📋 `log-fab`** in `DayPhase.vue` and
  `VotingPhase.vue` (day-side phases only — not night / sheriff election).
- Modal shows the **full game config**: room code, player count, role
  composition, sheriff on/off, witch self-save rule, win-condition mode.

## Architecture

Server-authoritative, mirroring the existing precedent of `bgmTrack` /
`witchSelfSaveAllowed` on game state (needed because `roomStore` is in-memory
only and does not survive a mid-game page refresh — see comment at
`GameService.kt:370-374`).

- **No new endpoint. No DB change.** `GET /api/game/{gameId}/state` gains one
  nested block, `gameSettings`, built from the `Room` that
  `GameService.getGameState` already loads (`GameService.kt:113`).
- **Membership gate:** the block is included **only when the requester is a
  player in the game** (`myPlayer != null`). Authenticated non-members keep
  receiving today's public state with `gameSettings` absent/null. This
  implements "only for players in this game" without changing the endpoint's
  status-code behaviour (no 403), so existing bots/scripts are unaffected.
- **Frontend renders only** — the new modal is pure presentation over
  `gameStore.state.gameSettings`. No extra fetch, no game logic.

## Backend contract

Added to the map returned by `GameService.getGameState` (`GameService.kt:357`):

```kotlin
"gameSettings" to if (myPlayer != null) mapOf(
    "roomCode" to room?.roomCode,
    "totalPlayers" to room?.totalPlayers,
    "wolfCount" to room?.wolfCount,
    "roles" to room?.let { buildRoleList(it, players.size).map { r -> r.name } },
    "hasSheriff" to room?.hasSheriff,
    "witchSelfSaveAllowed" to (room?.config?.witchSelfSaveAllowed ?: true),
    "winCondition" to room?.winCondition?.name,
) else null,
```

Notes:

- `buildRoleList(room, playerCount)` (`GameService.kt:416`) is the existing
  helper that expands `wolfCount` + `hasSeer/hasWitch/hasHunter/hasGuard/
  hasIdiot` flags and pads with `VILLAGER`. `roles` is therefore the full
  per-seat role multiset (e.g. `["WEREWOLF","WEREWOLF","SEER",...]`), names
  from `PlayerRole` in `Enums.kt` (source of truth).
- `winCondition` is `WinConditionMode` (`CLASSIC | HARD_MODE`, `Enums.kt:36`).
- Field values echo the `Room` entity (`Room.kt`): `roomCode` (3-digit),
  `totalPlayers`, `wolfCount`, `hasSheriff`, `config.witchSelfSaveAllowed`
  (default `true` when config is null), `winCondition`.

## Frontend

### Types (`frontend/src/types/index.ts`)

```ts
export interface GameSettings {
  roomCode: string
  totalPlayers: number
  wolfCount: number
  roles: string[]
  hasSheriff: boolean
  witchSelfSaveAllowed: boolean
  winCondition: WinConditionMode
}
```

`GameState` gains `gameSettings?: GameSettings | null`.

### New component: `frontend/src/components/GameSettingsModal.vue`

Cloned from the `ActionLogDrawer.vue` pattern:

- Props `{ settings: GameSettings | null | undefined; open: boolean }`,
  emits `close`.
- `<Teleport to="body">`, `fade` backdrop + `slide-up` panel transitions,
  backdrop click and ✕ button both emit `close`.
- Ink-paper tokens (`var(--paper)`, `var(--border)`, `var(--text)`,
  `var(--muted)`, `var(--gold)`, `var(--red)`), `Noto Serif SC` headings.
- Content sections, each with a testid:
  - `settings-room-code` — room code, displayed large (primary purpose:
    letting late joiners/spectators be invited).
  - `settings-player-count` — `totalPlayers`.
  - `settings-roles` — role composition aggregated to counts per role
    (e.g. 狼人 ×2, 预言家 ×1, 村民 ×3) using the existing Chinese role-name
    mapping already used elsewhere in the app.
  - `settings-sheriff` — 警长: 有 / 无.
  - `settings-witch-self-save` — 女巫自救: 允许 / 禁止.
  - `settings-win-condition` — 胜利条件: 屠边 (CLASSIC) / 屠城 (HARD_MODE).
- Root panel testid: `settings-modal`. Close button: `settings-close`.
- If `settings` is null/undefined (stale client or non-member), render
  nothing inside the panel body except a muted placeholder — no crash.

### Trigger button

`settings-fab` — an ℹ️ pill button styled like `.log-fab`
(`DayPhase.vue:648-661`), placed immediately beside the log-fab in:

- `DayPhase.vue` (next to `log-fab`, `DayPhase.vue:28-35`)
- `VotingPhase.vue` (next to `log-fab`, `VotingPhase.vue:38`)

Each phase component holds a local `showSettings` ref and mounts
`<GameSettingsModal :settings="..." :open="showSettings" @close="showSettings = false" />`,
mirroring how `ActionLogDrawer` is mounted (`DayPhase.vue:236`,
`VotingPhase.vue:585`). Settings come from the game store state each phase
component already receives/reads.

### Mocks

`frontend/src/mocks/index.ts` mock game state gains a `gameSettings` block so
mock-UI E2E and local dev render the modal with data.

## Testing (TDD)

1. **Backend integration** (extend/new test in
   `backend/src/test/kotlin/com/werewolf/integration/`):
   - Player in game requests state → `gameSettings` present, `roomCode` and
     all fields match the room the game was created from; `roles` matches
     composition (count + multiset).
   - Authenticated user NOT in the game requests state → `gameSettings` is
     null; response otherwise still succeeds (no 403).
   - Vendor-neutral SQL/H2-safe (tests run H2 locally, Postgres in CI).
2. **Frontend unit** (`frontend/src/__tests__/gameSettingsModal.test.ts`,
   cloned from `actionLogDrawer.test.ts`):
   - Renders room code and every config field with correct formatted values
     when `open=true`.
   - Aggregates `roles` into per-role counts.
   - Emits `close` on backdrop and ✕ click.
   - Null `settings` → placeholder, no crash.
   - Phase-component test: `settings-fab` visible and opens the modal.
3. **E2E (real backend)** — extend `frontend/e2e/real/game-flow.spec.ts`
   (already in the CI shard matrix; adding there avoids the
   spec-not-in-shard-matrix silent-skip trap):
   - Mid-game (day phase), a player clicks `settings-fab`, asserts
     `settings-modal` visible, `settings-room-code` text equals
     `ctx.roomCode` from the harness, and role/rule fields are visible with
     expected values from the known game setup.
   - `getByTestId` only, no `waitForTimeout`, positive assertions.

## Constraints

- No game logic in the frontend; modal is display-only.
- Enum literals (`PlayerRole`, `WinConditionMode`) verified against
  `backend/src/main/kotlin/com/werewolf/model/Enums.kt` (source of truth).
- Surgical changes: do not restyle or refactor `log-fab`, `ActionLogDrawer`,
  or phase components beyond inserting the button + modal mount.
- Night phase, sheriff election, and game-over screens are out of scope
  (user decision).
