# Scenario 10 — 5-Night HARD_MODE Spine: The Corrected Master Script

**Scenario ID:** 10
**Complexity:** High (12 players, all roles, sheriff, 5 nights + 5 days to GAME_OVER)
**Outcome:** WEREWOLF win at the Day-5 POST_VOTE check
**Executable form:** `frontend/e2e/real/flow-5night-hardmode.spec.ts` (CI shard 7)
**Companion variant:** `frontend/e2e/real/witch-selfsave-blocked-flow.spec.ts` (CI shard 8)
 
---

## Origin: corrections to the hand-written draft script

This scenario is the validated form of a hand-written 5-night game script. The
draft was checked claim-by-claim against the backend; every mismatch below is
baked into the corrected script:

| # | Draft claim | Code reality |
|---|-------------|--------------|
| 1 | Night order wolf → seer → witch → guard | **wolf → witch → seer → guard** (`@Order(1..4)` on the role handlers; witch acts before seer since PR #127) |
| 2 | N2: witch saves the wolf victim, reveal shows "1 kill" | **0 kills / 平安夜** — the antidote cancels the kill (`NightOrchestrator.computeKills`) |
| 3 | Jump from a reveal straight to the next night (no day vote) | **Impossible** — only wolf self-destruct (`daySkipVoting`) skips a vote; a mass-abstain **double tie** is the legitimate no-elimination day and auto-advances to night with no host click |
| 4 | Sheriff first appears on day 3 (badge transfer) | The election runs **day 1 only**, NIGHT → SHERIFF_ELECTION **before** the death reveal, iff `room.hasSheriff` |
| 5 | Night 5 plays `day_time` then `rooster_crowing` | `rooster_crowing.mp3` always plays **before** `day_time.mp3` (`AudioService`) |
| 6 | Nights 4–5 reachable in any mode | **HARD_MODE only** — CLASSIC (屠边) ends once every villager or every god is dead; HARD_MODE suppresses parity at night |
| 7 | Dead roles still get fake audio with pauses | Confirmed and backend-driven: the night role loop runs every role's slot dead-or-alive (fixed `deadRoleDelayMs`), so the audio manifest is identical every night |
| 8 | Tie → "second round, everyone votes the sheriff, badge transfers" | First tie → PK `RE_VOTING` (only the tied players can be voted for and they do not vote; votes wiped); the sheriff's 1.5× weight breaks 1:1 ties, so the engineered tie needs the sheriff to abstain; a revote executing the sheriff fires `DAY_VOTING/BADGE_HANDOVER` |
| 9 | "Finish the rest until game_over" | Completed: guard dies N5 → counterplay (guard / witch potions / hunter bullet) exhausted → **werewolf win at the D5 POST_VOTE parity check** |

House rules this codebase pins (divergences from traditional 狼人杀 are deliberate):
同守同救 → the target **dies** (official rule, browser-pinned by
`guard-witch-same-target-flow`); poison **pierces** guard protection; wolves may
target fellow wolves (内刀, no role check); 空刀 is allowed (`WOLF_SKIP`), only when the room enable it(default off);
witch self-save is night-1 only (the room toggle can forbid it entirely); the witch
cannot save + poison the same night; guard may self-protect but not repeat a
target on consecutive nights; a poisoned hunter cannot shoot; night kills are
deferred to the host's reveal, so victims act the night they die and N1 victims
fully participate in the day-1 election. CLASSIC wins by 屠边 (every villager or
every god dead); a first-round tie goes to a PK re-vote; the game ends the moment
a side wins (no hunter shot or badge handover after the deciding death); a dying
hunter shoots before the badge is handed over (day vote and night death alike); one
self-destruct per day, never during a pending hunter shot or badge handover.
Browser evidence: `win-rules-flow`, `night-rules-flow`, `sheriff-hunter-rules-flow`,
`self-destruct-rules-flow`, `pk-revote-flow`, `api-guards-flow`.

## Cast (role-relative — assignment is server-shuffled)

12 players = 4 × WEREWOLF (A/B/C/D) + SEER + WITCH + HUNTER + GUARD + IDIOT +
3 × VILLAGER (V1/V2/V3). The spec resolves who holds each role at runtime
(`getRoles` → `resolveRolePlayer`) and binds every action to the resolved
holder (`assertActorRole`) — only the wolf kills, only the seer checks, only
the guard protects. Browser pages: WEREWOLF, SEER, WITCH, GUARD (+ host).

## Room configuration

`totalPlayers: 12`, `hasSheriff: true`, `winCondition: HARD_MODE`,
`witchSelfSaveAllowed: true` (default), roles all-on (SEER/WITCH/HUNTER/GUARD/IDIOT).

## Script (per-night actions → expected outcome)

| Step | Actions | Deaths applied | W/H after | Pinned behavior |
|------|---------|----------------|-----------|-----------------|
| N1 (DOM) | wolves → V1 · witch **poisons** V2 · seer checks wolfA · guard **self-protects** | deferred | 4/8 | full real-UI night incl. the poison picker |
| Sheriff D1 | SEER campaigns alone; everyone else passes; all vote seer | — | 4/8 | morning cue rides NIGHT→SHERIFF_ELECTION exactly once; election→day rooster suppressed |
| D1 | reveal **2 dead** → all vote HUNTER → hunter shoots wolfD | V1, V2, HUNTER, wolfD | 3/5 | poison double-kill reveal; day-vote hunter shot |
| N2 (API) | wolves → V3 · witch **saves** V3 (self-save is night-1 only) · guard → wolfA · seer → wolfB | none | 3/5 | antidote cancels the kill |
| D2 | reveal 平安夜 → all abstain → RE_VOTING → all abstain | none | 3/5 | double tie → no elimination → **auto**-advance to night |
| N3 (API + witch DOM) | wolves → SEER · guard → SEER (prev = wolfA, legal) · potion-less witch clicks `witch-skip` | none | 3/5 | guard save cancels the kill; potion-exhaustion UI |
| D3 | reveal 平安夜 → engineered 1:1 tie (witch → SEER, guard → IDIOT, sheriff abstains) → PK revote piles on SEER → badge passed to wolfA | SEER | 3/4 | PK revote; badge handover via revote |
| N4 (API) | wolves → WITCH (potion-less; still acts) · guard → wolfA · dead SEER masked | WITCH | 3/3 | **HARD_MODE suppresses POST_NIGHT parity** (CLASSIC ends here) |
| D4 | reveal → all vote V3 | V3 | 3/2 | POST_VOTE blocked: the living guard is counterplay |
| N5 (API) | guard acts first (→ wolfB; not self, not prev wolfA) · wolves → GUARD · dead SEER **and** dead WITCH masked | GUARD | 3/1 | two dead roles masked in one night |
| D5 | reveal → all vote wolfB | wolfB | 2/1 | counterplay gone → **WEREWOLF win** at POST_VOTE |

**Alive audit:** 9 dead (V1, V2, HUNTER, wolfD, SEER, WITCH, V3, GUARD, wolfB) +
3 alive (wolfA with the badge, wolfC, IDIOT) = 12 ✓
**Guard chain:** self → wolfA → SEER → wolfA → wolfB (no consecutive repeats, self only on N1) ✓
**Hunter:** never night-targeted (the D1 vote-out is the deliberate exception) ✓

## Audio manifest (asserted by the spec)

Per night: `[goes_dark_close_eyes, wolf_howl]` (night-init frame) + 8 role
open/close frames in **Werewolf → Witch → Seer → Guard** order — identical
whether roles are alive or dead (masking). Per day: `[rooster_crowing,
 day_time]` (the D1 cue rides NIGHT→SHERIFF_ELECTION).

**Total: 5 × (2 + 8) + 5 × 2 = 60 files** = `buildGameAudioManifest({nights: 5, dayCues: 5})`.

Assertion layers (`helpers/audio-manifest.ts`): backend `[broadcastAudio]` /
`[broadcastNightInit]` log lines (EXACT counts, gameId-scoped), STOMP
`AudioSequence` receipts (AT LEAST — reconnect replay may re-deliver),
per-night order from backend log blocks, and masking pins from
`[nightRoleLoop] role=X alive=false` lines (SEER ×2, WITCH ×1). Playback
(`Starting playback`) is never asserted — e2e timings let broadcasts outpace
real-time playback by design.

## Coverage map — why this scenario exists (no duplicate tests)

Unique to this spec (each needs one continuous multi-night game): antidote
平安夜 · poison 2-death reveal + DOM poison path · potion-exhaustion witch-skip
UI · guard-save 平安夜 + consecutive-target chain · double-tie auto-night ·
sheriff-day-1 audio suppression · dual dead-role masking · HARD_MODE
POST_NIGHT parity suppression.

Smoke-only here because owned elsewhere: sheriff election (`sheriff-flow`,
`flow-12p-sheriff`) · badge handover day + night-death (`flow-12p-sheriff`
scenarios 1–3) · hunter day/night shots (`game-flow-d1-outcomes` row 4,
`hunter-night-death-flow`) · single tie (`revote-flow`) · idiot reveal
(`idiot-flow`) · self-destruct vote-skip (`self-destruct-flow`) · win endings
(`werewolf-win`, `d1-outcomes`) · 2-night audio manifest / single-dead-role
masking / dedup / replay (the five `*audio*` specs).

Demoted to backend unit tests (pure rule arithmetic, no unique UI):
同守同救-dies (also browser-pinned), poison-pierces-guard, poison==wolfTarget dedup
(`NightKillMatrixTest`), 内刀 acceptance (`RoleHandlerTest`),
poisoned-hunter-can't-shoot (`DayRevealAdvancerTest`, pre-existing).

**Selection rule for future variants:** a new browser spec must pin ≥1
behavior no existing spec exercises AND require live flow/UI/audio; anything
expressible as a pure `computeKills`/`DayRevealAdvancer`/`WinConditionChecker`
case goes to the backend matrix instead.

## Database cleanup contract

Both new specs call `DELETE /api/test-support/rooms/{roomCode}` in `afterAll`
(`helpers/test-support.ts`, warn-never-throw). The endpoint —
`TestSupportController`, gated `@Profile("(e2e | test) & !prod")` (no deployment runs either profile) — cascades the room's
full game graph (votes, events, eliminations, sheriff rows, night phases,
game players, games, room players, perk activations and their game-scoped
credit transactions, the room itself) while **keeping users and wallets**
(guest identities are shared across rooms in a session). CI shards run
per-process create-drop H2 and never needed this; it exists so a locally
reused backend (`reuseExistingServer: true`) stops accumulating rows.
