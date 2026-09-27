-- Issue #353: which FAPI profile a tenant's clients are held to. `standard`/`fapi2` today,
-- stored as text rather than a Postgres enum so a future profile needs no type migration.
--
-- `fapi2` for every existing row as well as the default for new ones: there are no external
-- consumers of this deployment yet, only the seed tenant, so nothing is grandfathered onto
-- `standard`. Registration/patch validation and runtime enforcement against this profile
-- land separately from the column itself.
ALTER TABLE challenge_settings ADD COLUMN security_profile TEXT NOT NULL DEFAULT 'fapi2';
