-- PK re-vote (平票PK): after a first-round tie only the tied players can be
-- voted for, and they cannot vote. Comma-separated user ids; NULL outside a
-- re-vote (empty tally = everyone abstained = no PK). Reset at night-init.
ALTER TABLE games ADD COLUMN pk_candidate_user_ids VARCHAR(1024);
