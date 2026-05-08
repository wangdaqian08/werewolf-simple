# Image Resources

Static iconography (roles, phases, status indicators, action icons) is loaded from
this directory via the `<GameIcon>` component. To swap in a new look, drop a PNG
file at the listed path with the listed name — no code change needed.

> **Player avatars are handled separately.** The `<Avatar>` component
> (`src/components/Avatar.vue`) takes a URL or emoji and falls back to the first
> character of the nickname. It does not consult this directory or the icon
> manifest, so the table below covers static iconography only.

## Loading & fallbacks

- Each icon name resolves through `src/assets/iconManifest.ts` (`ICON_MANIFEST`).
- `<GameIcon name="...">` renders the manifest path. If the PNG 404s or the
  name is unknown, the renderer falls back to `_placeholder.svg`.
- `_placeholder.svg` ships with the repo so the UI never shows a broken
  image, even before any PNGs land. Replace it with your own PNG of the same
  name (and update `FALLBACK_ICON` in `iconManifest.ts` if you want a
  different path) — or keep it.

## Recommended format

- **PNG**, 256 × 256, transparent background. Browser scales down for smaller
  sites.
- Square aspect ratio — assets render into square-ish slots, so off-square
  sources get cropped.
- Keep file size small (< 30 KB each). PNGs compress well at this resolution.

## Directory layout

```
frontend/public/images/
├── _placeholder.svg       (shipped — generic fallback for any missing icon)
├── roles/                 (7 PNGs — werewolf, villager, seer, witch, hunter, guard, idiot)
├── phases/                (3 PNGs — night, day, deaths)
├── status/                (9 PNGs — sheriff, dead, peaceful, quit, speaking, waiting, good, eliminated, medal)
└── actions/               (8 PNGs — vote, battle, music, shoot, revote, list, locked, officer)
```

**27 PNGs total.**

## Image table — drop these PNGs into the listed paths

> Sizes in the "Renders at" column are the largest place each asset is used.
> A 256×256 source covers every site cleanly.

### Roles

| Filename | Path | Replaces emoji | Renders at | Used in |
|---|---|---|---|---|
| `werewolf.png` | `public/images/roles/werewolf.png` | 🐺 | up to 80 px | RoleRevealCard, NightPhase (badge + seer-result), VotingPhase (history banner, role card sheet), CreateRoomView |
| `villager.png` | `public/images/roles/villager.png` | 🌾 / 🧑‍🌾 | up to 80 px | RoleRevealCard, VotingPhase role card sheet, CreateRoomView |
| `seer.png` | `public/images/roles/seer.png` | 🔭 | up to 80 px | RoleRevealCard, VotingPhase role card sheet, CreateRoomView |
| `witch.png` | `public/images/roles/witch.png` | 🔮 / 🧙‍♀️ | up to 80 px | RoleRevealCard, VotingPhase role card sheet, CreateRoomView |
| `hunter.png` | `public/images/roles/hunter.png` | 🏹 | up to 80 px | RoleRevealCard, VotingPhase (history banner + role card), CreateRoomView |
| `guard.png` | `public/images/roles/guard.png` | 🛡️ | up to 80 px | RoleRevealCard, VotingPhase role card sheet, CreateRoomView |
| `idiot.png` | `public/images/roles/idiot.png` | 🃏 | up to 80 px | RoleRevealCard, VotingPhase (banner + slot overlay + footer hints), ActionLogDrawer (section title), CreateRoomView |

### Phases

| Filename | Path | Replaces emoji | Renders at | Used in |
|---|---|---|---|---|
| `night.png` | `public/images/phases/night.png` | 🌙 | up to 64 px | NightPhase (header moon + sleep screen) |
| `day.png` | `public/images/phases/day.png` | ☀ | ~16 px | ActionLogDrawer (vote-result section title) |
| `deaths.png` | `public/images/phases/deaths.png` | ☽ | ~16 px | ActionLogDrawer (deaths section title) |

### Status

| Filename | Path | Replaces emoji | Renders at | Used in |
|---|---|---|---|---|
| `sheriff.png` | `public/images/status/sheriff.png` | ⭐ | 12 – 24 px | NightPhase / DayPhase / VotingPhase / GameView (sheriff badge), VotingPhase (badge banners), CreateRoomView (sheriff toggle), ActionLogDrawer (section title), SheriffElection (winner badge label) |
| `dead.png` | `public/images/status/dead.png` | 💀 | up to 32 px | NightPhase (dead self banner), DayPhase (kill banner) |
| `peaceful.png` | `public/images/status/peaceful.png` | ❤️ | ~16 px | DayPhase (peaceful-night banner) |
| `quit.png` | `public/images/status/quit.png` | ❌ | ~14 – 24 px | SheriffElection (speaking-row icon, vote-col-quit head) |
| `speaking.png` | `public/images/status/speaking.png` | 🎤 | ~14 px | SheriffElection (speaking-row icon when this row is active) |
| `waiting.png` | `public/images/status/waiting.png` | ⏳ | up to 48 px | GameView (role-confirm waiting screen), SheriffElection (speaking-row icon for pending speakers) |
| `good.png` | `public/images/status/good.png` | ✅ | ~20 px | NightPhase (seer "good camp" verdict) |
| `eliminated.png` | `public/images/status/eliminated.png` | ☑ | ~16 px | DayPhase (self-eliminated banner) |
| `medal.png` | `public/images/status/medal.png` | 🏅 | ~32 px | SheriffElection (RESULT screen medal) |

### Actions

| Filename | Path | Replaces emoji | Renders at | Used in |
|---|---|---|---|---|
| `vote.png` | `public/images/actions/vote.png` | 🗳 | n/a (currently only in mock data — kept for parity) | mocks/data.ts |
| `battle.png` | `public/images/actions/battle.png` | ⚔️ | up to 24 px | CreateRoomView (win-condition row), VotingPhase (badge-destroyed banner) |
| `music.png` | `public/images/actions/music.png` | 🎵 | ~24 px | CreateRoomView (BGM row) |
| `shoot.png` | `public/images/actions/shoot.png` | 🔫 | up to 24 px | VotingPhase (HUNTER_SHOOT banner), ActionLogDrawer (hunter-shot section title) |
| `revote.png` | `public/images/actions/revote.png` | 🔁 | ~16 px | VotingPhase (RE_VOTING banner) |
| `list.png` | `public/images/actions/list.png` | 📋 | ~20 px | DayPhase + VotingPhase (game-log fab buttons), VotingPhase (history button) |
| `locked.png` | `public/images/actions/locked.png` | 🔒 | ~14 px | VotingPhase (my-role chip) |
| `officer.png` | `public/images/actions/officer.png` | 👮 | ~14 px | SheriffElection (phase chip) |

### Pre-shipped fallback

| Filename | Path | Purpose |
|---|---|---|
| `_placeholder.svg` | `public/images/_placeholder.svg` | Shown when an icon path 404s or its name isn't in `ICON_MANIFEST` |

You may overwrite the fallback with your own PNG (renaming `.svg` → `.png`
requires updating the `FALLBACK_ICON` constant in
`src/assets/iconManifest.ts` to match).
