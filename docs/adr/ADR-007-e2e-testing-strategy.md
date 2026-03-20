# ADR-007 — E2E Testing Strategy

**Date:** 2026-03-20
**Status:** Accepted

---

## Context

As the Werewolf game grows in complexity (multi-phase game loop, per-role private events, action gating), we need a
testing strategy that:

1. **Correctness** — each phase transition fires only when the right actions complete; wrong phases reject actions
2. **Role visibility** — each player receives exactly the private info their role entitles them to (no role leaks)
3. **Action gating** — roles can only execute their allowed actions; others are rejected
4. **Design fidelity** — what each player sees in the browser matches the design spec per phase

Manual testing with multiple browser windows does not scale. We need automated, reproducible coverage at each layer.

---

## Decision

We adopt a **three-layer testing architecture** unified by a shared **Scenario Fixture** format.

```
┌─────────────────────────────────────────────────────┐
│  Game Scenario (Kotlin data class or YAML fixture)  │
│  • player count, role assignments                   │
│  • action sequence (who does what, when)            │
│  • expected events per player per phase             │
└────────────────┬────────────────┬───────────────────┘
                 │                │
    ┌────────────▼──────┐  ┌──────▼──────────────────┐
    │ Spring Integration│  │ Playwright Multi-Context │
    │    Tests (B)      │  │     Visual E2E (C)       │
    │                   │  │                          │
    │ - Real Spring ctx │  │ - N browser contexts     │
    │ - Real DB         │  │ - Real backend           │
    │ - STOMP clients   │  │ - Screenshot each phase  │
    │ - Assert events   │  │ - HTML visual report     │
    └───────────────────┘  └──────────────────────────┘
```

---

## Layer 1 — Scenario Fixtures

**File:** `backend/src/test/kotlin/com/werewolf/test/scenarios/GameScenario.kt`

```kotlin
data class GameScenario(
    val name: String,
    val players: List<PlayerSpec>,           // nickname + role
    val roomConfig: RoomConfig,              // hasSeer, hasWitch, etc.
    val steps: List<ScenarioStep>,           // ordered action sequence
)

data class PlayerSpec(val nickname: String, val role: PlayerRole)

data class ScenarioStep(
    val description: String,
    val actions: List<PlayerAction>,         // what each player does this step
    val expectBroadcast: List<EventMatcher>, // /topic/game/{id} assertions
    val expectPrivate: Map<String, List<EventMatcher>>, // /user/queue/private per nickname
    val expectPhase: PhaseAssertion?,        // phase + subPhase after step
    val expectRejected: List<RejectionSpec>? = null, // actions that must fail
)
```

### Built-in Scenarios

| Scenario                 | Players | Roles                                    | Notes                                  |
|--------------------------|---------|------------------------------------------|----------------------------------------|
| `StandardSixPlayer`      | 6       | 2W, Seer, Witch, Hunter, Villager        | Full happy path, wolves win            |
| `StandardSixVillagerWin` | 6       | 2W, Seer, Witch, Hunter, Villager        | Villagers win by day 2                 |
| `MinimalFourPlayer`      | 4       | 2W, 2 Villager                           | No special roles                       |
| `WithGuard`              | 7       | 2W, Seer, Witch, Hunter, Guard, Villager | Guard saves wolf target                |
| `WitchPoisonPath`        | 6       | 2W, Seer, Witch, Hunter, Villager        | Witch uses poison not antidote         |
| `HunterShootsAfterVote`  | 6       | 2W, Seer, Witch, Hunter, Villager        | Hunter eliminated by vote, shoots back |
| `TieVote`                | 6       | 2W, Seer, Witch, Hunter, Villager        | Vote ties, no elimination              |
| `SheriffElectionPath`    | 6       | 2W, Seer, Witch, Hunter, Villager        | Election enabled                       |

---

## Layer 2 — Spring Integration Tests

**Location:** `backend/src/test/kotlin/com/werewolf/test/integration/`

### Setup

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameFlowIntegrationTest {

    // One WebSocketStompClient per player
    // Connected via ws://localhost:{port}/ws
    // Each has own JWT (from POST /api/auth/dev)
    // Each subscribes to /topic/game/{gameId} + /user/queue/private

    // Message collection: CopyOnWriteArrayList<> per player
    // Assertions: Awaitility.await().atMost(5, SECONDS).until { ... }
}
```

### What each test verifies per ScenarioStep

1. **Phase transition** — `game.phase` + `game.subPhase` match `expectPhase` after step actions
2. **Broadcast events** — `/topic/game/{gameId}` messages match `expectBroadcast` (type + key fields)
3. **Private events** — each player's `/user/queue/private` inbox matches `expectPrivate[nickname]`
4. **Role info isolation** — WEREWOLF players get teammate list; SEER gets check result; others do not
5. **Action gating** — actions in `expectRejected` return `GameActionResult.Rejected` (not accepted)

### Key files

| File                         | Purpose                                               |
|------------------------------|-------------------------------------------------------|
| `GameFlowIntegrationTest.kt` | Main test class, scenario runner                      |
| `StompTestClient.kt`         | Wraps `WebSocketStompClient`, collects messages       |
| `ScenarioRunner.kt`          | Drives a `GameScenario` through HTTP + STOMP          |
| `EventMatcher.kt`            | Flexible assertion on `DomainEvent` fields            |
| `application-test.yml`       | Test profile: H2 in-memory or Testcontainers Postgres |

### Dependencies to add to `build.gradle.kts`

```kotlin
testImplementation("org.awaitility:awaitility-kotlin:4.2.1")
testImplementation("org.springframework.boot:spring-boot-starter-websocket")
// For Testcontainers (optional, alternative to H2):
testImplementation("org.testcontainers:postgresql:1.19.3")
testImplementation("org.testcontainers:junit-jupiter:1.19.3")
```

---

## Layer 3 — Playwright Visual E2E

**Location:** `frontend/e2e/full-flow/`

### Setup

Each player = separate `BrowserContext` (own cookies, own localStorage, own WebSocket connection).

```typescript
// e2e/full-flow/standard-six-player.spec.ts
test('6-player standard game — wolves win', async ({browser}) => {
    const players = await createPlayerContexts(browser, 6)
    // players[0] = host (Alice), players[1..5] = guests

    // Step 1: All join room
    // Step 2: Host starts game
    // Step 3: All confirm roles → screenshot each player's role reveal
    // Step 4: Night phase — wolves pick, seer checks, witch acts
    // Step 5: Day phase → screenshot each player's day view
    // Step 6: Vote → screenshot tally
    // Step 7: Repeat until game over → screenshot result screen
})
```

### Screenshot Strategy

At each phase transition, capture all N player views into a named directory:

```
playwright-report/
  visual/
    standard-6-player/
      step-01-role-reveal/
        alice-WEREWOLF.png
        bob-WEREWOLF.png
        carol-SEER.png
        dave-WITCH.png
        eve-HUNTER.png
        frank-VILLAGER.png
      step-02-night-werewolf-pick/
        alice-WEREWOLF.png    ← sees wolf UI (targeting)
        carol-SEER.png        ← sees waiting UI
      step-03-day-result-hidden/
        ...
```

### What each screenshot verifies

- **Role reveal**: correct role name, correct teammate display (wolves see each other, others don't)
- **Night pick**: only the active role sees targeting UI; others see "waiting" screen
- **Day**: all see same killed player list
- **Voting**: all see same tally; eliminated player's role revealed
- **Game over**: winner banner, all roles revealed

### Helper utilities

| File                                     | Purpose                                                 |
|------------------------------------------|---------------------------------------------------------|
| `e2e/full-flow/helpers/playerContext.ts` | `createPlayerContexts(browser, n)` — auth, join room    |
| `e2e/full-flow/helpers/gameDriver.ts`    | `advancePhase(players, step)` — drives scenario step    |
| `e2e/full-flow/helpers/screenshotAll.ts` | `screenshotAll(players, stepName)` — captures all views |
| `e2e/full-flow/scenarios/standardSix.ts` | Scenario data (mirrors backend `StandardSixPlayer`)     |

---

## Test Profile Config

**`backend/src/test/resources/application-test.yml`**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
  flyway:
    enabled: false   # Let Hibernate manage schema in tests
```

*(Alternative: Testcontainers Postgres for closer-to-prod fidelity)*

---

## Consequences

**Positive:**

- Scenario fixtures serve as living documentation of game rules
- Integration tests catch backend regressions without browser overhead
- Visual E2E screenshots catch UI regressions and role-visibility bugs simultaneously
- A single scenario description drives both backend and frontend tests

**Negative:**

- Multi-context Playwright tests are slower (~30–60 s per scenario)
- Maintaining scenario fixtures requires updating when game rules change
- H2 in-memory DB may diverge from production Postgres behavior (mitigated by Testcontainers option)

---

## Alternatives Considered

- **Unit tests only**: Too granular to catch integration bugs (STOMP event routing, phase gating)
- **Single-browser E2E**: Cannot test per-player role isolation (the key correctness concern)
- **Cypress**: Less suitable for multi-context; Playwright's `BrowserContext` model is the right fit
