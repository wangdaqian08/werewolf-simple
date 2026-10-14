# Game Review (复盘) and Speech Help — Brainstorm

**Date:** 2026-10-15
**Status:** Idea / discussion — not approved, not started

## Recommendation

Build the review in three layers. Layers 1 and 2 are free and need no AI.
They also make the AI layer cheaper and more accurate later.

## What the game already gives us

- The game is a **face-to-face assistant**: the host runs phases, night audio
  plays aloud, and the host timer times each speech. Speeches happen out loud,
  not in the app.
- Every action is already stored, with its night/day number:
    - every night: wolf target, seer check and result, witch save and poison,
      guard target (`NightPhase` rows)
    - every vote: who voted for whom, on which day, including sheriff votes
    - eliminations, hunter shots, self-destructs, sheriff results (action log)
    - each player's real role, shown at game end
- What the game does **not** know:
    - what anyone **said**
    - who is speaking during day discussion (only the sheriff campaign has a
      speaking order today)

## Layer 1 — God's-eye replay (上帝视角复盘) — free

At game end, replay the game night by night and day by day, with all roles shown:

- "Night 2: wolves killed 5号. Guard protected 3号. Witch saved nobody."
- "Day 2 vote: 4号 and 6号 voted for 2号 (villager). 7号 voted for 8号 (wolf) ✓"

Per-player **judgment score**:

- exile-vote and sheriff-vote accuracy (how often the vote hit a wolf)
- who trusted the real seer
- how many days each wolf survived

**Cost:** zero — a query over existing data.
**Why it stands out:** most apps only show final roles. A clear, shareable
timeline image is what players send to their group chat.

## Layer 2 — Claim tags and private beliefs — free, the unique part

Reviewing speeches does not need transcripts; it needs the structured
**content** of a speech.

**Claim chips** — during their own speech, the speaker taps:

- 跳预言家 / 跳女巫 / 我是好人
- 查验 N号 → 金水 / 查杀
- 站边 N号 / 踩 N号

**Private beliefs (心证)** — every listener can privately mark each player as
好人 / 狼 / 不确定, and change it at any time during the day.

At game end, the review checks claims and beliefs against the truth:

- "3号 claimed seer and gave 7号 查杀. Truth: 3号 was a wolf (悍跳); 7号 was a villager."
- "Your belief in 3号 was 'wolf' from Day 1. You read it right ✓"
- "Best reader of the table: 6号, 5 of 6 beliefs correct"

Why this is the strongest option:

- **Free and accurate**: no dependence on speech recognition, accents or room noise.
- **Uncommon**: tracking each player's private beliefs over the game and scoring
  them at the end is not a common app feature — this is the "outstanding" part.
- **Clean AI input**: if AI is added later, claims and beliefs give the model
  facts instead of raw talk — cheaper and better results.

## Layer 3 — AI speech summary — optional

Add only after Layers 1 and 2.

**Capture (free, on device):**

- The speaker's own phone records only during their turn; the host timer marks
  the turn boundaries.
- The browser's built-in speech recognition (Web Speech API) converts speech to
  text. It supports Chinese and costs nothing.
- Caveat: browser support varies; on iOS it may depend on system settings.
  Treat it as best effort — players who do not use it still get Layers 1 and 2.

**Prerequisite:** day discussion needs a "current speaker" (host taps 下一位,
as in the sheriff campaign). Without it, a transcript cannot be linked to a player.

**Summary:**

- **One AI call per game, at game end** — not one per speech.
- Input: all transcripts + true roles + the claim log.
- Output: a 2–3 line summary per speech, plus coaching notes, e.g.
  "5号 claimed guard, but their Day 2 story contradicts the Night 1 guard record."
- Rough cost: 12 players × 5 speeches × ~300 characters ≈ 20k Chinese
  characters. With a cheap model (DeepSeek / Qwen / Gemini Flash class) this is
  about one cent or less per game. **Check current prices — they change often.**

**Paying for it:** the app already has credits and paid perks. Make "AI 复盘" a
credit item, or free once a day. This covers the cost and gives credits more value.

**Risks:**

- **Consent**: recording needs a clear opt-in; other people's voices are recorded too.
- **Noise**: recognition quality in a loud room is the biggest unknown. Test
  early with a two-phone prototype before building on it.

## Suggested order

| Phase | Feature | Cost | Effort |
|---|---|---|---|
| 1 | God's-eye replay + judgment score | Free | Small — data already exists |
| 2 | Claim chips + private beliefs + "best reader" | Free | Medium — new UI, 2 small tables |
| 3a | "Current speaker" in day discussion | Free | Small |
| 3b | On-device speech-to-text + one AI call per game, paid with credits | ~1 cent / game | Medium — test the noise risk first |

## Open questions

1. **Where do people play?** Always in the same room → Layer 2 is the winner.
   Online play planned → transcripts and AI matter more.
2. **Who is the review for?** Casual groups want fun, shareable highlights;
   serious players want analysis. This changes what the AI is asked to write.
3. **Should beliefs ever be visible during the game?** E.g. to the host, or as an
   anonymous "table mood" bar. Private-until-the-end is safer; a live mood bar
   would also be unique.
