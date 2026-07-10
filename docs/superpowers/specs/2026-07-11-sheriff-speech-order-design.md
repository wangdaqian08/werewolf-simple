# Sheriff Campaign Speech Order — Design

**Date:** 2026-07-11
**Status:** Approved
**Branch:** `feature/sheriff-speech-order`

## Problem

When the sheriff campaign reaches the SPEECH sub-phase, candidates currently speak in a
**random** order (`running.map { it.userId }.shuffled()` in `SheriffService`). The host has
no control over it. We want the speaking order to follow player seat index — ascending or
descending — with the host choosing the direction before speeches start.

## Decisions (user-approved)

- Host chooses **ASC or DESC** by seat index. Random order is removed entirely.
- The choice is a toggle available to the host **during SIGNUP**, defaulting to **ASC**.
  If the host never touches it, speeches start in ascending seat order.
- The host may flip the toggle any number of times during SIGNUP; the value at the moment
  of the SIGNUP→SPEECH transition wins.
- E2E tests must verify the feature end-to-end against the real backend.

## Data model

- New enum in `Enums.kt`: `enum class SpeechOrderDirection { ASC, DESC }`
- New Flyway migration `V21__sheriff_speech_order.sql`:
  `ALTER TABLE sheriff_elections ADD COLUMN speech_order_direction VARCHAR(4) NOT NULL DEFAULT 'ASC';`
- New field on `SheriffElection` entity:
  `@Enumerated(EnumType.STRING) var speechOrderDirection: SpeechOrderDirection = SpeechOrderDirection.ASC`

## Backend behavior

- New `ActionType.SHERIFF_SET_SPEECH_ORDER`, dispatched to `SheriffService.handle`.
  - Host-only (`actorUserId == game.hostUserId`), else rejected.
  - Valid only in `SHERIFF_ELECTION` phase / `SIGNUP` sub-phase, else rejected.
  - Direction arrives in `payload["direction"]` as `"ASC"` or `"DESC"`; anything else rejected.
  - Persists `election.speechOrderDirection` and rebroadcasts the SIGNUP update so all
    clients (and a reconnecting host) see the current value.
- `finishSignupDecision` and the legacy `startSpeech` both replace `.shuffled()` with:
  running candidates sorted by their `GamePlayer.seatIndex`, ascending or descending per
  the stored direction.
- `buildState` exposes `"speechOrderDirection"` in the sheriff state map.

## Frontend behavior

- `SheriffElection.vue`: host-only ASC/DESC toggle rendered during SIGNUP
  (labels 正序发言 / 倒序发言), with `data-testid` attributes
  (`speech-order-toggle`, `speech-order-asc`, `speech-order-desc` or equivalent).
- Toggle state comes from game state (`speechOrderDirection`, default ASC); tapping fires
  `SHERIFF_SET_SPEECH_ORDER` via `gameService`. No client-side ordering logic —
  server-authoritative.

## Testing (TDD)

- **Backend integration tests** (`SheriffElectionIntegrationTest` or sibling):
  1. Default: no host action → speaking order is ascending seat order of running candidates.
  2. Host sets DESC during SIGNUP → order is descending seat order.
  3. Non-host actor → rejected.
  4. Action outside SIGNUP (e.g. during SPEECH) → rejected.
  5. Invalid/missing payload direction → rejected.
  6. Players who passed are excluded from the order (unchanged invariant, now asserted
     with deterministic order).
- **Frontend unit tests**: toggle visible only for host during SIGNUP; hidden for
  non-hosts and in other sub-phases; fires the correct action; reflects state value.
- **Real E2E**: extend the sheriff flow spec — host sets DESC during signup, then assert
  each successive speaker follows descending seat order (testid locators, DOM-driven,
  per `write-real-e2e-test` conventions).

## Out of scope

- Room-level configuration of speech order before game start.
- Any change to the DAY_VOTING or last-words speech flows.
