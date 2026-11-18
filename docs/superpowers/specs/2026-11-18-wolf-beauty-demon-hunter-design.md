# 狼美人 (Wolf Beauty) and 猎魔人 (Demon Hunter): game design

- Date: 2026-11-18. Status: **rules confirmed by the owner (O1–O6, A–G); M decided; detail round Q1–Q14 decided; technical design checked line by line against main `b5de135` — no code yet.**
- Two roles, two PRs (one role each, TDD). **Owner, 2026-11-18: build 狼美人 now; 猎魔人 is designed but deferred.**

## Why

The owner chose these two as the next roles on 2026-11-18. They were preferred over 混血儿, whose camp is only
decided during the game, which breaks the fixed `PlayerRole.isWolf` model behind the win check, settlement and
tasks.

- **狼美人** adds play to the wolf side. Recent roles (骑士、守墓人) were mostly good-side.
  - Its published board is **狼美人骑士** (12 players: 预言家、女巫、守卫、骑士、4 平民 / 3 狼人 + 狼美人).
  - Every role on that board already exists.
- **猎魔人** is a good-side god. Its rules are clear, and it reuses the existing "pick at night → resolve
  deaths at dawn" pattern.
  - Its published board partner (血月使徒) is not in the game, so it is used in custom boards.

**No extra role is needed for balance.** The existing setup limits cover both:
- 狼美人 is a special wolf:
  - the role page requires 9+ players (`RoomSetupPanel.vue:243-249`);
  - the role page **and** the backend require wolves ≥ special wolves + 1 (`RoomService.kt:505-520`).
- 猎魔人 is a god: the role page keeps gods within [wolves − 1, wolves + 1] (`RoomSetupPanel.vue:270-278`).
  - This is a frontend policy only: the backend checks just "wolves + gods ≤ seats" (`RoomService.kt:450-452`).

## Sources (checked 2026-11-18)

1. [游民岛: 骑士决斗狼美人可以殉情吗](https://www.youmindao.com/langrensha/211.html)
2. [LAL 狼人杀: 狼美骑士版型规则](https://werewolves.games/lang-mei-qi-shi/)
3. [口袋狼人杀: 狼美人介绍](https://www.langrensha.net/strategy/2023031001.html)
4. [维基学院: 狼美人](https://zh.wikiversity.org/zh-my/%E7%8B%BC%E4%BA%BA%E6%AE%BA/%E9%81%8A%E6%88%B2%E8%A7%92%E8%89%B2/%E7%8B%BC%E7%BE%8E%E4%BA%BA)
5. [游民岛: 狼美人规则](http://www.youmindao.com/langrensha/185.html), [87G](https://www.87g.com/lrs/60500.html)
6. [维基学院: 猎魔人](https://zh.wikiversity.org/wiki/%E7%8B%BC%E4%BA%BA%E6%AE%BA/%E9%81%8A%E6%88%B2%E8%A7%92%E8%89%B2/%E7%8D%B5%E9%AD%94%E4%BA%BA)
7. [百度百科: 猎魔人（网易）](https://baike.baidu.com/item/%E7%8C%8E%E9%AD%94%E4%BA%BA/24158357) (search summary only: the page returns 403)
8. [腾讯新闻: 狼人杀规则总结](https://news.qq.com/rain/a/20210428A0AW0L00) (the guard blocks only the wolf knife)
9. [维基百科: 狼人殺](https://zh.wikipedia.org/wiki/%E7%8B%BC%E4%BA%BA%E6%AE%BA): 「被狼美人魅惑而殉情的玩家比狼美人先死亡」
10. [口袋狼人杀: 狼美人最后出局能殉情带人吗](https://www.langrensha.net/strategy/2022122701.html): 「狼美人最后出局能殉情带人……狼人阵营会获得最后的胜利」

## Owner decisions (2026-11-18)

| # | Decision |
|---|---|
| O1 | 狼美人 **cannot charm on night 1**. |
| O2 | 狼美人 **cannot charm the same player two nights in a row**. |
| O3 | 狼美人 poisoned at night → the charmed player **still 殉情**. Write this clearly in the role text. |
| O4 | 狼美人 **cannot charm a wolf teammate**. |
| O5 | The guard **cannot** block a hunt. |
| O6 | Only one new exclusion: **机械狼 ↔ 猎魔人**. 狼美人 gets none. |

## 狼美人 `WOLF_BEAUTY` — rules

| # | Rule | Basis |
|---|---|---|
| W1 | Wolf camp, a **pack wolf**. It knows the pack, joins the wolf discussion and kill, and counts as a wolf for every win check. It is a special wolf: same limits as 白狼王 / 黑狼王 / 机械狼 / 石像鬼. | sources 2, 4 |
| W2 | Each night from **night 2**, after the wolf kill, it wakes alone and may charm one living player. The step plays every night. When it is dead, the step runs on the dead-role delay. On **night 1**, a living 狼美人 sees 「首夜不能魅惑」 and must tap 確認, which sends `WOLF_BEAUTY_SKIP`: the night loop waits for an action from every living role (`NightOrchestrator.kt:713-749`), the same as the gravekeeper's night-1 confirm. | O1, sources 3, 4 |
| W3 | Charm target: alive, not itself, **not a pack wolf** (the server rejects them; the UI greys them out), and **not last night's target**. It may skip (C). The 石像鬼 and 机械狼 are not in the pack, so it may charm them (D). It may charm tonight's knife target; there is no rejection and no warning (Q5). | O2, O4, C, D, Q5 |
| W4 | The charm is private. The pack, the charmed player and the shared screen are not told. A player who 殉情 gets no private notice of the cause (Q6). | sources 2, 4 ("单独睁眼") |
| W5 | **Only the latest night's charm counts.** If it charmed A on night 2 and nobody on night 3, nobody dies with it on day 3. | sources 4, 2 ("上一夜/当晚被魅惑") |
| W6 | When 狼美人 is out, the charmed player is out too (**殉情**) and **cannot use any skill**: a charmed 猎人 does not shoot. | sources 3, 4 |
| W7 | 殉情 happens for **every** way 狼美人 goes out (vote-out, hunter or 黑狼王 shot, 白狼王 take, any poison: the witch's or a mechanical wolf's learned poison, 猎魔人 hunt) **except**: a **骑士 duel** (no 殉情, D2), and a **host declare-out** (the player left the game; that is not a death in play). | O3, source 1, D2 |
| W8 | It **cannot self-destruct**. The wolves **cannot knife it** (不能自刀); the server rejects that kill target. Why (Q1): a knife on 狼美人 would kill two players in one night (her and the charmed player), at a time the wolves choose, and a self-destruct would let it take the charmed player by day whenever it likes. With both banned, a 殉情 only happens when someone else removes 狼美人. Other pack wolves may still be knifed (to bait the antidote or fake a good role). | sources 2, 3, 4, 5; Q1 |
| W9 | The guard, the witch's antidote and a mechanical wolf's learned guard shield do not stop a 殉情. | source 2 (the charm is not a wolf knife); M |
| W10 | The seer and the gravekeeper see it as a wolf. The medium and the gargoyle see `WOLF_BEAUTY`. A mechanical wolf that checks it learns no skill, like every other wolf. | existing role rules |
| W11 | A night 殉情 joins the dawn death list (seat order, no cause, as today; source 3/百度百科: a poisoned 狼美人's 殉情 is not named). A day 殉情 happens at once when 狼美人 goes out, and the shared screen says **「N號（狼美人）出局，M號殉情出局」** (Q13; the sources say it flips its card). When nobody 殉情 (night 1, no charm last night, or the charmed player is already dead), its exit looks like any other exit and its role is **not** shown (Q14). | B, Q13, Q14 |
| W12 | A charmed sheriff who 殉情 still hands over the badge (G). The badge is not a role skill. It is handed over like the voted-out, shot, duelled or taken sheriff today. A self-destructing or declared-out sheriff's badge is destroyed instead (`SelfDestructService.kt:106-110`, `HostDeclareOutService.kt:93-97`). | `BADGE_HANDOVER` |
| W13 | The gravekeeper's "yesterday's vote-out" is 狼美人 when it was voted out. The 殉情 player is never a vote-out. | gravekeeper rules |
| W14 | The post-game review shows "N號（狼美人）魅惑 M號" each night, and labels every 殉情 (day or night) as 殉情 (Q2). The public 游戏记录 shows only deaths. | gargoyle G15 pattern, Q2 |
| W15 | **The 殉情 resolves first** (source 9: the charmed player dies before 狼美人). When 狼美人 goes out: the charmed player goes out → a win check that counts **only a side fully wiped out** → if no winner, 狼美人 goes out → the normal win check. So a last-wolf 狼美人 whose 殉情 takes the last god or the last villager **wins for the wolves** (source 10, 「狼刀优先」). The "wolves ≥ humans, no counterplay" HARD_MODE shortcut is never used in the first check (Q12). Other simultaneous deaths (e.g. knife + poison) keep today's rule: the villager win is checked first. | Q11, Q12; sources 9, 10 |

### Edge cases (resolved by the rules above)

- **The charmed 猎人 is also knifed that night**, and 狼美人 dies the same night (poison or hunt): no shot.
  W6 wins, the same as a poisoned 猎人 today.
- **狼美人 is the last wolf and is voted out, and the 殉情 takes the last god**: the **wolves** win (W15).
- **The same, in HARD_MODE, with 2 good players left and only 狼美人**: the 殉情 takes one; 1 v 1 does **not**
  end the game in the first check (no shortcut there, Q12). 狼美人 then goes out, the villagers win.
- **A voted-out 猎人 shoots a last-wolf 狼美人, and the 殉情 takes the last god**: the wolves win (W15).
- **A voted-out 猎人 shoots 狼美人**: it dies → 殉情 → win check.
- **The charmed player is the 白痴**: it dies. The 白痴 is only protected from the vote.
- **The charmed player is a knight that has not dueled**: it dies and cannot duel.

## 猎魔人 `DEMON_HUNTER` — rules

| # | Rule | Basis |
|---|---|---|
| H1 | Good camp, a **god**. It counts as a god for 屠边, the god cap and the role-pick card. | sources 6, 7 |
| H2 | From **night 2**, each night it may hunt one living player other than itself, or skip. The step plays every night. When it is dead, the step runs on the dead-role delay. On **night 1**, a living 猎魔人 sees 「首夜不能狩獵」 and must tap 確認 (`DEMON_HUNTER_SKIP`), for the same reason as W2. | sources 6, 7 |
| H3 | The target is a **wolf** (any wolf role) → the target dies. The target is **good** → the 猎魔人 dies. Resolved at dawn with the other night deaths. | sources 6, 7 |
| H4 | **The witch's poison cannot kill it.** The poison is still used up. | sources 3, 6, 7 |
| H5 | The **guard** and the witch's antidote do **not** stop a hunt death, either the target's or the 猎魔人's own. | O5, source 8 |
| H6 | A hunted 黑狼王 **does not shoot** (A). This is **not** fully automatic. `DayRevealAdvancer.nightShooterId` (`:64-75`) grants a shot to the wolf-knife victim, so a 黑狼王 that the wolves knifed **and** the 猎魔人 hunted on the same night would get a shot. Add one guard next to the existing poison guard (`:68`): wolf target == tonight's hunt death → no shot. | analogy: a poisoned 狼王 cannot shoot |
| H7 | A hunted 狼美人 → its charmed player 殉情 (W7). | O3 |
| H8 | It may hunt a player the wolves knifed that night. The deaths resolve together. | engine (deaths resolve at dawn) |
| H9 | **Exclusion: 机械狼 ↔ 猎魔人** (`BLOCKED_WITH`, `RoomService.validateRoleComposition`, start-game check, and the god maximum leaves out a blocked god). | O6, D4 pattern |
| H10 | The seer and the gravekeeper see it as good; the medium and the gargoyle see `DEMON_HUNTER` (a god, so it counts toward the gargoyle's "2 gods" task). | existing role rules |
| H11 | The post-game review shows "N號（猎魔人）狩獵 M號：狼人/好人". The public 游戏记录 shows only deaths. | gargoyle G15 pattern |
| H12 | No private result screen (Q4). The dawn tells the 猎魔人 the answer: alive → the target was a wolf (a hunt cannot be blocked, so a wolf target always dies); dead → the target was good. | Q4 |

## Role texts

The owner is worried that role intros keep getting longer (2026-11-18). The app already shows role text in three
layers (`i18n/locales/*.ts`, `role.*`):

| Key | Where it shows | Length rule for the new roles |
|---|---|---|
| `descShort` | the role-details sheet header (`roleDescription(id, true)`, `RoleCardSheet.vue:18`, its only caller) | **one line** |
| `desc` | the role card a player sees when roles are dealt (`RoleRevealCard.vue:23`) | **at most two short sentences**: what the role does, plus the one rule a player must not miss |
| `rules` | the 規則 list in the role-details sheet, one line each (`RoleCardSheet.vue:40-43`, split on `\n`) | **every rule goes here**, including M and all edge cases; one rule per line |

The excluded roles are shown automatically from `BLOCKED_WITH` (`RoleCardSheet.vue:31-38`), so they are not
repeated in `rules`.

### 狼美人 `WOLF_BEAUTY`

- `descShort`: 每晚魅惑一名玩家；你出局時，被魅惑的玩家殉情。
- `desc`: 夜晚與狼隊一起襲擊，第二晚起每晚可魅惑一名玩家。你出局時，最近一晚被魅惑的玩家隨之殉情。
- `rules`:
  1. 陣營：狼人陣營（屬於狼隊）。
  2. 夜晚與狼隊一起襲擊。預言家、守墓人查驗你的結果是狼人，通靈師、石像鬼查驗顯示為狼美人。
  3. 第二晚起，狼人襲擊後你單獨睜眼，可魅惑一名存活玩家，也可以跳過；首夜不能魅惑。
  4. 不能魅惑自己或狼隊友，不能連續兩晚魅惑同一名玩家。
  5. 你出局時，最近一晚被你魅惑的玩家隨之殉情出局，且不能發動技能（例如獵人不能開槍）；更早的魅惑不算。
  6. 被投出、被開槍帶走、被白狼王帶走、被毒死（女巫或機械狼）、被獵魔人狩獵時都會殉情；只有被騎士決鬥或被房主判定出局時不會殉情。（狼美人 PR 先不寫「被獵魔人狩獵」，由獵魔人 PR 加上。）
  7. 守衛、女巫解藥、機械狼學到的守護都擋不住殉情。
  8. 白天殉情時公布「N號（狼美人）出局，M號殉情出局」；夜晚殉情和其他死訊一起公布，不顯示死因；沒有人殉情時不公開你的身份。殉情的玩家是警長時照常移交警徽。
  9. 不能自爆；狼隊不能襲擊你。
  10. 殉情先於你出局結算：殉情若帶走最後一名神職或最後一名村民，即使你是最後一隻狼，狼人陣營也獲勝。
- en:
  - `descShort`: Charms one player each night; when you go out, the charmed player goes with you.
  - `desc`: Kills with the pack at night, and from night 2 may charm one player each night. When you go out, the player charmed on the latest night goes out with you.
  - `rules`, the same ten lines in English.

### 猎魔人 `DEMON_HUNTER`

- `descShort`: 第二晚起每晚可狩獵一人：獵到狼人則狼人出局，獵到好人則你出局。
- `desc`: 第二晚起，每晚可狩獵一名玩家：對方是狼人則對方出局，是好人則你出局。女巫的毒藥毒不死你。
- `rules`:
  1. 陣營：好人陣營（神職）。
  2. 第二晚起，每晚可狩獵一名存活玩家（不能是自己），也可以跳過；首夜不能狩獵。
  3. 對方是狼人（包括狼王、狼美人、石像鬼）：對方出局；對方是好人：你出局。天亮時和其他死訊一起公布，不顯示死因。
  4. 女巫的毒藥毒不死你，但毒藥照樣算用掉。
  5. 守衛、女巫解藥都擋不住狩獵造成的死亡。
  6. 被你狩獵出局的黑狼王不能開槍；被你狩獵出局的狼美人，她魅惑的玩家照常殉情。
  7. 預言家、守墓人查驗你的結果是好人，通靈師、石像鬼查驗顯示為獵魔人。
- en:
  - `descShort`: From night 2, hunt one player each night: a wolf goes out, a good player means you go out.
  - `desc`: From night 2, may hunt one player each night: a wolf target goes out; a good target means you go out. The witch's poison cannot kill you.
  - `rules`, the same seven lines in English.

### Existing roles: only lines that would become wrong

Change only the `rules` lines that would be **false** once a new role is on the board. Leave every `desc` and
`descShort` alone, so no existing intro gets longer. The full exception lists already live in `rules`.

| Role | Current `rules` text | Change | Which PR |
|---|---|---|---|
| 狼人 | 可以襲擊任何存活玩家，包括隊友或自己（自刀）。 | add 「（狼美人除外）」 | 狼美人 |
| 獵人 | 被女巫毒死或被白狼王帶走時不能開槍 | 被女巫毒死、被白狼王帶走或殉情時不能開槍 | 狼美人 |
| 黑狼王 | 被女巫毒死、自爆、被白狼王帶走或被騎士決鬥時不能開槍 | add 「被獵魔人狩獵」 | 猎魔人 |
| 守墓人 | 被開槍、被決鬥、被白狼王帶走、被房主判定出局、白癡翻牌都不算 | add 「殉情」 | 狼美人 |

The 獵人 `desc` (「被女巫毒死或被白狼王帶走時無法開槍」) stays as it is. It does not list every case today
either: 「你的出局已決定勝負時也不能開槍」 is only in `rules`.

## Night order

The wake order is each handler's `@Order` (`NightOrchestrator.nightSequence`, `:154-163`). It is the order of
the injected `handlers` list. On main today:

```
石像鬼 (0) → 狼人 (1) → 机械狼 (2) → 女巫 (3) → 预言家 (4) → 通灵师 (5) → 守墓人 (6) → 守卫 (7)
```

The witch wakes **before** the seer, as pinned by `AudioPhaseMappingTest.kt:294` (WITCH_ACT → SEER_PICK).

The new order:

```
石像鬼 (0) → 狼人 (1) → 狼美人 (2) → 机械狼 (3) → 女巫 (4) → 预言家 (5) → 通灵师 (6) → 守墓人 (7) → 猎魔人 (8) → 守卫 (9)
```

- 狼美人 comes straight after the wolf kill, as the sources say ("刀人后单独睁眼"). `@Order` values are
  integers and no slot is free between 1 and 2, so the 狼美人 PR shifts 机械狼 … 守卫 up by one. Only the relative order
  matters (Spring sorts the list); the non-night handlers 平民 (10), 猎人 (11) and 白痴 (12) are unaffected.
- 猎魔人's place changes no information, because every death resolves at dawn. It goes after the gravekeeper
  and before the guard (in the 猎魔人 PR: after the 狼美人 shift the guard is 8, so 猎魔人 takes 8 and the guard moves to 9). The guard stays last, which the code comments describe
  (`NightOrchestrator.kt:63`, `:448`). The morning-cue delay itself runs after whichever role is last, so this
  is not a hard constraint.
- Both steps play their open/close narration every night, including night 1 and when dead (ADR-010
  dead-role rule; dead path `NightOrchestrator.kt:750-765`, same total delay).

## Anti-goals (a failure even if it works)

- **A tell in the night flow.** Order, timing or sub-phase changes that depend on whether the role is alive,
  acted or skipped.
- **A charm leak.** The pack, the charmed player, the state API or the shared screen learn the charm target
  before a 殉情.
- **A cause leak at dawn.** The dawn list shows which death was a hunt, a poison or a 殉情.
- **Regressions.** Any existing board plays differently.

## Owner decisions, round 2 (2026-11-18: "同意建议")

| # | Decision |
|---|---|
| A | A 黑狼王 hunted by the 猎魔人 **does not shoot**. |
| B | When 狼美人 goes out by day, the shared screen announces the 殉情 (wording refined by Q13: 「N號（狼美人）出局，M號殉情出局」). A night 殉情 is a plain name in the dawn list. |
| C | 狼美人 **may skip** the charm. |
| D | 狼美人 **may charm the 石像鬼 or the 机械狼** (it does not know them). They then 殉情 like anyone else. |
| E | A living 猎魔人 **counts as HARD_MODE counterplay**. |
| F | New 单局任务: 狼美人 `WOLF_BEAUTY_LINK_GOD` 「殉情帶走一名神職」 **20**; 猎魔人 `DEMON_HUNTER_HUNT_WOLF` 「狩獵到一隻狼人」 **10**. |
| G | A charmed sheriff who 殉情 **hands over the badge**. |

## Owner decision, round 3 (2026-11-18)

| # | Decision |
|---|---|
| M | A mechanical wolf's learned guard shield **does not stop** a 殉情, the same as the guard (W9). Write it in the role's rules. |

## Owner decisions, round 4 — detail review (2026-11-18)

| # | Decision |
|---|---|
| Q1 | Keep W8 (the wolves cannot knife 狼美人), with the reason written in W8. |
| Q2 | A night 殉情 also records a `CHARM_DEATH` event. The 狼美人 task counts it; the review labels it; the dawn list still shows no cause. |
| Q3 | Night 1: a living 狼美人 / 猎魔人 taps 確認 (SKIP), like the gravekeeper. |
| Q4 | 猎魔人 gets no private result screen (H12). |
| Q5 | 狼美人 may charm tonight's knife target; no rejection, no warning. |
| Q6 | A player who 殉情 gets no private notice of the cause. |
| Q7 | **No** night-effect stacking table for now (option A): the rules already say it. Later idea: a collapsed 「效果疊加」 block in the role sheet. Not in these PRs. |
| Q10 | 狼美人 shot as sheriff: the charmed player 殉情 **at the shot**, then 狼美人 hands over the badge (sources: 百度百科/网易 「公布殉情者，之后执行遗言和移交警徽」; source 9). |
| Q11 | The 殉情 resolves first; a last-wolf 狼美人 that takes the last god or villager wins for the wolves (W15). |
| Q12 | That first check counts only a side fully wiped out; the HARD_MODE shortcut waits for the normal check. |
| Q13 | Day 殉情 banner: 「N號（狼美人）出局，M號殉情出局」. |
| Q14 | No 殉情 → 狼美人's exit looks like any other; its role is not shown. |

## How this was checked

Every statement about existing code in this doc was read in the source on main `b5de135`, with file and line.
Nothing was assumed. In particular:

- `VotingPipeline.kt`, all 685 lines: every death site and every badge path (X2–X5).
- The night loop, kill resolution and night-end win check in `NightOrchestrator.kt`.
- `DayRevealAdvancer.kt`, `GamePhasePipeline.revealNightResult`.
- `SelfDestructService.kt`, `KnightDuelService.kt`, `HostDeclareOutService.kt`.
- The day and voting state in `GameService.kt`, the review builders, `RoomService.validateRoleComposition`,
  the handlers' `@Order`, and migrations V1 / V19 / V37.

What this check changed from the first draft:

1. **Night order.** The witch wakes before the seer (`@Order` 3 vs 4), not after it.
2. **Night 1.** A living role must still send an action (the loop waits), so night 1 accepts only SKIP, through a
   確認 tap.
3. **H6.** A 黑狼王 that is both knifed and hunted would get a shot today, so a guard is needed.
4. **Badge.**
   - A voted-out sheriff dies first and then hands over; only a shot sheriff is deferred.
   - New branch X4(c): a deferred 狼美人 sheriff passes the badge to the charmed player. Later removed by Q10: the victim now dies at the shot.
5. **W12 wording.** A self-destructing or declared-out sheriff's badge is destroyed, not handed over.
6. **Day 殉情 banner.** It must be a saved field (`games.charm_death_user_id`) to survive a reload.
7. **Special-wolf list.** The backend list is hard-coded, so it is not automatic.
8. **New question M**, which the owner then decided.

## Technical design

Two PRs. Each PR is one role, TDD, with its own migration and a real-backend E2E in a CI shard.

- **狼美人 now** (owner, 2026-11-18). It adds a new mechanism, **chained death (殉情)**, that touches every
  place a player goes out. `docs/new-role.md` deferred 狼美人 for exactly this reason.
- **猎魔人 later.** Its design below is kept as is, so it can be built without new rulings. Its migration number
  is whatever is next when it is built. Wherever the 狼美人 sections mention a hunt (W7, X1, H7), that path
  starts to work only once 猎魔人 exists.

### Later PR — 猎魔人 `DEMON_HUNTER` (deferred)

**Wire contract**

| Item | Value |
|---|---|
| Role | `PlayerRole.DEMON_HUNTER` (good; `isGod` is automatic) / `'DEMON_HUNTER'` |
| Room flag | `rooms.has_demon_hunter` / `Room.hasDemonHunter` |
| Sub-phase | `DEMON_HUNTER_PICK` (one step; there is no result screen, because the result is the dawn death list) |
| Actions | `DEMON_HUNTER_HUNT` (target required), `DEMON_HUNTER_SKIP`. Both end the turn. Night 1: only `DEMON_HUNTER_SKIP` is accepted (the 確認 tap). Dead: no action; the loop runs the dead-role delay. |
| Night columns | `night_phases.demon_hunter_target_user_id`, `night_phases.demon_hunter_hit_wolf` (set when the hunt is submitted, like `seer_is_wolf`) |
| Private state | `nightPhase.demonHunter = { canHunt: boolean, history: [{ round, seatIndex, nickname, hitWolf }] }` |
| Audio | `demon_hunter_open_eyes.mp3` / `demon_hunter_close_eyes.mp3` (0-byte placeholders until the owner supplies them) |
| Migration | `V(next)__demon_hunter.sql`: room flag, two night columns, role CHECKs re-created with the full role set, sub-phase CHECK (V37 pattern) |

**Server rules — where each goes**

| Rule | Where |
|---|---|
| H2 | `DemonHunterHandler` (new, modelled on `GargoyleHandler`). It rejects: a hunt on night 1, a dead actor, itself, a dead target, and a second action. `@Order(8)`, and the guard moves to 9. |
| H3, H5 | `NightOrchestrator.computeKills`. A hunt adds the target (when `hitWolf`) or the 猎魔人 (when not). The guard, the antidote and the night-1 perk never filter it. The mechanical wolf's shield cannot apply, because 机械狼 ↔ 猎魔人 is excluded. |
| H4 | `computeKills`. A witch poison aimed at the 猎魔人 is dropped. The poison still counts as used, because "used" is read from `witch_poison_target_user_id != null` (`WitchHandler.kt:58`, `WinConditionChecker.kt:45`), which is still set. `computeKills` needs the roster (see "computeKills call sites" below). |
| H6 | `DayRevealAdvancer.nightShooterId`: add the hunt-death guard (see rule H6), with a test for "knifed + hunted 黑狼王 → no shot". |
| H9 | `BLOCKED_WITH` (`DEMON_HUNTER: ['MECHANICAL_WOLF']`, and add `DEMON_HUNTER` to the `MECHANICAL_WOLF` list), `RoomService.validateRoleComposition` (next to the existing pairs at `:496-504`), and the start-game check |
| E | `HardModeCounterplay.hasDemonHunter = alive.any { it.role == DEMON_HUNTER }` |
| F | `GameTask.DEMON_HUNTER_HUNT_WOLF(10)`; eligible only for `DEMON_HUNTER`. Done = some night where this player is the hunter and `demon_hunter_hit_wolf = true`. `Facts` reads it from `night_phases`. Register it in the `GameTasksTest` guard table. |

**Touchpoints** (template: the gargoyle PR #203 file list)

- Backend:
  - `Enums.kt`, `Room.kt`, `NightPhase.kt`, the migration and `FlywayMigrationsIntegrationTest`;
  - `DemonHunterHandler`, `GameActionDispatcher` (dispatch + `TURN_SUB_PHASE`), `NightOrchestrator.activeNightRoles`;
  - `RoleDealer`, `RoomService`, `GameService` (private state);
  - the audio config, `RoleDelayConfig`;
  - the review builders and DTOs, `RoleHistoryBuilder`, `GameStateLogger`;
  - `GameTask`, `GameTasks`, `RewardSettlementService.taskFacts`;
  - `scripts/act.sh` / `roles.sh`.
- Frontend:
  - `types/index.ts`, `roleDefinitions.ts`, i18n zh/en (name, intro, rules, turn texts, task name);
  - `DemonHunterTurn.vue` (pick + skip, modelled on `GargoyleTurn.vue`), `NightPhase.vue`, the narration mapping;
  - the role card, composition, history, review pages, mocks.
- Docs: `docs/new-role.md` status row, `docs/narration-scripts.md`.

### This PR — 狼美人 `WOLF_BEAUTY`

**Wire contract**

| Item | Value |
|---|---|
| Role | `PlayerRole.WOLF_BEAUTY(isWolf = true)`, a pack wolf (`isPackWolf` stays true) / `'WOLF_BEAUTY'` |
| Room flag | `rooms.has_wolf_beauty` / `Room.hasWolfBeauty`; a special wolf: the role page's 9+ players rule (automatic, derived from `ROLE_DEFINITIONS`), and the backend's wolves ≥ specials + 1 rule (add it to the hard-coded list) |
| Sub-phase | `WOLF_BEAUTY_PICK`, right after `WEREWOLF_PICK` |
| Actions | `WOLF_BEAUTY_CHARM` (target required), `WOLF_BEAUTY_SKIP`. Both end the turn. Night 1: only `WOLF_BEAUTY_SKIP` is accepted (the 確認 tap). Dead: no action; the loop runs the dead-role delay. |
| Night column | `night_phases.wolf_beauty_charm_user_id` |
| New domain event | `DomainEvent.PlayerCharmDeath(gameId, wolfBeautyUserId, victimUserId)` with `@JsonTypeName("PlayerCharmDeath")`, broadcast after commit, only for a day 殉情. The shared screen shows 「N號（狼美人）出局，M號殉情出局」 (Q13). A night 殉情 sends nothing extra (it is in `NightResult`). |
| New game event | `CHARM_DEATH` (payload `wolfBeautyUserId`, `victimUserId`), via `ActionLogService.recordCharmDeath`. It is used by the review and the task. It is **not** written to `elimination_history`, because a 殉情 is not a vote-out (W13). |
| Private state | `nightPhase.wolfBeauty = { canCharm, lastNightTargetUserId, result: null \| { targetSeatIndex, targetNickname } \| 'SKIP', history }` |
| Audio | `wolf_beauty_open_eyes.mp3` / `wolf_beauty_close_eyes.mp3` (0-byte placeholders until supplied) |
| Migration | `V38__wolf_beauty.sql`: room flag, night column, role CHECKs, sub-phase CHECK |

**The 殉情 mechanism — one rule, called from every exit**

One function holds the rule, e.g. `RoleRules.charmVictim(outUserId, players, todaysNight)`. It returns the
charmed player's user id or `null`. It returns an id only when all of these hold:

- the player going out is the `WOLF_BEAUTY`;
- the caller is not the duel or the declare-out (those never call it: W7);
- **today's** night row has a `wolf_beauty_charm_user_id`. Today's row is the one with
  `day_number == game.dayNumber`: a night and the day after it share a day number (`NightOrchestrator.kt:590-591`),
  so this is the latest night (W5);
- that player is still alive.

**The victim always dies at once, in the same transaction as 狼美人, and is never deferred** (not even when
狼美人 is a shot sheriff whose own death waits for the handover: Q10). It gets no shot (W6). It has no 殉情 of
its own: there is only one 狼美人, and it never charms a pack wolf.

**Order and win checks (W15).** At every exit that has a victim:

1. Kill the victim and record `CHARM_DEATH`.
2. Run the **wipe-only check**: `winConditionChecker.check(...)` on the alive list **with the victim out and
   狼美人 still in**, using `trigger = POST_NIGHT`.
   - That trigger already turns off the HARD_MODE shortcut (`WinConditionChecker.checkHardModeWolfWin`: `if
     (trigger == POST_NIGHT) return null` after the `humans == 0` branch).
   - CLASSIC (屠边) is already wipe-only.
   - The villagers cannot win here, because 狼美人 still counts as a living wolf.
   - So no new checker code is needed.
3. If there is a winner, end the game (the wolves). 狼美人's own death is then applied as part of game over.
4. Otherwise, go on with 狼美人's exit and that exit's normal win check, unchanged.

At night, the same order applies to the projected deaths in `resolveNightKills` (`NightOrchestrator.kt:360-369`).
Step 2 uses "every other death of the night + the victim", with 狼美人 still in. Step 4 uses all of them.

Night 1 never has a charm (O1), so **no 殉情 can happen during the day-1 sheriff campaign**. The campaign-time
paths that apply last night's kills early (`SelfDestructService.kt:73-75, 181-184`, `HostDeclareOutService.kt:109-110, 121-124`)
can therefore never contain one. They still go through `computeKills`, so they stay consistent.

**Every exit, as the code works on main today, and what the 狼美人 PR adds:**

| # | Exit | How it works today | What the 狼美人 PR adds |
|---|---|---|---|
| X1 | **Dawn** (any poison or a hunt kills 狼美人) | `revealNightResult` (`GamePhasePipeline.kt:59-78`) applies `computePendingKills`, then routes a dead sheriff to `BADGE_HANDOVER` via `DayRevealAdvancer.nextSubPhase` (`:36-46`). The night-end win check uses the same list as projected deaths (`NightOrchestrator.kt:344-369`). | `computeKills` adds the victim when 狼美人 is in the list. The dawn list and the sheriff routing then include it with no further change. The night-end win check gains the W15 order. `revealNightResult` (and the game-over branch of `resolveNightKills`) also records `CHARM_DEATH` (Q2). `nightShooterId` must return `null` if the wolf-knife victim is the 殉情 victim (W6). |
| X2 | **Vote-out** of 狼美人 | `collectEliminationEvents` (`VotingPipeline.kt:597-639`): kills at once, writes `elimination_history`, checks the win, opens a shooter's shot, and routes to `BADGE_HANDOVER` **only if the voted-out player is the sheriff** (`:634`). | After `:599`, kill the victim and record `CHARM_DEATH`, before the win check at `:613`. The badge route at `:634` must also fire when **the victim** is the sheriff. The victim is not written to `elimination_history` (W13). |
| X3 | **Day shot after a vote-out** (hunter / 黑狼王 shoots 狼美人), non-sheriff target | `votingShot` (`:284-288`): kills at once, then `afterHunterAct` (`:453-478`) checks the win and routes a dead sheriff to `BADGE_HANDOVER` (`:469-471`). | Kill the victim right after `:284`. `afterHunterAct`'s dead-sheriff check already covers a sheriff victim. |
| X4 | **Day shot after a vote-out**, the target 狼美人 **is the sheriff** | `votingShot` defers the death (`:272-281`): she stays alive through `BADGE_HANDOVER`, and `commitDeferredHunterShotIfAny` (`:394-410`) kills her inside `handleBadge`, keyed on `elimination_history.hunter_shot_user_id`. Then `afterElimination` (`:427-440`) checks the win. | **Q10: the victim dies at the shot**, inside `votingShot` before the defer decision. Then the wipe-only check (W15): a winner ends the game at once with no handover. Otherwise `projectedWinner` (`:663-672`) decides "kill now vs defer" as today. It must leave out **both** 狼美人 and the victim: pass the victim in explicitly rather than rely on `context.alivePlayers`, which was loaded before the shot. Nothing changes in `commitDeferredHunterShotIfAny` or `handleBadge`. She can no longer pass the badge to the victim (`BADGE_PASS` needs a living target, `:324-325`). A host declare-out mid-handover (`HostDeclareOutService.kt:69-84`) needs nothing extra, because the victim is already dead. |
| X5 | **Day shot by a night-killed shooter** (hunter / 黑狼王 shoots 狼美人 during the day reveal) | `nightDeathShot` (`:487-515`): kills at once, checks the win (POST_NIGHT), and routes a dead sheriff via `nextSubPhase`. | Kill the victim right after `:488`, before the win check. The sheriff routing already covers a sheriff victim. |
| X6 | **白狼王 takes 狼美人** (`DAY_DISCUSSION / RESULT_REVEALED` only, because night 1 has no charm) | `SelfDestructService` (`:115-119`): the taken player dies. A taken sheriff is routed via `nextSubPhase` (`:144-149`). The win check excludes the actor and the taken player (`:169-171`). | Kill the victim with the taken player. Exclude it in the win check at `:169-171`. Extend the routing at `:144` to "the taken player **or the victim** is the sheriff". |
| X7 | **骑士 duel** on 狼美人 | `KnightDuelService` (`:64-66`) | Nothing: no 殉情 (D2). |
| X8 | **Host declare-out** of 狼美人 | `HostDeclareOutService` (`:89-97`) | Nothing: no 殉情 (W7). |

**Also required**

- **Day 殉情 banner that survives a reload (B).**
  - The day banners are rebuilt from saved fields: `selfDestruct` and `knightDuel` (`GameService.kt:380-401`),
    and the vote result (`:464-492`).
  - Add `games.charm_death_user_id`. Clear it at night-init, next to `selfDestructUserId` (`NightOrchestrator.kt:823-824`).
  - Expose it as `charmDeath = { beautySeatIndex, beautyNickname, victimSeatIndex, victimNickname }` in both `dayPhase` (exits X5, X6) and `votingPhase` (X2–X4). It is set only when a 殉情 happened, so a 狼美人 exit with no victim shows no role (Q14).
  - A night 殉情 needs none of this: it is inside the `nightResult` list (X1).
- **Review.** Add `CHARM_DEATH` to `DAY_EVENT_TYPES` in `GameReviewBuilder.kt:22` and in `PlayerReviewBuilder`.
  `game_events.event_type` has no CHECK constraint (V1, V19), so the new type needs no migration.
- **`computeKills` call sites.** It needs the roster to tell who is the 猎魔人 (H4) and the 狼美人 (X1). It has 9
  callers, and all of them have the roster:
  - `NightOrchestrator.kt:345, 558, 579`;
  - `GamePhasePipeline.kt:63`;
  - `SelfDestructService.kt:74`;
  - `HostDeclareOutService.kt:110`;
  - `GameService.kt:362`;
  - `GameReviewBuilder.kt:53` and `PlayerReviewBuilder.kt:55` (both have `players` in scope).
  The 狼美人 PR adds the parameter (for X1). The 猎魔人 PR reuses it for H4.
- **W8.**
  - `WerewolfHandler` today accepts any living target, teammates included (`:80-83`). Reject the `WOLF_BEAUTY`
    as a target; the pack's kill UI greys it out.
  - `SelfDestructService.kt:57` allows every pack wolf, and 狼美人 is one, so add an explicit rejection. Hide
    the 自爆 button for it.
- **D.** The charm check rejects only pack wolves (`isPackWolf`, `Enums.kt:31`), never a 石像鬼 / 机械狼, so a
  rejection can never reveal a hidden wolf.
- **Special-wolf list.** The backend list is hard-coded at `RoomService.kt:505-508`: add `WOLF_BEAUTY`. The
  frontend derives it from `ROLE_DEFINITIONS` (`RoomSetupPanel.vue:248`), so it updates automatically.
- **Night order.** Shift `@Order` 2…7 up by one; 狼美人 takes 2 (see Night order).
- **F.** `GameTask.WOLF_BEAUTY_LINK_GOD(20)`, eligible only for `WOLF_BEAUTY`. Done = a `CHARM_DEATH` event
  whose `wolfBeautyUserId` is this player and whose victim is a god. Register it in the `GameTasksTest` guard
  table.

**Touchpoints**

- The same files as any night role (the list under the 猎魔人 PR, with 狼美人 names), plus:
  - `VotingPipeline`, `SelfDestructService`, `DayRevealAdvancer`, `WerewolfHandler`, `ActionLogService`, `DomainEvent`;
  - the frontend handling of `PlayerCharmDeath` (the day announcement), `WolfBeautyTurn.vue`, the wolf kill
    picker, and the 自爆 button.

## Tests (each PR; all must pass)

| Layer | 猎魔人 PR (later) | 狼美人 PR (now) |
|---|---|---|
| Backend unit | H2 rejections (a hunt on N1, self, dead target, dead actor; N1 SKIP accepted); H3 wolf → target dies, good → hunter dies; H4 poison dropped but used up; H5 guard / antidote do not block; H6 knifed + hunted 黑狼王 no shot; H9 exclusion (room + start); E counterplay; F task done / not done + guard table | W2/W3 rejections (a charm on N1, self, pack wolf, last night's target, dead; N1 SKIP accepted); D charming 石像鬼 accepted; W5 latest night only; W6 charmed 猎人 no shot (vote, night); W7 殉情 on each exit X1–X6, none on X7 / X8; X4 shot sheriff → victim dies at the shot, before the handover, and cannot receive the badge (Q10); W15 last-wolf 狼美人 + last god → wolves win (vote-out, shot, night poison, night hunt); Q12 HARD_MODE 2 good + 狼美人 → no early wolf win; Q13 banner names 狼美人, Q14 no victim → no role shown; Q2 night `CHARM_DEATH` recorded and the task counts it; X2 / X6 victim-is-sheriff routing; reload shows `charmDeath`; W8 knife and 自爆 rejected; win projection with 殉情; G badge; F task |
| Backend integration | full night with hunt → dawn deaths; migration | vote-out → 殉情 → win check; poison → 殉情 in the dawn list; 白狼王 take → 殉情; events recorded and settled |
| Frontend unit | turn component, i18n keys, `BLOCKED_WITH` | turn component, kill picker greys 狼美人, 自爆 hidden, `PlayerCharmDeath` announcement |
| Real E2E (new CI shard entry) | hunt a wolf (dies) and a good player (hunter dies) across nights; role-leak scan | charm, then vote 狼美人 out → 「N號（狼美人）出局，M號殉情出局」; result + review pages; role-leak scan |

## Out of scope

- A one-tap 狼美人骑士 board preset.
- A night-effect stacking table (被刀 / 被救 / 被守 / 被魅惑 → result): Q7 chose not now. Later idea: a collapsed block in the role sheet.
- Any change to existing boards or to other roles' rules.
- Item 5 (功能狼点将卡, on hold): if it resumes, 狼美人 is a 功能狼 candidate.
- The real narration audio: the owner generates it (ttsmaker.com, voice **1506-AI 阿乐**, then `loudnorm`).
