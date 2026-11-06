# 点将卡 (Role-pick card): design

Date: 2026-11-06. Branch: `feat/role-pick-card` (not created yet).

## Why

Players want some say in which side they play. The 点将卡 lets a player pay credits for a better chance at one camp: wolf, god or villager. It works like 首夜免死: you pay only if it works. If you do not get the camp, the credits go back to your balance.

The owner confirmed every rule below, one at a time (grilling Q1–Q15, 2026-11-06). The owner also approved the room-page mockup (three states: not chosen / chosen / ready and locked).

## Words used here

- **积分 / credits**: the in-app balance (`wallets.balance`). Players buy credits with real money. This feature never refunds real money.
- **退回 (credits return)**: credits go back to the wallet balance. In the ledger it is a `REFUND` row. That is the existing name for it, but no money moves.
- **冻结 (frozen)**: what the UI shows for credits paid for a card that is not settled yet. In the code the credits are already debited (`PERK_SPEND`). Settlement keeps them or returns them (see Rule 5).

## Goals

- In a room where perks are allowed, every player picks one of four options before they press Ready: 不使用（随机）/ 狼人 / 神 / 村民.
- Dealing honors the cards. A player who got the camp they paid for pays. A player who did not get it pays nothing.
- No one but the holder can see a card while the game runs. The review after the game shows every card.

## Non-goals

- No picking of a specific role (for example "I want to be the witch"). Only the camp.
- No real-money refund, and no change to buying credits.
- No new room setting. The card follows the existing `perksAllowed` switch.
- No change to how 首夜免死 works for the player (price, first come first served, public badge).

## Anti-goals (a failure even if it works)

- **The card leaks.** Another player, or the host, can tell from the room page, the seat badges, a STOMP broadcast, the role-reveal screen or the timing that someone used a card, or which one.
- **Someone pays for a card that did not work.** This includes a game that is cancelled, and a player who leaves or is kicked before the start.
- **Credits are returned twice**, or a card is both consumed and returned.
- **A player cannot get to Ready** because they have no credits, or because the board has no seat in a camp.

## Rules (confirmed)

### 1. The choice (Q1, Q7, Q9, Q10, Q11, Q12, Q15)

- The panel shows only when `room.config.perksAllowed` is true. When it is false, nothing changes: no panel, and Ready is not gated.
- Four options: `NONE` (不使用, free), `WOLF`, `GOD`, `VILLAGER`.
- A guest cannot press Ready until they have chosen. `NONE` counts as a choice.
- The host also chooses. When perks are allowed, the host cannot start the game until the host has chosen.
- While a player is READY, their choice is locked. To change it: undo Ready, choose again (the old card's credits return at once, the new card is paid), then press Ready again.
- A camp with no seat on the current board (for example a board with no villagers) is greyed out with "本局无此阵营". It cannot be chosen.
- If the balance is below a card's price, that card is greyed out with "余额不足" and a link to buy credits. `NONE` can always be chosen.
- A choice belongs to one game. It is cleared whenever the room goes back to WAITING after a game (today that only happens in `OrphanedGameRecovery`). Fact: after a normal game end, "再来一局" goes to the lobby and the next game is a new room, so a choice never carries over in the normal flow.

### 2. The camps (Q3)

| Card | Gets it when the dealt role is |
|------|--------------------------------|
| WOLF | `role.isWolf`: 狼人, 白狼王, 黑狼王, 机械狼 |
| GOD | `role.isGod`: 预言家, 女巫, 猎人, 守卫, 白痴, 通灵师 |
| VILLAGER | `VILLAGER` |

Use the existing `PlayerRole.isWolf` / `isGod`. Do not add a second list.

### 3. Dealing (Q2)

`buildRoleList` still decides which roles are in the game. Only the assignment of roles to seats changes. The steps:

1. Split the role list into three camps (WOLF / GOD / VILLAGER) with the rule in 2.
2. For each camp, take the players who hold that card. If there are no more of them than the camp has seats, all of them get a role from that camp. If there are more, draw the winners **at random** (not by who chose first). The others are "not drawn".
3. Inside a camp, which exact role a winner gets is random.
4. Everyone else (players with `NONE`, players not drawn, and holders of a card whose camp has no seat) gets the remaining roles at random.
5. A winner's card **took effect**. Every other card did not.

This has to be a pure function with the randomness passed in, so unit tests can fix the random source. For example: `RoleDealer.deal(roles, players, picks, random): Map<userId, PlayerRole>`.

With no cards in a room (or every player on `NONE`), the result must be the same as today's plain shuffle.

### 4. Price (Q4)

- Three new rows in `perks`: `ROLE_PICK_WOLF`, `ROLE_PICK_GOD`, `ROLE_PICK_VILLAGER`. Each costs 20 credits.
- Each row has its own price, so a later price change is a data change only.
- Names (zh): 点将卡·狼人 / 点将卡·神 / 点将卡·村民. Add en text for the UI. `zh.ts` is Traditional Chinese, as everywhere else.

### 5. Charging and settlement (Q5, Q14)

This reuses the 首夜免死 machinery (`perk_activations`, `WalletService`, `PerkSettlementService`, `PerkRefundSweep`):

- **Choosing a card**: insert a `perk_activations` row and debit 20 (`PERK_SPEND`) in the same transaction. Not enough credits means the call is rejected and nothing changes.
- **Switching cards, or switching to NONE**: the old card's row goes to REFUNDED with an immediate credit, through the existing `refund()` exactly-once gate. Then the new card is charged.
- **Leaving or being kicked before the start**: already covered by `refundActiveForUser`. The stale-room sweep (`PerkRefundSweep`) also covers role cards with no extra code.
- **Game start**: `bindToGame` binds the cards to the game, as it already does. Each card that took effect gets `triggered_at` set. The card stays ACTIVE. Nothing is returned at the start, on purpose, because the balance must not move when roles are dealt.
- **Game end** (`PerkSettlementService.settleForGame`): a card that is ACTIVE with `triggered_at` set, in a game that was not cancelled, becomes CONSUMED and keeps the 20. Every other card (not drawn, game cancelled) becomes REFUNDED and the 20 goes back.
- **With 首夜免死** (Q8): the two are settled separately. If the wolf card took effect, the wolf card's 20 is consumed. 首夜免死 is VOID (the holder is a wolf), so its 30 goes back at game end. That is how it works today.

The account page shows the existing ledger, so a card that did not work shows two lines: "道具支出 −20" and "退回 +20". The owner accepted that (Q14).

### 6. Privacy (Q6, Q13)

- No seat badge. No card data in `RoomDto.perkActivations` or in the `PERK_UPDATE` broadcast. Both are public to the room and must leave out `ROLE_PICK_*` rows.
- The room broadcasts carry nothing about a card. If the room needs to know who has chosen, it uses only the existing ready status. A guest cannot be READY without a choice, so READY already means "has chosen".
- A player reads their own choice from a request only they can make (a per-user GET, or the response of the choose call).
- Role reveal (Q13): only the holder sees one line:
  - took effect: "点将卡生效，游戏结束扣除 20◈"
  - not drawn: "点将卡未抽中，20◈ 将在游戏结束时退回"
  - `NONE`, or perks off: nothing
  Put this in the requester-only part of `getGameState`, next to `myRole`. Never in a broadcast.
- Review (Q6): after `GAME_OVER`, the review shows each player's card and whether it took effect. The review is already limited to the game's players, after the game ends (`GameReviewService.load`).

### 7. UI (approved mockup)

- Room page, a "点将卡" panel above the 首夜免死 row. A 2×2 grid: 不使用（免费）, 🐺 狼人, ✨ 神, 🌾 村民. Each card shows its price and how many seats its camp has on this board.
- The panel title shows the balance, and "冻结 N" when the player has unsettled cards. N is the sum of the player's own live room activations, 首夜免死 included.
- After a paid choice, show: "想当狼的人多于 N 个时随机抽签；没抽中会随机发牌，并退回 20◈。" (the camp name and N change with the card).
- When the player holds the wolf card and 首夜免死 together, show the hint: "提示：拿到狼人时首夜免死无效，它的 30◈ 会在游戏结束时退回。"
- While the player is READY: the panel is locked (🔒) and the chosen card is highlighted.
- The status bar asks for a choice until there is one, and the Ready button stays disabled.
- Every new control gets a `data-testid`.

## Data model

- `perks`: three new rows (Rule 4).
- `perk_activations`: no new columns. A card that took effect is marked with `triggered_at`.
- The partial unique index `ux_one_active_perk_per_room (room_id, perk_code) WHERE status='ACTIVE'` would allow only one wolf card per room. Recreate it so it does not cover `ROLE_PICK_*`. The rule "a player holds at most one live role card" is enforced in the service, under the room lock. If the builder wants a database backstop too, they can add a partial unique index on `(room_id, user_id) WHERE status='ACTIVE' AND perk_code LIKE 'ROLE_PICK_%'`.
- The choice, `NONE` included, must be stored somewhere, because `NONE` has no activation row and Ready must be gated on it. Suggestion: a nullable `room_players.role_pick` column (`NULL` = not chosen yet, `NONE` / `WOLF` / `GOD` / `VILLAGER`). Clear it everywhere ready flags are reset.
- One new Flyway migration, `V31__role_pick_card.sql`. Never edit a migration that is already merged.

## Code that must change (found while checking the code)

- `PerkService.onGameStart` → `voidWolfHolders` voids **every** live activation a wolf holds. That would VOID a wolf card that worked. Limit it to `NIGHT1_IMMUNITY`.
- `PerkService.activate` enforces "one per room". Role cards need their own path: their own camp rules, one card per player, and refund-then-charge when switching.
- `PerkService.catalog()` feeds the generic perk list on the room page. The role cards must not show up there as three extra "activate" rows.
- `PerkSettlementService.tookEffect`: add the `ROLE_PICK_*` codes, with the same rule as `NIGHT1_IMMUNITY` (`ACTIVE && triggeredAt != null`).
- `RoomService.setReady(ready=true)`: when perks are allowed and `role_pick` is NULL, reject.
- `GameService.startGame`: when perks are allowed and the host's `role_pick` is NULL, reject. Then deal with `RoleDealer` instead of `roles.shuffle()`, and mark the cards that took effect.
- `OrphanedGameRecovery`: where it resets ready flags, also clear `role_pick`.
- Scripts and tests that make players ready through the API (`scripts/join-room.sh`, E2E `setupGame`, the mock data) must choose `NONE` first. Otherwise every real-backend E2E would stop at Ready.
- `myActivations` (account page) shows the role cards with their names. Nothing else is needed there.

## Tests the builder should write

- `RoleDealer`: no cards gives the same result as a plain shuffle; under-subscribed (every holder gets their camp); over-subscribed (exactly as many winners as seats, the others not drawn); a camp with zero seats; inside a camp the exact role is random; every role in the list is dealt exactly once.
- Settlement: a card that worked gives CONSUMED and no credit; not drawn gives REFUNDED and +20; a cancelled game gives REFUNDED; running settlement twice credits only once; a wolf card that worked plus 首夜免死 gives wolf card CONSUMED and 首夜免死 REFUNDED (+30).
- Ready gate: perks on and no choice → rejected; `NONE` → allowed; perks off → allowed with no choice; host start with no choice → rejected.
- Privacy: `RoomDto` and `PERK_UPDATE` carry no `ROLE_PICK_*`; another player's `getGameState` has no role-card field for you.
- Switching: WOLF → GOD returns 20 and charges 20; switching while READY is rejected.
- No new Spring test contexts (CI Postgres connection budget).
- One real-backend E2E: a player picks wolf, the game starts, the role-reveal line matches the dealt role, and the balance after the game is right. Gate on phase, never on a sleep.

## Left to the builder

- The endpoint shape (suggestion: `POST /api/room/role-pick {choice}` next to `/api/room/perk/*`, plus a per-user GET).
- Whether `RoleDealer` is its own file or a private function in `GameService` (it must be unit-testable).
- The exact UI copy beyond the lines quoted above, and the en translations.

## Calls I made (not discussed with the owner)

- The choice is stored on `room_players.role_pick`, because `NONE` has no activation row.
- Scripts and E2E helpers choose `NONE` before Ready, instead of loosening the Ready gate.
- An old frontend still cached during a deploy cannot choose, so it cannot press Ready until the page reloads. I judged that acceptable.
- If the host edits the board after a player chose, a camp can drop to zero seats. The card is not cancelled. It just does not take effect, and its credits return at game end.
