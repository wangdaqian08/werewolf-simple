-- Sheriff campaign speaking order is random again (shuffled at SPEECH start);
-- the host-chosen ASC/DESC direction from V21 is no longer used.
ALTER TABLE sheriff_elections DROP COLUMN speech_order_direction;
