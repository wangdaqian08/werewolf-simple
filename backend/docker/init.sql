-- Runs once on first container startup (before Flyway migrations).
-- Flyway handles the actual schema; this file is for DB-level setup only.

-- Ensure the werewolf user has full privileges on the database
GRANT
ALL
PRIVILEGES
ON
DATABASE
werewolf TO werewolf;

-- Allow the user to create schemas (needed by Flyway for the flyway_schema_history table)
ALTER
USER werewolf CREATEDB;
