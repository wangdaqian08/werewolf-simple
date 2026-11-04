# Narration scripts for new roles

The file names and exact text to generate for each future night role, so the
whole batch can be made in one sitting. How to generate them (site, voice,
format, loudness) is in [ADR audio § Generating narration files](adr/audio.md#generating-narration-files):
https://ttsmaker.com/, voice **1506-AI 阿乐**, short phrase only, then `loudnorm`.

Only roles with their own **night step** need narration. Every such role gets
an open-eyes and a close-eyes line, and both play even when the role is dead
(ADR-010 dead-role rule), so the lines must not reveal anything else. Results
are shown on the role's own screen, never read aloud.

## Roadmap roles ([new-role.md](new-role.md))

| Role | File name | Script |
|---|---|---|
| 守墓人 | `gravekeeper_open_eyes.mp3` | 守墓人请睁眼 |
| 守墓人 | `gravekeeper_close_eyes.mp3` | 守墓人请闭眼 |

骑士 needs no narration: the duel happens during the day, like 白狼王 and 白痴,
which have no lines either.

## Deferred or not-recommended roles (optional, generate ahead if wanted)

| Role | File name | Script |
|---|---|---|
| 狼美人 | `wolf_beauty_open_eyes.mp3` | 狼美人请睁眼 |
| 狼美人 | `wolf_beauty_close_eyes.mp3` | 狼美人请闭眼 |
| 摄梦人 | `dreamer_open_eyes.mp3` | 摄梦人请睁眼 |
| 摄梦人 | `dreamer_close_eyes.mp3` | 摄梦人请闭眼 |
| 魔术师 | `magician_open_eyes.mp3` | 魔术师请睁眼 |
| 魔术师 | `magician_close_eyes.mp3` | 魔术师请闭眼 |
| 丘比特 | `cupid_open_eyes.mp3` | 丘比特请睁眼 |
| 丘比特 | `cupid_close_eyes.mp3` | 丘比特请闭眼 |
| 情侣 (after 丘比特 links them) | `lovers_open_eyes.mp3` | 情侣请睁眼 |
| 情侣 | `lovers_close_eyes.mp3` | 情侣请闭眼 |

隐狼 and 恶灵骑士 need none: 隐狼 wakes with the pack, and 恶灵骑士's skill is
passive.

The names follow the existing `<role>_open_eyes.mp3` pattern. When a role is
implemented, the name in its `AudioConfig` is the one that counts; rename the
file if they differ.
