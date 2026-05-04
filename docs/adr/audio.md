# Audio

Audio files are served by the backend from `backend/src/main/resources/static/audio/`
(accessible as `/audio/<filename>.mp3`). The architecture is **server-authoritative**:
the backend computes which files to play and broadcasts an `AudioSequence` STOMP
event; the frontend's job is purely to play whatever the server told it to play.

## Architecture (server-authoritative)

```
Backend                                   Frontend
─────────                                 ──────────
NightOrchestrator / GamePhasePipeline /
SheriffService construct an AudioSequence
        │
        ▼
StompPublisher.broadcastGame              [stomp] AudioSequence event
  ├─ side-effects into AudioReplayCache   ────────────────────────────►
  └─ /topic/game/{gameId}                                              │
                                                                       ▼
                                                         gameStore.state.audioSequence
                                                                       │
                                                                       ▼
                                                         useAudioService watcher
                                                                       │
                                                                       ▼
                                                         audioService.playSequential
                                                         (HTMLAudioElement queue)
```

The frontend never decides "what to play" — it dedups by `AudioSequence.id`
(useAudioService `playedIds` Set, capped at 50) and plays the supplied file list.
A second watcher on `audioReplayBuffer` (see below) handles reconnect recovery
through the same `tryPlay` gate, so the live STOMP path and the HTTP recovery
path share one dedup.

### Reconnect recovery (`AudioReplayCache`)

Spring's `SimpleBroker` is fire-and-forget — broadcasts to a momentarily-offline
subscriber are dropped on the wire. STOMP-JS auto-reconnects after
`reconnectDelay: 3000` ms, so any AudioSequence broadcast during the disconnect
window would otherwise be permanently lost (background-tab heartbeat throttling
on macOS Spaces / mobile background is the typical trigger).

`AudioReplayCache` (in `backend/src/main/kotlin/com/werewolf/audio/`) keeps a
small per-game ring buffer (default 8 frames) of recent broadcasts.
`StompPublisher.broadcastGame` side-effects into the cache for every
`DomainEvent.AudioSequence` and clears it on `DomainEvent.GameOver`.
`GameService.getGameState` surfaces both fields:

- `audioSequence` — most recent (legacy single-field contract)
- `audioReplayBuffer` — full ring (recovery path)

On reconnect, `GameView.refreshState()` picks up the buffer; the frontend's
`useAudioService` iterates each entry through the same dedup gate as live
events, so missed cues replay and already-played ones skip.

## Trigger Map (role narration + transition cues)

Computed by `AudioService.kt` (`calculatePhaseTransition`,
`calculateNightSubPhaseTransition`) and by per-cue `broadcastAudio` calls in
`NightOrchestrator.nightRoleLoop`.

| Event / sub-phase                                      | File(s)                                  |
|--------------------------------------------------------|------------------------------------------|
| Enter `NIGHT` (any night)                              | `goes_dark_close_eyes.mp3`, `wolf_howl.mp3` |
| `WEREWOLF_PICK` opens                                  | `wolf_open_eyes.mp3`                     |
| `WEREWOLF_PICK` → `SEER_PICK` close                    | `wolf_close_eyes.mp3`                    |
| `SEER_PICK` opens                                      | `seer_open_eyes.mp3`                     |
| `SEER_RESULT` close (after seer confirms)              | `seer_close_eyes.mp3`                    |
| `WITCH_ACT` opens                                      | `witch_open_eyes.mp3`                    |
| `WITCH_ACT` close                                      | `witch_close_eyes.mp3`                   |
| `GUARD_PICK` opens                                     | `guard_open_eyes.mp3`                    |
| `GUARD_PICK` close                                     | `guard_close_eyes.mp3`                   |
| `NIGHT` → `DAY_DISCUSSION/RESULT_HIDDEN` (Day 2+)      | `rooster_crowing.mp3`, `day_time.mp3`    |
| `NIGHT` → `SHERIFF_ELECTION/SIGNUP` (Variant B Day 1)  | `rooster_crowing.mp3`, `day_time.mp3` (delayed by `sheriffMorningCueDelayMs`) |

Dead roles still receive the same open-eyes → delay → close-eyes sequence (see
ADR-010 Dead-Role Information Hiding). Audio is identical whether the role
player is alive or dead.

## Background music (BGM)

Optional looping music per room, picked by the host on the create-room screen
and persisted inside the room's `GameConfig` JSONB column. Tracks are scanned
from `backend/src/main/resources/static/audio/bgm/` at startup
(`AudioTracksController`, 60 s TTL cache).

The BGM lifecycle is driven by frontend watchers in `useAudioService`:

| Watcher source                          | Action                                   |
|-----------------------------------------|------------------------------------------|
| `gameStore.state.phase === 'NIGHT'`     | `audioService.startBgm(track)`           |
| Any other phase                         | `audioService.stopBgm()`                 |
| `nightPhase.subPhase` ∈ HIGH_VOL_NIGHT_SUBPHASES | `setBgmLevel('HIGH')` (1.0×)     |
| Any other sub-phase                     | `setBgmLevel('LOW')` (0.45×)             |
| Narration queue active                  | `duckBgm()` (0.15×)                      |

Volume modulation is a 150 ms `requestAnimationFrame` tween on
`HTMLAudioElement.volume`. The earlier Web-Audio routing
(`createMediaElementSource → GainNode`) was replaced because
`AudioContext.resume()` is async and was not awaited before `play()`, leaving
the FIRST night silent. See PR #95 for the full rationale.

## Frontend usage

```ts
const { setVolume, getVolume, setBgmVolume, getBgmVolume, toggleMute, isMuted } =
  useAudioService()
setVolume(0.5)              // narration volume, 0–1
setBgmVolume(0.5)           // BGM base volume, 0–1, persisted to localStorage
const muted = toggleMute()  // also persisted
```

The `VolumeControl.vue` component wraps these and is rendered in the room +
game views.
