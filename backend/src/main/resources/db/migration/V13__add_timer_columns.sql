ALTER TABLE games
  ADD COLUMN timer_started_at   BIGINT,
  ADD COLUMN timer_duration_ms  BIGINT  NOT NULL DEFAULT 0,
  ADD COLUMN timer_running      BOOLEAN NOT NULL DEFAULT false;
