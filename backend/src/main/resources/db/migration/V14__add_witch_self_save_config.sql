-- Backfill the new `witchSelfSaveAllowed` key into the `rooms.config` JSONB
-- for every pre-existing row. Default is `true` to preserve the historical
-- behavior (witch could always self-save before this feature shipped).
--
-- Idempotent: the guard `NOT (config ? 'witchSelfSaveAllowed')` makes
-- re-running this migration a no-op on already-backfilled rows.
UPDATE rooms
SET config = jsonb_set(
        COALESCE(config, '{}'::jsonb),
        '{witchSelfSaveAllowed}',
        'true'::jsonb,
        true
    )
WHERE config IS NULL OR NOT (config ? 'witchSelfSaveAllowed');
