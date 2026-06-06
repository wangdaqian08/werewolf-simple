-- Room codes become 3 numeric digits (000–999) and are REUSABLE: once a room's
-- game ends, its code is free to recycle. Drop the global UNIQUE constraint
-- (finished rooms may share a recycled code with a newer active room) and shrink
-- the column. Uniqueness among *active* rooms is enforced in application code
-- (RoomService.generateCode via RoomRepository.findActiveByRoomCode).
--
-- Postgres auto-names the inline UNIQUE constraint from V1 `rooms_room_code_key`.
ALTER TABLE rooms DROP CONSTRAINT IF EXISTS rooms_room_code_key;

-- Any legacy 4-char alphanumeric codes would no longer fit VARCHAR(3). Closed
-- rooms are inert (their codes are reusable), so truncating their stored code is
-- harmless; active rooms in a fresh deploy use the new 3-digit generator.
UPDATE rooms SET room_code = LEFT(room_code, 3);

ALTER TABLE rooms ALTER COLUMN room_code TYPE VARCHAR(3);
