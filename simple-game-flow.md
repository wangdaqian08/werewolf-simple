# Werewolf Game Flow Test Scenarios

## Scenario A: Werewolves Win (12 Players, Hard Mode)

### Initial Setup
- 4 Werewolves, 1 Seer, 1 Witch, 1 Guard, 1 Hunter, 4 Villagers (12 total)

### Night 1
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill 1 Villager
- Seer checks 1 Werewolf
- Witch kills 1 Villager (poison)
- Guard protects self

**Audio:** `guard_close_eyes.mp3`

### Day 1
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 2 kills (2 Villagers dead)
**Remaining:** 10 players (4W, 1S, 1Witch, 1G, 1H, 2V)

**Vote Phase:**
- Players vote for Hunter
- Hunter is executed
- **Hunter revenge:** Hunter kills 1 Werewolf

**Remaining:** 9 players (3W, 1S, 1Witch, 1G, 0H, 2V)

---

### Night 2
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill 1 Villager
- Seer checks 1 player (not self)
- **Audio:** Only 1 `seer_close_eyes.mp3` during seer phase
- Witch saves the killed Villager
- Guard protects 1 Werewolf

**Audio:** `guard_close_eyes.mp3`

### Day 2
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 1 kill
**Remaining:** 9 players

---

### Night 3
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill Seer
- Seer phase runs as normal: Seer checks any player (not self)
- **Audio:** Only 1 `seer_close_eyes.mp3` during seer phase
- Witch phase: no action, player confirms, move to next phase
- Guard protects Seer (if not protected last round)

**Audio:** `guard_close_eyes.mp3`

### Day 3
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 0 kills
**Remaining:** 8 players (3W, 0S, 1Witch, 1G, 0H, 2V)

**Vote Phase:**
- Only 2 players vote on different players
- Others abstain
- Vote result triggers second round
- Everyone votes for Sheriff
- Sheriff transfers badge to 1 player

---

### Night 4
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill Witch
- Wolf confirms
- Move to Seer: Seer already dead
- **Audio:** `seer_open_eyes.mp3` → pause 2s → `seer_close_eyes.mp3`
- Move to Witch
- Move to Guard

### Day 4
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 1 kill
**Remaining:** 7 players (3W, 0S, 0Witch, 1G, 0H, 2V)

---

### Night 5
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill Guard
- Wolf confirms
- Move to Seer: Seer already dead
- **Audio:** `seer_open_eyes.mp3` → pause 2s → `seer_close_eyes.mp3` → pause 2s
- Move to Witch: Witch already dead
- **Audio:** `witch_open_eyes.mp3` → pause 2s → `witch_close_eyes.mp3` → pause 2s
- Move to Guard: Guard already dead
- **Audio:** `guard_open_eyes.mp3` → pause 2s → `guard_close_eyes.mp3` → pause 2s

### Day 5
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 1 kill
**Remaining:** 6 players (3W, 0S, 0Witch, 0G, 0H, 2V)

**Vote Phase:** Vote for another player (1 dies)
**Remaining:** 5 players (3W, 0S, 0Witch, 0G, 0H, 1V)

---

### Night 6
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill the Hunter (if hunter was remaining) OR last Villager
- If Hunter died: **Hunter revenge** - Hunter picks 1 target and kills 1 Werewolf
- Wolf confirms kill
- Seer phase: dead (audio only)
- **Audio:** `seer_open_eyes.mp3` → pause 2s → `seer_close_eyes.mp3` → pause 2s
- Witch phase: dead (audio only)
- **Audio:** `witch_open_eyes.mp3` → pause 2s → `witch_close_eyes.mp3` → pause 2s
- Guard phase: dead (audio only)
- **Audio:** `guard_open_eyes.mp3` → pause 2s → `guard_close_eyes.mp3` → pause 2s

### Day 6
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 1 kill
**Remaining:** 3-4 players depending on Hunter revenge

**Vote Phase:** Vote kills the last Villager

---

### Game Over: Werewolves Win
**Condition:** Werewolves (2) >= Villagers (0)
**Audio:** `game_over_werewolves_win.mp3`

---

## Scenario B: Villagers Win (12 Players, Hard Mode)

### Initial Setup
- 4 Werewolves, 1 Seer, 1 Witch, 1 Guard, 1 Hunter, 4 Villagers (12 total)

### Night 1
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill 1 Villager
- Seer checks 1 Werewolf (gets "bad" result)
- Witch saves the killed Villager
- Guard protects 1 random player (not self)

**Audio:** `guard_close_eyes.mp3`

### Day 1
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 0 kills (Witch saved the victim)
**Remaining:** 12 players

**Discussion Phase:**
- Seer hints at knowing a werewolf (without revealing identity)

**Vote Phase:**
- Players vote for 1 Werewolf based on Seer's hint and behavior
- 1 Werewolf is executed

**Remaining:** 11 players (3W, 1S, 1Witch, 1G, 1H, 4V)

---

### Night 2
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill the Seer (targeting confirmed good player)
- Seer checks another Werewolf (not self, not checked before)
- **Audio:** Only 1 `seer_close_eyes.mp3` during seer phase
- Witch has no action (save used)
- Guard protects the Seer (if not protected last round)

**Audio:** `guard_close_eyes.mp3`

### Day 2
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 0 kills (Guard protected Seer)
**Remaining:** 11 players

**Vote Phase:**
- Players vote for another Werewolf
- 1 Werewolf is executed

**Remaining:** 10 players (2W, 1S, 1Witch, 1G, 1H, 4V)

---

### Night 3
**Audio Sequence:**
1. `goes_dark_close_eyes.mp3`
2. `wolf_howl.mp3`
3. `wolf_open_eyes.mp3`

**Actions:**
- Wolves kill the Guard
- Seer checks another player (Villager - "good" result)
- **Audio:** Only 1 `seer_close_eyes.mp3` during seer phase
- Witch poisons 1 Werewolf
- Guard protects self (if allowed) or random player

**Audio:** `guard_close_eyes.mp3`

### Day 3
**Audio Sequence:**
1. `day_time.mp3`
2. `rooster_crowing.mp3`

**Reveal:** 2 kills (Guard died + Witch poisoned Werewolf)
**Remaining:** 8 players (1W, 1S, 1Witch, 0G, 1H, 4V)

**Vote Phase:**
- Players vote for the last Werewolf
- 1 Werewolf is executed

**Remaining:** 7 players (0W, 1S, 1Witch, 0G, 1H, 4V)

---

### Game Over: Villagers Win
**Condition:** All Werewolves eliminated (0 Werewolves remaining)
**Audio:** `game_over_villagers_win.mp3`

---

## Audio Asset Reference

| Filename | Usage |
|----------|-------|
| `goes_dark_close_eyes.mp3` | Night start - all players close eyes |
| `wolf_howl.mp3` | Werewolf phase intro |
| `wolf_open_eyes.mp3` | Werewolves wake up |
| `wolf_close_eyes.mp3` | Werewolves close eyes |
| `seer_open_eyes.mp3` | Seer wakes up |
| `seer_close_eyes.mp3` | Seer closes eyes |
| `witch_open_eyes.mp3` | Witch wakes up |
| `witch_close_eyes.mp3` | Witch closes eyes |
| `guard_open_eyes.mp3` | Guard wakes up |
| `guard_close_eyes.mp3` | Guard closes eyes |
| `day_time.mp3` | Day phase begins |
| `rooster_crowing.mp3` | Morning announcement |
| `game_over_werewolves_win.mp3` | Werewolves victory |
| `game_over_villagers_win.mp3` | Villagers victory |

## Dead Role Audio Pattern

When a role is dead, the audio still plays with 2-second pauses:

```
{role}_open_eyes.mp3 → [pause 2s] → {role}_close_eyes.mp3 → [pause 2s]
```

This applies to: Seer, Witch, Guard phases when the respective player is dead.

## Critical Test Cases

1. **Multiple deaths in one night** (Werewolf kill + Witch poison)
2. **Hunter revenge on day death** (immediate trigger after execution)
3. **Witch save preventing death** (0 kills revealed)
4. **Guard protecting target** (0 kills if protected player was targeted)
5. **Guard protecting werewolf** (does not prevent wolf from killing)
6. **0 kills night** (all protections aligned)
7. **Dead role audio handling** (open → pause → close for each dead role)
8. **Hunter revenge on night death** (triggered when Hunter dies at night)
9. **Game over detection** (Werewolves >= Villagers OR Werewolves = 0)
10. **Sheriff election after tied vote** (second round voting)
11. **Sheriff badge transfer** (sheriff transfers to another player)
12. **Seer checking same player twice** (should be prevented by backend)
13. **Witch saving self** (if Witch was the target)
14. **Guard protecting same person consecutive nights** (rule variation)
