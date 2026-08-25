-- Tokens (and whole sessions) that must stop being accepted before their `exp`.
-- One key column rather than a composite key, so the generic cleanup manager can
-- batch on it: 'jti:<access token id>' kills one token, 'sid:<session id>' kills
-- every token issued under that SSO session, including ones this edge never saw,
-- and 'sub:<user id>' kills every token a user holds.
--
-- issued_before belongs to 'sub:' entries alone, which differ from the other two
-- kinds in that the user can log in again while the entry is still live: only
-- tokens issued before that instant are rejected. Null for 'jti:' and 'sid:',
-- where nothing can issue a token under the key any more.
CREATE TABLE revocations (
    revoked_key   TEXT PRIMARY KEY,
    revoked_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    issued_before TIMESTAMP WITH TIME ZONE
);

CREATE INDEX revocations_expires_at_idx ON revocations (expires_at);

-- Replicas read the list by (revoked_at, revoked_key), resuming from where they stopped,
-- so that a periodic catch-up costs the rows written since the last one rather than a scan
-- of every unexpired revocation. Without this index that ordering is a sort of the whole
-- table on every page.
--
-- The pair rather than revoked_at alone: it is the ordering the cursor resumes on, and a
-- page boundary landing between two rows written in the same instant would otherwise skip
-- one of them.
CREATE INDEX revocations_revoked_at_key_idx ON revocations (revoked_at, revoked_key);

-- Deletes do not notify: they are cleanup of entries already past expires_at,
-- which every replica drops on its own. Updates do, because re-revoking a key
-- widens what it covers and a replica that kept the old row would accept tokens
-- the new one rejects.
CREATE OR REPLACE FUNCTION notify_revocation()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'revocation',
    json_build_object(
      'key', NEW.revoked_key,
      'exp', extract(epoch from NEW.expires_at)::bigint,
      'before', extract(epoch from NEW.issued_before)::bigint
    )::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER revocations_notify
AFTER INSERT OR UPDATE ON revocations
FOR EACH ROW EXECUTE FUNCTION notify_revocation();
