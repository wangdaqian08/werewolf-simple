# Audio

Audio files are served by the backend from `backend/src/main/resources/static/audio/`
(accessible as `/audio/<filename>.mp3`). The frontend triggers playback based on STOMP
events; volume is controllable via `useAudioService()`.

## Trigger Map

| Event / sub-phase                     | File                        |
|---------------------------------------|-----------------------------|
| `NIGHT` enter                         | `goes_dark_close_eyes.mp3`  |
| Night atmosphere (loop)               | `crow_night.mp3`            |
| `NIGHT / WEREWOLF_PICK` enter         | `wolf_howl.mp3` → `wolf_open_eyes.mp3` |
| Werewolf phase close                  | `wolf_close_eyes.mp3`       |
| `NIGHT / SEER_PICK` enter             | `seer_open_eyes.mp3`        |
| Seer phase close                      | `seer_close_eyes.mp3`       |
| `NIGHT / WITCH_ACT` enter             | `witch_open_eyes.mp3`       |
| Witch phase close                     | `witch_close_eyes.mp3`      |
| `NIGHT / GUARD_PICK` enter            | `guard_open_eyes.mp3`       |
| Guard phase close                     | `guard_close_eyes.mp3`      |
| Dawn / `DAY_PENDING`                  | `rooster_crowing.mp3`       |
| `DAY_DISCUSSION` / `DAY_VOTING` enter | `day_time.mp3`              |

Dead roles still receive the same open-eyes → delay → close-eyes sequence (see
ADR-010 Dead-Role Information Hiding). Audio is identical whether the role player is
alive or dead.

## Frontend usage

```ts
const { setVolume, getVolume } = useAudioService()
setVolume(0.5)         // 0–1
const volume = getVolume()
```
