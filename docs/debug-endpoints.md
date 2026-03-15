# Debug Endpoints

Mock-mode only (`VITE_MOCK=true`). These endpoints are intercepted by `axios-mock-adapter` inside the browser — they are **not real HTTP routes** and cannot be called with curl.

## How to run commands

Open the browser DevTools console (`F12` → Console) while the app is running. A `__debug` helper is available:

```js
// see all available commands
__debug
```

Every command returns a Promise. You can fire and forget or await it:

```js
__debug.gameStart()
```

---

## Room View

### Toggle player ready status

```js
__debug.ready("u2")          // mark Alice ready
__debug.ready("u3", false)   // mark Bob not ready
```

Sets a player's ready status and fires a live `ROOM_UPDATE` over STOMP.

Player IDs: `u2` Alice · `u3` Bob · `u4` Carol · `u5` Dave · `u6` Tom · `u7` 阿强 · `u8` 波波 · `u9` 小花

Use this to make all guests ready so the **Start Game** button enables for the host.

---

### Add a fake player

```js
__debug.addPlayer()
```

Adds the next fake player (小龙 → Nina → Zara → Rex) to the next open seat. Returns `400` when the room is full.

---

### Start game

**UI button:** `Debug: Launch Game` (Room view)

```js
__debug.gameStart()
```

Fires `GAME_STARTED` on the room topic → navigates all clients to the Game view → pushes `ROLE_REVEAL` state. You (u1) are assigned the **SEER** role.

---

## Role Reveal Phase

After `gameStart()`, the game opens on the Role Reveal card showing your role.

### Skip to Sheriff Election

**UI button:** `Skip → Sheriff`

```js
__debug.roleSkip()
```

When to use: you are on the **waiting screen** (shown after tapping "知道了") and want to advance without 9 confirmations. Also works from the Role Reveal card itself.

Transitions immediately to `SHERIFF_ELECTION` → `SIGNUP`.

---

## Sheriff Election Phase

### Switch sub-phase

**UI buttons:** `Sign-up` · `Speech: Me` · `Speech: Watch` · `Voting` · `Result`

```js
__debug.sheriffPhase("SIGNUP")
__debug.sheriffPhase("SPEECH_CANDIDATE")   // you are speaking
__debug.sheriffPhase("SPEECH_AUDIENCE")    // Tom is speaking
__debug.sheriffPhase("VOTING")
__debug.sheriffPhase("RESULT")
```

| Preset | What you see |
|---|---|
| `SIGNUP` | Candidate sign-up; candidate buttons appear in the debug panel |
| `SPEECH_CANDIDATE` | You (u1) are the current speaker |
| `SPEECH_AUDIENCE` | Tom (u6) is speaking; you are watching |
| `VOTING` | Vote for a candidate |
| `RESULT` | Tom wins; tally shown |

---

### Add / remove a candidate

**UI buttons:** `+ Alice` · `− Alice` · `+ Bob` · `− Bob` · `+ Tom` · `− Tom`
*(visible in the debug panel only when sub-phase is `SIGNUP`)*

Alice (`u2`) and Tom (`u6`) are already candidates after `roleSkip()` or `sheriffPhase("SIGNUP")`. Bob (`u3`) is not — adding Bob is a real add.

```js
__debug.sheriffCandidate("u3", "Bob",   "🎭")   // real add — Bob not in default list
__debug.sheriffCandidate("u2", "Alice", "😊")   // no-op if Alice already present

__debug.sheriffRemove("u2")   // remove Alice
__debug.sheriffRemove("u6")   // remove Tom
```

---

### Exit to Day Phase

**UI button:** `← Day`

```js
__debug.sheriffExit()
```

Ends the election and transitions to `DAY` with the night result **hidden**.

---

## Day Phase

### Load a scenario

**UI buttons:** `Host·Hidden` · `Host·Revealed` · `Dead` · `Alive·Hidden` · `Alive·Revealed` · `Guest`

```js
__debug.dayScenario("HOST_HIDDEN")
__debug.dayScenario("HOST_REVEALED")
__debug.dayScenario("DEAD")
__debug.dayScenario("ALIVE_HIDDEN")
__debug.dayScenario("ALIVE_REVEALED")
__debug.dayScenario("GUEST")
```

| Scenario | You are | Night result |
|---|---|---|
| `HOST_HIDDEN` | Host (u1) | Hidden — only host sees it |
| `HOST_REVEALED` | Host (u1) | Revealed to everyone |
| `DEAD` | Dead player | Revealed; voting disabled |
| `ALIVE_HIDDEN` | Alive, not host | Hidden |
| `ALIVE_REVEALED` | Alive, not host | Revealed; can vote |
| `GUEST` | Not in game | Spectating |

---

### Switch day preset (keeps current players)

**UI buttons:** `Hidden` · `Revealed`

```js
__debug.dayPhase("HIDDEN")
__debug.dayPhase("REVEALED")
```

Unlike `dayScenario`, this keeps the current player list and only swaps the day sub-state. Useful after editing the player grid.

---

### Reveal night result

Triggered by tapping **显示结果 / Reveal Result** in the UI, or:

```js
__debug.dayReveal()
```

Transitions `dayPhase.subPhase` from `RESULT_HIDDEN` → `RESULT_REVEALED`. Kill banner appears and voting buttons unlock for all players.

---

## Typical Flows

### Full game flow from lobby

```js
// 1. In the browser — fill nickname, create room, then:
__debug.gameStart()      // triggers navigation to game view

// 2. After seeing the Role Reveal card, either tap "知道了" in the UI or skip:
__debug.roleSkip()       // → Sheriff Election SIGNUP
```

### Jump straight to a day scenario

```js
__debug.gameStart()
__debug.roleSkip()       // skip role reveal
__debug.sheriffExit()    // skip sheriff election → Day HIDDEN

// now load any scenario:
__debug.dayScenario("HOST_REVEALED")
```

### Test candidate controls

> **Note:** `roleSkip()` seeds Alice (`u2`) and Tom (`u6`) as candidates by default. Calling `sheriffCandidate` for a player already in the list is a no-op — remove them first to test adding.

```js
__debug.gameStart()
__debug.roleSkip()                               // → SIGNUP (Alice + Tom already candidates)

// Remove Alice, then re-add to see the add take effect
__debug.sheriffRemove("u2")                      // remove Alice
__debug.sheriffCandidate("u2", "Alice", "😊")   // re-add Alice

// Add Bob (not in the default list — this is a real add)
__debug.sheriffCandidate("u3", "Bob", "🎭")

__debug.sheriffPhase("SPEECH_CANDIDATE")         // advance to speech
```
