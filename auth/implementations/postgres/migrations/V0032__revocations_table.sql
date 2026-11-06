-- Access tokens that must stop being accepted before their `exp`.
--
-- Named `auth_revocations`, not `revocations`: edge has a `revocations` table of its own (V2003), and a
-- development or test setup that runs the services against one schema (the generated dev configs, the e2e
-- stack) would otherwise have the two collide, on the table, its indexes, the trigger function and the
-- `revocation` NOTIFY channel, whose payload the other service would also receive. Everything below that is
-- named at database level carries the prefix for the same reason. Auth keeps no record of the
-- tokens it issues (V0004), so a revocation names what it ends rather than listing tokens, and a
-- row exists only for as long as a token it covers could still be valid.
--
-- One key column rather than a composite key, so the generic cleanup manager can batch on it:
-- 'jti:<access token id>' ends one token, 'fam:<refresh token family id>' every token a refresh
-- chain ever issued, 'sid:<public session id>' every token issued under that SSO session, and
-- 'sub:<user id>' every token a user holds. 'tenant:<id>' and 'client:<id>' are reserved for
-- tenant- and client-wide revocations; nothing writes them yet, and the key format is fixed here
-- so that it does not change when something does.
--
-- Written in the transaction that ends the thing the token depends on (the session, the refresh
-- family), so the record and its cause commit together or not at all. Keys are unique across
-- tenants (ids are random), so the table needs no tenant column.
--
-- issued_before belongs to the 'sub:', 'tenant:' and 'client:' entries, which differ from the
-- rest in that the user can log in again while the entry is still live: only tokens issued before
-- that instant are rejected. Null where nothing can issue a token under the key any more.
--
-- cause says why the row exists ('revoke', 'code_replay', 'refresh_reuse', 'logout',
-- 'admin_revoked', 'user_agent_forgotten', 'user_revoked', 'user_deleted'). It is for operators
-- and later consumers of the table; nothing decides on it.
--
-- No DEFAULT on revoked_at: the writer stamps it with the database's own NOW() (see
-- PostgresRevocationRepository) so that the cursor below orders rows by one clock.
CREATE TABLE auth_revocations (
    revoked_key   TEXT PRIMARY KEY,
    revoked_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    issued_before TIMESTAMP WITH TIME ZONE,
    cause         TEXT NOT NULL
);

CREATE INDEX auth_revocations_expires_at_idx ON auth_revocations (expires_at);

-- Readers (every auth replica, later the feed for edges) resume from where they stopped by
-- (revoked_at, revoked_key), so that a periodic catch-up costs the rows written since the last
-- one rather than a sort of every unexpired revocation. The pair rather than revoked_at alone:
-- two rows written in the same instant would otherwise make a page boundary skip one.
CREATE INDEX auth_revocations_revoked_at_key_idx ON auth_revocations (revoked_at, revoked_key);

-- Deletes do not notify: they are cleanup of entries already past expires_at, which every
-- replica drops on its own. Updates do, because re-revoking a key widens what it covers and a
-- replica that kept the old row would accept tokens the new one rejects.
CREATE OR REPLACE FUNCTION notify_auth_revocation()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify(
    'auth_revocation',
    json_build_object(
      'key', NEW.revoked_key,
      'exp', extract(epoch from NEW.expires_at)::bigint,
      'before', extract(epoch from NEW.issued_before)::bigint
    )::text
  );
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER auth_revocations_notify
AFTER INSERT OR UPDATE ON auth_revocations
FOR EACH ROW EXECUTE FUNCTION notify_auth_revocation();
