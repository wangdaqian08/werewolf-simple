# 单局任务第二批：7 个身份任务

- 日期：2026-11-18
- 路线图：`docs/superpowers/specs/2026-11-12-feature-roadmap-six-goals.md` 第 1 项
- 第一批：#190、#192、#193（13 个任务，`model/GameTask.kt`、`service/GameTasks.kt`）

## 已确认（2026-11-18，产品负责人）

| 角色 | 代码 | 任务 | 战绩分 |
|---|---|---|---|
| 猎人 | `HUNTER_SHOOT_WOLF` | 開槍帶走一隻狼人 | 10 |
| 骑士 | `KNIGHT_DUEL_WOLF` | 決鬥翻出狼人 | 10 |
| 通灵师 | `MEDIUM_FIND_WOLF` | 驗到一隻狼人的具體身份 | 10 |
| 白狼王 | `WWK_TAKE_GOD` | 自爆時帶走一名神職 | 20 |
| 黑狼王 | `BWK_SHOOT_GOD` | 出局開槍帶走一名神職 | 20 |
| 石像鬼 | `GARGOYLE_FIND_TWO_GODS` | 查驗到至少 2 名神職 | 10 |
| 白痴 | `IDIOT_REVEAL_WIN` | 被放逐翻牌後，好人陣營獲勝 | 10 |

- 机械狼、守墓人**不加**身份任务（任务太简单），仍只抽通用任务。
- 1-4：同身份的两人可以抽到同一个任务（保持每人独立抽签）。
- 其余规则不变：每人每局 1 个，至少 6 人，只加战绩分，取消的对局不结算。

## 判定（全部用已有记录，不加表、不加迁移）

| 任务 | 判定 |
|---|---|
| `HUNTER_SHOOT_WOLF` | 有一条 `HUNTER_SHOT` 事件，`hunterUserId` 是自己，目标是狼阵营。被黑狼王打死后反击开枪也算 |
| `BWK_SHOOT_GOD` | 有一条 `HUNTER_SHOT` 事件，`hunterUserId` 是自己，目标是神职 |
| `KNIGHT_DUEL_WOLF` | 自己的 `KNIGHT_DUEL` 事件里 `targetIsWolf = true` |
| `WWK_TAKE_GOD` | 自己的 `SELF_DESTRUCT` 事件里 `takenUserId` 是神职 |
| `MEDIUM_FIND_WOLF` | 任意一晚 `mediumResultRole` 是狼阵营角色。按通灵师**看到的**身份判定：学了技能的机械狼在通灵师眼里是学到的身份，不算 |
| `GARGOYLE_FIND_TWO_GODS` | `gargoyleResultRole` 是神职的夜晚至少 2 个（石像鬼整局不能重复验同一人，所以就是 2 名不同的神职） |
| `IDIOT_REVEAL_WIN` | 有自己的 `IDIOT_REVEAL` 事件，且好人阵营获胜 |

## 改动范围

- 后端：`GameTask` 加 7 个值；`GameTasks.eligible` 按角色发；`GameTasks.Facts` 加开枪、自爆带人、决斗成功三项，由 `RewardSettlementService.taskFacts` 从 `game_events` 读出。
- 前端：`TaskCode` 类型、`task.name.*` 中英文案。
- 测试：
  - `GameTasksTest`：每个任务的完成和未完成，并在「不发超出技能的任务」守卫表里登记；
  - `GameTasksIntegrationTest`：从真实事件记录结算；
  - 真实后端 E2E：用 `set-task` 固定任务，跑一局验证结算页和复盘。
