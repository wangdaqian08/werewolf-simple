# Role × Phase Screen Specifications

For each role, every screen they encounter throughout a full game is described here. Use this file as the definitive UI
spec for each role's view at each phase/subphase.

**Background rule:** Night phases use `--ink` (`#2a1f14`) background with paper-white text. Day/voting/lobby phases use
`--bg` (`#ede8df`).

---

## ROLE: WEREWOLF

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink (dark)
- **Shows:**
    - Role name: "狼人" (in `--red`, Noto Serif SC)
    - Role description: "每晚与狼队友合作，选择一名村民击杀"
    - Wolf teammate list: names of all other werewolves in the game
    - Flavor icon: wolf silhouette
- **Action:** `[确认]` button → emits `CONFIRM_ROLE`
- **Note:** Card stays visible until player taps confirm; server waits for all CONFIRM_ROLE before proceeding

---

### Screen: SHERIFF_ELECTION / SIGNUP

- **Component:** `SheriffElection`
- **Background:** parchment (`--bg`)
- **Shows:**
    - Title: "警长选举 — 报名阶段"
    - Running list of players who have signed up (names appear as they sign up)
    - Player's own signup status (signed up / not signed up)
- **Actions:**
    - `[参选]` → `SHERIFF_CAMPAIGN` (if not yet signed up)
    - `[退出参选]` → `SHERIFF_QUIT` (if already signed up)
- **Note:** Host ends signup manually (or after timeout). Non-host sees no "advance" button.

---

### Screen: SHERIFF_ELECTION / SPEECH

- **Component:** `SheriffElection`
- **Shows:**
    - Ordered list of candidates
    - Current speaker name highlighted
    - Speech order indicator ("1 / N")
- **Actions (host only):**
    - `[开始发言]` → `SHERIFF_START_SPEECH` (to start first speaker)
    - `[下一位]` → `SHERIFF_ADVANCE_SPEECH` (to move to next candidate)
- **Actions (non-host):** None — waiting state

---

### Screen: SHERIFF_ELECTION / VOTING

- **Component:** `SheriffElection`
- **Shows:** Candidate list with vote buttons
- **Actions:** `[投票给 <name>]` → `SHERIFF_VOTE` (if not a candidate themselves; candidates abstain)
- **Note:** Werewolves can strategically vote for or against any candidate

---

### Screen: SHERIFF_ELECTION / RESULT

- **Component:** `SheriffElection`
- **Shows:** Elected sheriff's name + badge icon; tally if revealed
- **Actions (host only):** `[公布结果]` → `SHERIFF_REVEAL_RESULT`
- **Actions (others):** None

---

### Screen: NIGHT / WEREWOLF_PICK (active)

- **Component:** `NightPhase`
- **Background:** ink (dark)
- **Shows:**
    - Title: "狼人行动"
    - Grid of alive non-wolf players (profile icon + name per card)
    - Wolf teammates also visible in a sidebar/banner (grayed out, not targetable)
    - Pending pick indicator if wolf teammate has already picked (but final lock requires consensus)
- **Actions:** Tap player card → `WOLF_KILL(targetUserId)` (must agree with wolf teammates; last wolf to confirm locks
  the pick)
- **Constraint:** Cannot target another werewolf

---

### Screen: NIGHT / SEER_PICK (waiting)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:** "预言家正在查验…" spinner
- **Actions:** None

---

### Screen: NIGHT / SEER_RESULT (waiting)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:** "预言家正在确认结果…" spinner
- **Actions:** None

---

### Screen: NIGHT / WITCH_ACT (waiting)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:** "女巫正在行动…" spinner
- **Actions:** None

---

### Screen: DAY / RESULT_HIDDEN

- **Component:** `DayPhase`
- **Background:** parchment
- **Shows:** "等待主持人公布昨夜结果"
- **Actions:** None (host sees `[公布结果]` → `REVEAL_NIGHT_RESULT`)

---

### Screen: DAY / RESULT_REVEALED

- **Component:** `DayPhase`
- **Shows:**
    - Deaths: list of eliminated player names and seats (if any)
    - Or: "昨夜平安" if no deaths
    - Sheriff badge indicator if applicable
- **Actions:** None (host sees `[进入讨论]` → `DAY_ADVANCE`)

---

### Screen: VOTING / VOTING

- **Component:** `VotingPhase`
- **Shows:** Alive player list (self included; cannot vote for self)
- **Actions:** Tap player → `SUBMIT_VOTE(targetUserId)` (one vote, no change after submit)
- **Note:** Werewolves try to vote out villagers/special roles

---

### Screen: VOTING / VOTE_RESULT

- **Component:** `VotingPhase`
- **Shows:**
    - Vote tally table: each player's vote count
    - Eliminated player name (or "平票，无人出局" on tie)
    - Eliminated player's role revealed
- **Actions (host only):** `[VOTING_CONTINUE]` to advance phase

---

### Screen: GAME_OVER

- **Component:** `ResultView`
- **Background:** parchment
- **Shows:**
    - Winner banner: "狼人获胜" (red) or "好人获胜" (green)
    - Full role reveal table: all players with their roles
    - Game statistics (rounds played, deaths)
- **Actions:** `[返回大厅]` → navigate to Lobby

---

## ROLE: SEER

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink
- **Shows:**
    - Role name: "预言家" (gold text)
    - Role description: "每晚可查验一名玩家的身份，判断其是否为狼人"
    - Flavor icon: eye / crystal ball
- **Action:** `[确认]` → `CONFIRM_ROLE`

---

### Screen: NIGHT / WEREWOLF_PICK (waiting)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:** "狼人正在行动…" with eye-closed animation
- **Actions:** None

---

### Screen: NIGHT / SEER_PICK (active)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:**
    - Title: "预言家 — 请选择查验目标"
    - List of all alive players except self (self is not checkable)
    - Previously checked players shown with result icons (wolf / not-wolf) — persists across nights
- **Actions:** Tap player → `SEER_CHECK(targetUserId)`
- **Constraint:** Cannot check self; cannot check same player twice (rejected by backend)

---

### Screen: NIGHT / SEER_RESULT (active)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:**
    - Checked player name
    - Result: "是狼人！" (red, wolf icon) or "不是狼人" (green, villager icon)
    - Private — only visible to this player (event from `/user/queue/private`)
- **Actions:** `[确认]` → `SEER_CONFIRM`

---

### Screen: NIGHT / WITCH_ACT (waiting)

- **Component:** `NightPhase`
- **Shows:** "女巫正在行动…" spinner
- **Actions:** None

---

### Screen: DAY / RESULT_REVEALED

- **Component:** `DayPhase`
- **Shows:** Same as all players — deaths list or peaceful night
- **Note:** Seer must decide whether to reveal their check result during discussion (social gameplay)

---

### Screen: VOTING / VOTING

- **Component:** `VotingPhase`
- **Actions:** `[SUBMIT_VOTE(target)]` — seer uses knowledge from nightly checks to vote strategically

---

### Screen: Eliminated (spectator)

- **Component:** `Eliminated` overlay
- **Background:** darkened overlay on current phase background
- **Shows:**
    - "你已出局" banner
    - Own role card (smaller)
    - Game continues in read-only mode — can see all public events but cannot act

---

## ROLE: WITCH

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink
- **Shows:**
    - Role name: "女巫" (purple/gold text)
    - Role description: "拥有一瓶解药和一瓶毒药，可各使用一次"
    - Antidote count: 1, Poison count: 1
    - Flavor icon: potion bottles
- **Action:** `[确认]` → `CONFIRM_ROLE`

---

### Screen: NIGHT / WITCH_ACT (active)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:**
    - Wolf kill target name: "今晚 [Name] 被狼人袭击"
    - Antidote section (if antidote not yet used):
        - `[使用解药]` → `WITCH_ACT(action: "antidote")` — saves wolf target
    - Poison section (if poison not yet used):
        - Player list for poison target selection
        - `[使用毒药 → <name>]` → `WITCH_ACT(action: "poison", targetUserId: id)`
    - `[跳过]` → `WITCH_ACT(action: "skip")`
- **Constraints:**
    - Cannot use antidote if already consumed
    - Cannot use poison if already consumed
    - Cannot use both antidote and poison on same night (backend enforces)
    - Witch cannot poison themselves (backend rejects)

---

### Screen: NIGHT / WITCH_ACT (no antidote, poison available)

- Same as above but antidote section shows "解药已用完" (grayed out, no button)

---

### Screen: NIGHT / WITCH_ACT (no potions left)

- Shows: "你的药水已全部用完" — only `[跳过]` available

---

## ROLE: HUNTER

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink
- **Shows:**
    - Role name: "猎人" (amber text)
    - Role description: "当你被淘汰时，可以选择带走场上任意一名玩家"
    - Flavor icon: crossbow / gun
- **Action:** `[确认]` → `CONFIRM_ROLE`

---

### Screen: VOTING / HUNTER_SHOOT (active — when eliminated by vote)

- **Component:** `VotingPhase` (hunter shoot overlay)
- **Background:** parchment with red accent border
- **Shows:**
    - "你被投票出局，猎人技能触发！"
    - "选择带走一名玩家，或放弃技能"
    - List of all alive players
- **Actions:**
    - Tap player → `HUNTER_SHOOT(targetUserId)`
    - `[放弃技能]` → `HUNTER_SKIP`
- **Constraint:** Cannot shoot self (already eliminated); backend rejects self-target

---

### Screen: VOTING / HUNTER_SHOOT (waiting — for all other players)

- **Component:** `VotingPhase`
- **Shows:** "猎人正在选择目标…" spinner
- **Actions:** None

---

### Screen: NIGHT (eliminated) — hunter eliminated at night

- If hunter is wolf kill target:
    - Hunter shoot does NOT trigger on night elimination — only triggers on vote elimination
    - Hunter sees eliminated overlay

---

## ROLE: GUARD

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink
- **Shows:**
    - Role name: "守卫" (blue/silver text)
    - Role description: "每晚保护一名玩家，被保护的玩家当晚不会被狼人杀死。不能连续两晚保护同一人"
    - Flavor icon: shield
- **Action:** `[确认]` → `CONFIRM_ROLE`

---

### Screen: NIGHT / GUARD_PICK (active)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:**
    - Title: "守卫 — 请选择保护目标"
    - List of all alive players (including self — guard can protect self)
    - Last-protected player is greyed out / marked "上晚已守护" and is not selectable
    - First night: no restriction
- **Actions:**
    - Tap player → `GUARD_PROTECT(targetUserId)`
    - `[跳过]` → `GUARD_SKIP`
- **Constraint:** Backend rejects protection of same player two nights in a row (returns error)

---

### Screen: NIGHT / GUARD_PICK (rejected — same player attempted)

- Backend returns rejection event
- Frontend shows inline error: "不能连续两晚守护同一名玩家，请重新选择"
- Player selection resets; guard must pick again

---

## ROLE: VILLAGER

### Screen: ROLE_REVEAL

- **Component:** `RoleRevealCard`
- **Background:** ink
- **Shows:**
    - Role name: "平民" (neutral text)
    - Role description: "通过讨论和投票，找出并淘汰所有狼人"
    - Flavor icon: person silhouette
- **Action:** `[确认]` → `CONFIRM_ROLE`

---

### Screen: All NIGHT subphases (waiting)

- **Component:** `NightPhase`
- **Background:** ink
- **Shows:**
    - "夜晚降临，请闭眼" — eyes-closed message
    - Phase name of current active role: e.g., "狼人正在行动…"
- **Actions:** None for any night subphase

---

### Screen: DAY / RESULT_HIDDEN

- **Component:** `DayPhase`
- **Shows:** "等待主持人公布结果" with pulsing indicator
- **Actions:** None

---

### Screen: DAY / RESULT_REVEALED

- **Component:** `DayPhase`
- **Shows:**
    - Deaths or "平安夜"
    - Discussion prompt: "请发表你的看法"
- **Actions:** None (discussion is physical/voice, not in app)

---

### Screen: VOTING / VOTING

- **Component:** `VotingPhase`
- **Shows:** Alive player list; submit vote UI
- **Actions:** `[SUBMIT_VOTE(target)]`

---

### Screen: Eliminated (spectator)

- **Component:** `Eliminated` overlay
- **Shows:** "你已出局" banner; read-only view of ongoing game
- **Actions:** None (cannot vote, cannot act)

---

## Eliminated Player — Common Spectator Screen

Applies to any role after elimination (day vote, night kill, hunter shot, witch poison).

- **Component:** Current phase component with `Eliminated` overlay
- **Background:** Current phase background, dimmed
- **Shows:**
    - "你已出局" top banner (persistent)
    - Own role card displayed (smaller, in corner)
    - All public events continue to stream in (they can see everything public)
    - Private events (e.g., seer results) are no longer sent
- **Actions:** None — fully spectating
- **Navigation:** Can see `[返回大厅]` only after GAME_OVER
