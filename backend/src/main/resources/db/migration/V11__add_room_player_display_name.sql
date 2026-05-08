-- Per-room nickname override (Option A from the OAuth follow-up).
--
-- Lets a user pick a different display name for THIS specific room without
-- mutating their User row's nickname. The User row keeps the OAuth-provided
-- (or guest-typed) nickname and is still auto-refreshed on every login.
--
-- Backward compatible: NULL means "no override, use User.nickname". Existing
-- rows are unaffected.
ALTER TABLE room_players ADD COLUMN display_name VARCHAR(50);
