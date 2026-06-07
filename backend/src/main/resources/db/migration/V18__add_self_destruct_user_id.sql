-- The wolf who self-destructed (自爆) this day, surfaced in the day death banner.
-- Nullable; reset to NULL at night-init (parallel to day_skip_voting from V12).
ALTER TABLE games ADD COLUMN self_destruct_user_id VARCHAR(128);
