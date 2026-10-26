# Room setup flow — before and after

## Before

The player count and roles are fixed when the room is created.

```mermaid
flowchart TD
    A[Lobby: host taps Create Room] --> B["Create Room page<br/>player count + roles + game settings<br/>(all in one form)"]
    B -->|Create| C[Room: code shown, players join and sit]
    C --> D{More players than seats?}
    D -->|No| E[All seated + ready → host starts game]
    D -->|Yes: next joiner gets 'Room is full'| F[Host abandons the room]
    F --> G[Host creates a new room<br/>new code, everyone joins again]
    G --> C
```

## After

Game settings are chosen once. The player count and roles stay editable until the game starts.

```mermaid
flowchart TD
    A[Lobby: host taps Create Room] --> B["Page 1 · Game settings<br/>BGM, perks, sheriff, win condition, toggles"]
    B -->|Create Room| C["Page 2 · Players & roles<br/>player count, wolves, roles<br/>joined count · 📤 one-tap share link"]
    C -->|Done| D["Room: players join via link or code<br/>📤 share button for everyone"]
    D --> E{Headcount matches seats?}
    E -->|Yes| F[All seated + ready → host starts game]
    E -->|No: more or fewer people| G[Host taps 'Edit players/roles']
    G --> C
    C -.->|board changed| H[Same code, same members<br/>guests asked to ready up again]
    H -.-> D

    L["Invitee opens link /?room=123"] --> M[Lobby with room code pre-filled] --> D
```

## Rules the backend enforces

| Rule | Message |
|---|---|
| Only the host may change the board, only while the room is waiting | 403 / "Room is not open" |
| Can't go below the members who joined (seated or not) | "6 players have joined" |
| Can't remove a seat someone sits in | "Seat 8 is taken" |
| Role mix must stay valid (same check as creation) | composition error |
| A board change un-readies every guest | — |
| Start needs exactly the configured number of players | "Need 7 players, have 6" |
| A seat beyond the room size can't be claimed | "Seat 7 does not exist" |
