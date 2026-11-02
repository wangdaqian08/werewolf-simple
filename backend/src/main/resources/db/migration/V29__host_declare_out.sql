-- A player the host declared out mid-game (they had to leave): dead, never shoots.
ALTER TABLE game_players ADD COLUMN IF NOT EXISTS removed_by_host BOOLEAN NOT NULL DEFAULT FALSE;
