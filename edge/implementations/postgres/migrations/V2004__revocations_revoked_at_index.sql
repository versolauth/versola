-- Replicas read the list by (revoked_at, revoked_key), resuming from where they stopped,
-- so that a periodic catch-up costs the rows written since the last one rather than a scan
-- of every unexpired revocation. Without this index that ordering is a sort of the whole
-- table on every page.
--
-- The pair rather than revoked_at alone: it is the ordering the cursor resumes on, and a
-- page boundary landing between two rows written in the same instant would otherwise skip
-- one of them.
CREATE INDEX revocations_revoked_at_key_idx ON revocations (revoked_at, revoked_key);
