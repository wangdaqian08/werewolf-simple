ALTER TABLE rooms ADD COLUMN wolf_count INTEGER NOT NULL DEFAULT 2;

UPDATE rooms SET wolf_count = CASE
    WHEN total_players <= 6 THEN 2
    WHEN total_players <= 9 THEN 3
    ELSE total_players / 3
END;
