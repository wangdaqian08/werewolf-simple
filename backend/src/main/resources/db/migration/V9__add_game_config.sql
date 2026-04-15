-- Add game configuration to rooms table
-- This migration adds a JSONB column to store game configuration, including role-specific delays

-- Add the config column to rooms table
ALTER TABLE rooms ADD COLUMN IF NOT EXISTS config JSONB;

-- Set default configuration for existing rooms
UPDATE rooms SET config = '{
  "roleDelays": {
    "WEREWOLF": {
      "actionWindowMs": 30000,
      "deadRoleDelayMs": 25000
    },
    "SEER": {
      "actionWindowMs": 20000,
      "deadRoleDelayMs": 15000
    },
    "WITCH": {
      "actionWindowMs": 25000,
      "deadRoleDelayMs": 20000
    },
    "GUARD": {
      "actionWindowMs": 20000,
      "deadRoleDelayMs": 15000
    }
  }
}'::jsonb WHERE config IS NULL;

-- Add comment to the column
COMMENT ON COLUMN rooms.config IS 'Game configuration stored as JSONB, including role-specific delays and other game settings';