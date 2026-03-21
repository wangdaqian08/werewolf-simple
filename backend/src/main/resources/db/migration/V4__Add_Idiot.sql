ALTER TABLE rooms
    ADD has_idiot BOOLEAN;

ALTER TABLE rooms
    ALTER COLUMN has_idiot SET NOT NULL;